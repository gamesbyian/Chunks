package io.github.gamesbyian.chunks.snapshots

import java.io.File
import java.util.Locale

/**
 * User-triggered, engine-read-only inspection of previous stopped Local
 * snapshot Adds after a crash, lost acknowledgement or interrupted group.
 *
 * Uses the SAME exact archive+item+target journal as one-at-a-time and batch
 * restores. openExisting() never constructs an absent journal. Only
 * reconcilePending() may record an independently proven VERIFIED receipt;
 * no engine Add, Start, Stop, retry, delete or payload access occurs.
 *
 * A missing/corrupt journal is UNKNOWN, not evidence that Add never ran.
 * A stored VERIFIED phase is also rechecked against CURRENT stopped state and
 * the user's previously reviewed canonical Place before it counts.
 */
internal object SnapshotLocalBatchRecoveryV1 {
    private const val MAX_ITEMS = 512
    private val v1 = Regex("[0-9a-f]{40}")
    private val targetHash = Regex("[0-9a-f]{40}([0-9a-f]{24})?")
    private val fingerprint = Regex("[0-9a-f]{64}")

    enum class State {
        /** Current complete inventory, exact hash, STOPPED, canonical Place proved. */
        VERIFIED_STOPPED,
        /** Durable REVIEW/READY/REFUSED state did not submit an Add. */
        NO_SUBMISSION_IN_THIS_JOURNAL,
        /** A prior Add may have happened; no current exact stopped-Place proof. */
        UNRESOLVED,
        /** Absent, corrupt or inaccessible journal. Never conclude safe to retry. */
        JOURNAL_UNAVAILABLE,
        /** The selected Place differs from the prior durable authorisation. */
        DIFFERENT_REVIEWED_PLACE
    }

    enum class Blocker { INVALID_REVIEW, DESTINATION_UNAVAILABLE, TARGET_CHANGED, CANCELLED }

    data class Item(val infoHashV1: String, val state: State)
    data class Report(val items: List<Item>, val blocker: Blocker? = null) {
        val completed: Boolean get() = blocker == null
        val verifiedCount: Int get() = items.count { it.state == State.VERIFIED_STOPPED }
        val unresolvedCount: Int get() = items.count {
            it.state in setOf(State.UNRESOLVED, State.JOURNAL_UNAVAILABLE,
                State.DIFFERENT_REVIEWED_PLACE)
        }
        val reviewOnlyCount: Int get() =
            items.count { it.state == State.NO_SUBMISSION_IN_THIS_JOURNAL }
    }

    fun inspect(
        hashes: List<String>,
        archiveFingerprint: String,
        targetFingerprint: String,
        chosenPlace: File,
        verifiedPlaceFingerprint: String,
        frozenTargetHashes: List<String>,
        store: SnapshotRestoreJournalV1.Store,
        target: SnapshotRestoreOnceV1.LocalTarget,
        mayContinue: () -> Boolean = { true }
    ): Report {
        fun blocked(reason: Blocker) = Report(emptyList(), reason)

        if (hashes.size !in 1..MAX_ITEMS ||
            hashes.any { !v1.matches(it) } ||
            hashes.size != hashes.toSet().size ||
            !fingerprint.matches(archiveFingerprint) ||
            !fingerprint.matches(targetFingerprint) ||
            !fingerprint.matches(verifiedPlaceFingerprint))
            return blocked(Blocker.INVALID_REVIEW)

        if (!chosenPlace.isAbsolute || !chosenPlace.isDirectory ||
            java.nio.file.Files.isSymbolicLink(chosenPlace.toPath()))
            return blocked(Blocker.DESTINATION_UNAVAILABLE)
        // This receipt is about the original approved canonical Place.
        // Never allow a caller to provide the old fingerprint alongside a
        // different folder and accidentally promote a mismatched Add journal.
        val independentPlace = try {
            LocalSnapshotRestoreFingerprint.of(
                "chunks-place-v1:" + chosenPlace.canonicalPath)
        } catch (_: Exception) {
            return blocked(Blocker.DESTINATION_UNAVAILABLE)
        }
        if (independentPlace != verifiedPlaceFingerprint)
            return blocked(Blocker.INVALID_REVIEW)

        fun canonicalInventory(values: List<String>?): Set<String>? {
            if (values == null || values.size > 20_000) return null
            val normalized = values.map { it.lowercase(Locale.ROOT) }
            if (normalized.any { !targetHash.matches(it) } ||
                normalized.size != normalized.toSet().size) return null
            return normalized.toSet()
        }
        val frozen = canonicalInventory(frozenTargetHashes)
            ?: return blocked(Blocker.INVALID_REVIEW)
        if (!mayContinue()) return blocked(Blocker.CANCELLED)
        val current = canonicalInventory(try { target.completeHashes() }
            catch (_: Exception) { null })
            ?: return blocked(Blocker.TARGET_CHANGED)
        if (current != frozen) return blocked(Blocker.TARGET_CHANGED)

        val statuses = ArrayList<Item>(hashes.size)
        for (hash in hashes.sorted()) {
            if (!mayContinue()) return blocked(Blocker.CANCELLED)
            // Opening an absent record as a fresh REVIEW would be an unsafe
            // recovery fiction. The exact item scope prevents batch reshaping
            // from bypassing uncertain individual restores.
            val journal = try {
                SnapshotRestoreJournalV1.openExisting(
                    store,
                    SnapshotRestoreOnceV1.itemArchiveFingerprint(archiveFingerprint, hash),
                    targetFingerprint,
                    listOf(hash)
                )
            } catch (_: Exception) {
                statuses += Item(hash, State.JOURNAL_UNAVAILABLE)
                continue
            }
            val entry = journal.entries().singleOrNull()
            val state = when {
                entry == null || entry.infoHashV1 != hash ->
                    State.JOURNAL_UNAVAILABLE
                entry.phase in setOf(
                    SnapshotRestoreJournalV1.Phase.REVIEW,
                    SnapshotRestoreJournalV1.Phase.READY,
                    SnapshotRestoreJournalV1.Phase.REFUSED
                ) -> State.NO_SUBMISSION_IN_THIS_JOURNAL
                entry.placeFingerprint != verifiedPlaceFingerprint ->
                    State.DIFFERENT_REVIEWED_PLACE
                else -> when (SnapshotRestoreOnceV1.reconcilePending(
                    journal, hash, chosenPlace, verifiedPlaceFingerprint, target
                )) {
                    SnapshotRestoreOnceV1.Outcome.VERIFIED_PRESENT_STOPPED ->
                        State.VERIFIED_STOPPED
                    SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED ->
                        State.JOURNAL_UNAVAILABLE
                    else -> State.UNRESOLVED
                }
            }
            statuses += Item(hash, state)
        }
        // A concurrent native Add/Remove while recovering revokes the group
        // summary; any earlier durable exact-item VERIFIED receipts remain
        // independently justified, but stale aggregate claims are withheld.
        if (!mayContinue()) return blocked(Blocker.CANCELLED)
        val final = canonicalInventory(try { target.completeHashes() }
            catch (_: Exception) { null })
            ?: return blocked(Blocker.TARGET_CHANGED)
        // Known later proofs can add hashes during recovery only when another
        // caller concurrently mutated the engine. This check must not silently
        // present a coherent batch if that happened.
        if (final != current) return blocked(Blocker.TARGET_CHANGED)
        return Report(statuses)
    }
}
