package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * Backend-only, bounded composition of the existing durable one-item stopped
 * Local restore. No UI calls this yet. A future batch review must explicitly
 * authorize every original definition and each independently chosen Local Place.
 *
 * Never calls Start, Move, Remove or any remote API. Each native Add has its
 * OWN durable exact-archive/item/target journal. An uncertain Add halts the
 * entire batch, including when a later read-only inventory looks absent.
 *
 * A successful Add changes the inventory baseline before the next item. Any
 * external inventory drift, corrupt inventory, missing folder or lost journal
 * commit halts the remaining queue rather than issuing an unreviewed command.
 */
internal object SnapshotStoppedBatchV1 {
    const val MAX_ITEMS = 32
    private const val MAX_ORIGINAL_BYTES = 2 * 1024 * 1024
    private val V1_HASH = Regex("[0-9a-f]{40}")
    private val TARGET_HASH = Regex("[0-9a-f]{40}([0-9a-f]{24})?")
    private val FINGERPRINT = Regex("[0-9a-f]{64}")

    data class Candidate(
        val reviewed: SnapshotVerifiedTorrentDocumentV1.Prepared,
        /** Current Local destination chosen by the owner, never an old snapshot path. */
        val chosenPlace: File
    ) {
        override fun toString() = "SnapshotStoppedBatchV1.Candidate(private original and Place)"
    }

    enum class State {
        /** Exact identity existed already: no Add, no claim of stopped Place or restored bytes. */
        PRESENT_UNVERIFIED,
        VERIFIED_STOPPED,
        NOT_ATTEMPTED,
        UNCERTAIN,
        REFUSED,
        STALE_TARGET,
        JOURNAL_BLOCKED
    }

    enum class Halt { NONE, NOT_APPROVED, STALE_TARGET, UNCERTAIN, REFUSED, JOURNAL_BLOCKED }

    data class Item(val infoHashV1: String, val state: State) {
        override fun toString() = "SnapshotStoppedBatchV1.Item(private identity, state=$state)"
    }

    data class Report(val items: List<Item>, val halt: Halt) {
        override fun toString() = "SnapshotStoppedBatchV1.Report(items=${items.size}, halt=$halt)"
    }

    fun execute(
        archiveFingerprint: String,
        targetFingerprint: String,
        candidates: List<Candidate>,
        frozenHashes: List<String>,
        userApproved: Boolean,
        store: SnapshotRestoreJournalV1.Store,
        target: SnapshotRestoreOnceV1.LocalTarget
    ): Report {
        require(candidates.size <= MAX_ITEMS) { "Too many stopped-restore candidates in one review" }
        val requested = candidates.map { Item(it.reviewed.infoHashV1, State.NOT_ATTEMPTED) }
        if (!userApproved) return Report(requested, Halt.NOT_APPROVED)

        // Validate the ENTIRE reviewed batch before opening a journal or
        // submitting the first Add. Copies prevent callers mutating originals
        // after this preflight. Never persist raw metainfo, names or paths.
        require(FINGERPRINT.matches(archiveFingerprint) &&
            FINGERPRINT.matches(targetFingerprint)) { "Invalid snapshot/target identity" }
        val reviewed = candidates.map { candidate ->
            val source = candidate.reviewed
            require(V1_HASH.matches(source.infoHashV1) &&
                source.bytes.size in 1..MAX_ORIGINAL_BYTES) {
                "Invalid stopped-restore original"
            }
            val copy = source.copy(bytes = source.bytes.copyOf())
            val preview = try { TorrentMetainfoReader.preview(copy.bytes) }
                catch (_: Exception) { throw IllegalArgumentException("Invalid stopped-restore original") }
            require(preview.infoHashV1 == copy.infoHashV1 &&
                preview.name == copy.originalName) { "Snapshot original identity changed" }
            val file = candidate.chosenPlace
            require(file.isAbsolute && file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
                "Reviewed Local destination is unavailable"
            }
            val canonical = try { file.canonicalFile }
                catch (_: Exception) { throw IllegalArgumentException("Reviewed Local destination is unavailable") }
            val fingerprint = LocalSnapshotRestoreFingerprint.of(
                "chunks-place-v1:" + canonical.path)
            Triple(copy, canonical, fingerprint)
        }
        require(reviewed.map { it.first.infoHashV1 }.distinct().size == reviewed.size) {
            "The same snapshot identity was selected twice"
        }
        val original = canonical(frozenHashes)
        val initial = readInventory(target)
        if (original == null || initial == null || original != initial)
            return Report(requested, Halt.STALE_TARGET)

        val results = mutableListOf<Item>()
        val expected = initial.toMutableSet()
        for ((index, item) in reviewed.withIndex()) {
            val (source, place, placeFingerprint) = item
            val hash = source.infoHashV1
            if (hash in expected) {
                // Presence from an unrelated transfer or a prior uncertain
                // Add is NEVER called a verified stopped restore here.
                results += Item(hash, State.PRESENT_UNVERIFIED)
                continue
            }
            if (readInventory(target) != expected)
                return halted(results, requested, index, Halt.STALE_TARGET)
            val journal = try {
                SnapshotRestoreJournalV1.open(
                    store,
                    SnapshotRestoreOnceV1.itemArchiveFingerprint(archiveFingerprint, hash),
                    targetFingerprint,
                    listOf(hash)
                )
            } catch (_: Exception) {
                return halted(results, requested, index, Halt.JOURNAL_BLOCKED)
            }
            val outcome = SnapshotRestoreOnceV1.execute(
                journal, source, place, placeFingerprint,
                expected.sorted(), userApproved = true, target = target
            )
            val state = when (outcome) {
                SnapshotRestoreOnceV1.Outcome.VERIFIED_PRESENT_STOPPED -> State.VERIFIED_STOPPED
                SnapshotRestoreOnceV1.Outcome.STALE_TARGET -> State.STALE_TARGET
                SnapshotRestoreOnceV1.Outcome.REFUSED -> State.REFUSED
                SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED -> State.JOURNAL_BLOCKED
                SnapshotRestoreOnceV1.Outcome.UNCERTAIN -> State.UNCERTAIN
            }
            results += Item(hash, state)
            if (state == State.VERIFIED_STOPPED) {
                expected += hash
            } else {
                val halt = when (state) {
                    State.STALE_TARGET -> Halt.STALE_TARGET
                    State.REFUSED -> Halt.REFUSED
                    State.JOURNAL_BLOCKED -> Halt.JOURNAL_BLOCKED
                    else -> Halt.UNCERTAIN
                }
                return halted(results, requested, index + 1, halt)
            }
        }
        return Report(results, Halt.NONE)
    }

    private fun halted(
        results: List<Item>, requested: List<Item>, nextIndex: Int, cause: Halt
    ): Report = Report(results + requested.drop(nextIndex), cause)

    private fun readInventory(target: SnapshotRestoreOnceV1.LocalTarget): Set<String>? =
        try { target.completeHashes()?.let(::canonical) } catch (_: Exception) { null }

    private fun canonical(values: List<String>): Set<String>? {
        if (values.size > 20_000) return null
        val lower = values.map { it.lowercase(Locale.ROOT) }
        if (lower.any { !TARGET_HASH.matches(it) } || lower.size != lower.toSet().size)
            return null
        return lower.toSet()
    }
}
