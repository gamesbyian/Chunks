package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import java.util.Locale

/**
 * One explicitly approved, bounded group of stopped Local restores.
 *
 * Reuses the *same item-scoped durable journal* as the existing one-at-a-time
 * restore, so a later individual review or a differently sized batch cannot
 * accidentally give an uncertain prior Add a fresh journal. Every original is
 * copied and independently verified BEFORE the first native command. Each
 * successful Add is verified stopped at the reviewed Place before proceeding.
 *
 * This coordinator never starts transfers, copies payloads, retries writes or
 * uses historical source paths. Failure stops the remainder of the group.
 */
internal object SnapshotLocalBatchRestoreV1 {
    private const val MAX_ITEMS = 512
    // Bounded frozen buffers alongside the already-decoded .chunksnap must
    // not exhaust an older Android device before the first native Add.
    const val MAX_TOTAL_METAINFO_BYTES = 24 * 1024 * 1024
    private val v1 = Regex("[0-9a-f]{40}")
    private val targetIdentity = Regex("[0-9a-f]{40}([0-9a-f]{24})?")

    data class Result(
        val total: Int,
        val verified: Int,
        val stoppedAtInfoHash: String?,
        /** Null only when EVERY requested stopped restore was verified. */
        val stop: SnapshotRestoreOnceV1.Outcome?
    ) {
        val complete: Boolean get() = stop == null && total > 0 && verified == total
        val remaining: Int get() = total - verified
    }

    fun execute(
        reviewed: List<SnapshotVerifiedTorrentDocumentV1.Prepared>,
        archiveFingerprint: String,
        targetFingerprint: String,
        chosenPlace: File,
        verifiedPlaceFingerprint: String,
        frozenHashes: List<String>,
        userApproved: Boolean,
        store: SnapshotRestoreJournalV1.Store,
        target: SnapshotRestoreOnceV1.LocalTarget
    ): Result {
        val total = reviewed.size
        fun fail(reason: SnapshotRestoreOnceV1.Outcome, hash: String? = null) =
            Result(total, 0, hash, reason)
        if (!userApproved || total !in 1..MAX_ITEMS ||
            reviewed.sumOf { it.bytes.size.toLong() } > MAX_TOTAL_METAINFO_BYTES ||
            !chosenPlace.isAbsolute || !chosenPlace.isDirectory ||
            java.nio.file.Files.isSymbolicLink(chosenPlace.toPath()))
            return fail(SnapshotRestoreOnceV1.Outcome.REFUSED)

        // A corrupt *later* definition must not permit an earlier item to be
        // added. Copy ALL mutable review buffers before parsing any of them.
        val prepared = try {
            reviewed.map { candidate ->
                require(v1.matches(candidate.infoHashV1))
                val frozen = candidate.bytes.copyOf()
                val parsed = TorrentMetainfoReader.preview(frozen)
                require(parsed.infoHashV1 == candidate.infoHashV1 &&
                    parsed.name == candidate.originalName)
                candidate.copy(bytes = frozen)
            }.also { items ->
                require(items.map { it.infoHashV1 }.toSet().size == items.size)
            }.sortedBy { it.infoHashV1 }
        } catch (_: Exception) {
            return fail(SnapshotRestoreOnceV1.Outcome.REFUSED)
        }
        val normalized = frozenHashes.map { it.lowercase(Locale.ROOT) }
        if (normalized.size > 20_000 ||
            normalized.any { !targetIdentity.matches(it) } ||
            normalized.size != normalized.toSet().size ||
            prepared.any { it.infoHashV1 in normalized })
            return fail(SnapshotRestoreOnceV1.Outcome.STALE_TARGET)
        val actual = try { target.completeHashes() }
            catch (_: Exception) { null }
        val checked = actual?.map { it.lowercase(Locale.ROOT) }
        if (checked == null || checked.size > 20_000 ||
            checked.any { !targetIdentity.matches(it) } ||
            checked.size != checked.toSet().size ||
            checked.toSet() != normalized.toSet())
            return fail(SnapshotRestoreOnceV1.Outcome.STALE_TARGET)

        // Review EVERY per-item journal before the first native write. A
        // pending old individual restore in item N must not cause items 1..N-1
        // to be added before we discover it. Opening absent journals creates
        // only REVIEW records, never an Add or permission to replay one.
        val journals = ArrayList<Pair<SnapshotVerifiedTorrentDocumentV1.Prepared,
            SnapshotRestoreJournalV1>>(prepared.size)
        for (original in prepared) {
            val journal = try {
                val itemArchive = SnapshotRestoreOnceV1.itemArchiveFingerprint(
                    archiveFingerprint, original.infoHashV1)
                SnapshotRestoreJournalV1.open(
                    store, itemArchive, targetFingerprint, listOf(original.infoHashV1))
            } catch (_: Exception) {
                return fail(SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED,
                    original.infoHashV1)
            }
            val entry = journal.entries().singleOrNull()
            if (entry?.phase != SnapshotRestoreJournalV1.Phase.REVIEW &&
                !(entry?.phase == SnapshotRestoreJournalV1.Phase.READY &&
                    entry.placeFingerprint == verifiedPlaceFingerprint)) {
                return fail(SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED,
                    original.infoHashV1)
            }
            journals += original to journal
        }

        // SnapshotRestoreOnceV1 rechecks fresh inventory for every item and
        // durably records IN_FLIGHT before each native Add.
        val expected = normalized.toMutableSet()
        var verifiedCount = 0
        for ((original, journal) in journals) {
            val outcome = try {
                SnapshotRestoreOnceV1.execute(
                    journal, original, chosenPlace, verifiedPlaceFingerprint,
                    expected.toList(), userApproved = true, target = target
                )
            } catch (_: Exception) {
                SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED
            }
            if (outcome != SnapshotRestoreOnceV1.Outcome.VERIFIED_PRESENT_STOPPED)
                return Result(total, verifiedCount, original.infoHashV1, outcome)
            verifiedCount++
            expected += original.infoHashV1
        }
        return Result(total, verifiedCount, null, null)
    }
}
