package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteReadAuthEvidence
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One explicitly approved set of missing v1 definitions, each restored STOPPED
 * at the exact current rTorrent account and user-reviewed Place.
 *
 * This is distinct from read-only previous-journal group recovery. Every source
 * definition and every existing per-item journal is vetted BEFORE the first
 * non-idempotent Add. A later journal cannot reveal an earlier unknown outcome
 * only after the batch has already changed another transfer.
 *
 * Journals may be initialized in REVIEW during prepare; this never mutates the
 * server or authorizes any Add. Execute preflights ALL journals again, then
 * delegates individual writes and readbacks to the accepted single-item
 * coordinator. One uncertain result stops the remainder, without retry or
 * rollback. No Start, payload copy, data delete or historic path reuse.
 */
internal class SnapshotRemoteBatchStoppedRestore(
    private val remote: RTorrentRemote,
    private val store: SnapshotRestoreJournalV1.Store,
    private val stillCurrent: () -> Boolean,
    private val admitBatch: () -> Boolean,
    private val settleBatch: () -> Unit
) {
    // One Compose-owned exclusive mutation lease spans the ENTIRE user-approved
    // series. The accepted single writer still enforces its own prewrite intent
    // and stopped/hash/Place readback per item, but must not reacquire the
    // global UI readback gate after every constituent RPC. A fresh UI readback
    // releases that gate only AFTER the group settles or stops.
    private val running = AtomicBoolean(false)
    private val batchLease = AtomicBoolean(false)
    private val one = SnapshotRemoteDefinitionRestore(
        remote, store, stillCurrent,
        admitWrite = { batchLease.get() && stillCurrent() },
        settleWrite = {})

    enum class Stop {
        NOT_CONFIRMED, STALE, JOURNAL_BLOCKED, UNVERIFIED_WRITE, SESSION_CHANGED,
        LEASE_UNAVAILABLE
    }

    data class Result(
        val total: Int, val verified: Int, val stoppedAtHash: String?, val stop: Stop?
    ) {
        val complete: Boolean get() = stop == null && total > 0 && total == verified
        val remaining: Int get() = total - verified
    }

    /** Process-local consent draft, never serialized or copied. */
    class Draft internal constructor(
        internal val owner: SnapshotRemoteBatchStoppedRestore,
        internal val archive: SnapshotManifestV1.Verified,
        internal val frozenHashes: Set<String>,
        internal val scope: String,
        internal val selectedPlace: String?,
        internal val frozen: List<SnapshotRemoteDefinitionRestore.Review>
    ) {
        val count: Int get() = frozen.size
        val place: String get() = frozen.first().place
        val serverDefault: Boolean get() = frozen.first().serverDefault
        val items: List<Pair<String, String>> get() = frozen.map { it.hash to it.name }
    }

    companion object {
        private const val MAX_ITEMS = 32
        private const val MAX_TOTAL_METAINFO_BYTES = 24 * 1024 * 1024
        private val v1 = Regex("[0-9a-f]{40}")
        private val observedHash = Regex("[0-9a-f]{40}([0-9a-f]{24})?")
    }

    /**
     * Read-only server preflight. The only local mutation is initialization of
     * absent per-item REVIEW journals after every definition has been checked.
     * Existing IN_FLIGHT/ACK_UNVERIFIED/VERIFIED/REFUSED journals block the
     * complete proposal, even when that hash is absent from the current server.
     */
    suspend fun prepare(
        archive: SnapshotManifestV1.Verified,
        selectedHashes: List<String>,
        comparedHashes: List<String>,
        selectedPlace: String? = null
    ): Draft? = readOrNull {
        if (!stillCurrent() || !authenticated() ||
            selectedHashes.size !in 2..MAX_ITEMS ||
            selectedHashes.any { !v1.matches(it) } ||
            selectedHashes.size != selectedHashes.toSet().size) return@readOrNull null

        val selected = selectedHashes.sorted()
        if (archive.manifest.items.count {
                it.recovery == SnapshotManifestV1.Recovery.METAINFO &&
                    it.infoHashV1 in selected
            } != selected.size) return@readOrNull null

        val initial = currentRoster() ?: return@readOrNull null
        if (!sameRoster(initial, comparedHashes)) return@readOrNull null
        val scope = remote.addAccountScope()

        // Freeze and validate ALL originals, capability and destination before
        // any durable journal is opened or any remote write is reachable.
        val reviews = ArrayList<SnapshotRemoteDefinitionRestore.Review>(selected.size)
        var total = 0L
        for (hash in selected) {
            if (!stillCurrent() || !authenticated() ||
                remote.addAccountScope() != scope) return@readOrNull null
            val review = one.review(
                archive, hash, comparedHashes, recoveryOnly = false,
                selectedPlace = selectedPlace) ?: return@readOrNull null
            if (hash in initial || review.frozenHashes != initial) return@readOrNull null
            total += review.original.copyVerifiedBytes().size
            if (total > MAX_TOTAL_METAINFO_BYTES || (reviews.isNotEmpty() &&
                    (review.place != reviews.first().place ||
                        review.serverDefault != reviews.first().serverDefault)))
                return@readOrNull null
            reviews += review
        }

        // Reject known pending writes ANYWHERE in the proposed set before
        // submitting item one. Opening absent journals only creates REVIEW.
        val target = accountKey(scope)
        withContext(Dispatchers.IO) {
            for (review in reviews) {
                val journal = SnapshotRestoreJournalV1.open(
                    store, review.journalIdentity, target, listOf(review.hash))
                if (!safeJournal(journal, review)) return@withContext false
            }
            true
        }.takeIf { it } ?: return@readOrNull null

        if (!stillCurrent() || !authenticated() || remote.addAccountScope() != scope ||
            currentRoster() != initial) return@readOrNull null
        Draft(this@SnapshotRemoteBatchStoppedRestore,
            archive, initial, scope, selectedPlace, reviews)
    }

    /**
     * The user must separately confirm this exact process-local proposal.
     * No write is attempted until all per-item journals and full inventory
     * match the review again. Each next item gets a NEW current-session
     * review, never the stale pre-first-Add roster.
     */
    suspend fun execute(draft: Draft, confirmed: Boolean): Result {
        val total = draft.frozen.size
        fun refuse(why: Stop, done: Int = 0, hash: String? = null) =
            Result(total, done, hash, why)
        if (!confirmed || draft.owner !== this) return refuse(Stop.NOT_CONFIRMED)
        if (!running.compareAndSet(false, true)) return refuse(Stop.LEASE_UNAVAILABLE)
        try {
            if (!stillCurrent() || !authenticated() ||
                remote.addAccountScope() != draft.scope) return refuse(Stop.STALE)

            val current = readOrNull { currentRoster() } ?: return refuse(Stop.STALE)
            if (current != draft.frozenHashes) return refuse(Stop.SESSION_CHANGED)
            val target = accountKey(draft.scope)
            val journalsOk = readOrNull {
                withContext(Dispatchers.IO) {
                    draft.frozen.all { review ->
                        val journal = SnapshotRestoreJournalV1.openExisting(
                            store, review.journalIdentity, target, listOf(review.hash))
                        safeJournal(journal, review)
                    }
                }
            } ?: false
            if (!journalsOk) return refuse(Stop.JOURNAL_BLOCKED)

            // The same global mutation gate used by ordinary Add is acquired
            // ONCE and remains held through every constituent verified Add.
            // Therefore unrelated UI commands cannot interleave, and no
            // second gate acquisition is needed before a proper batch readback.
            if (!admitBatch()) return refuse(Stop.LEASE_UNAVAILABLE)
            batchLease.set(true)
            try {
                val expected = current.toMutableSet()
                var verified = 0
                for (original in draft.frozen) {
                    if (!stillCurrent() || !authenticated() ||
                        remote.addAccountScope() != draft.scope)
                        return refuse(Stop.STALE, verified, original.hash)
                    val before = readOrNull { currentRoster() }
                        ?: return refuse(Stop.SESSION_CHANGED, verified, original.hash)
                    if (before != expected)
                        return refuse(Stop.SESSION_CHANGED, verified, original.hash)

                    val fresh = readOrNull {
                        one.review(draft.archive, original.hash, expected.toList(),
                            recoveryOnly = false, selectedPlace = draft.selectedPlace)
                    } ?: return refuse(Stop.STALE, verified, original.hash)
                    if (fresh.place != original.place ||
                        fresh.serverDefault != original.serverDefault ||
                        fresh.name != original.name ||
                        fresh.journalIdentity != original.journalIdentity)
                        return refuse(Stop.STALE, verified, original.hash)

                    val outcome = readOrNull { one.restore(fresh, confirmed = true) }
                        ?: return refuse(Stop.UNVERIFIED_WRITE, verified, original.hash)
                    if (outcome != SnapshotRemoteDefinitionRestore.Outcome.VERIFIED) {
                        val reason = when (outcome) {
                            SnapshotRemoteDefinitionRestore.Outcome.BLOCKED -> Stop.JOURNAL_BLOCKED
                            SnapshotRemoteDefinitionRestore.Outcome.STALE -> Stop.STALE
                            else -> Stop.UNVERIFIED_WRITE
                        }
                        return refuse(reason, verified, original.hash)
                    }

                    verified++
                    expected += original.hash
                    val after = readOrNull { currentRoster() }
                        ?: return refuse(Stop.SESSION_CHANGED, verified, original.hash)
                    if (after != expected)
                        return refuse(Stop.SESSION_CHANGED, verified, original.hash)
                }
                return Result(total, verified, null, null)
            } finally {
                // Even an unknown ACK or cancellation must leave the outer
                // gate latched until the ordinary NEW UI readback finishes.
                batchLease.set(false)
                settleBatch()
            }
        } finally {
            running.set(false)
        }
    }

    private suspend fun currentRoster(): Set<String>? {
        val rows = remote.listCompleteSessionTransfers()
        val identities = rows.map { it.hash.lowercase(Locale.ROOT) }
        if (rows.size > 20_000 || identities.size != identities.toSet().size ||
            identities.any { !observedHash.matches(it) }) return null
        return identities.toSet()
    }

    private fun sameRoster(observed: Set<String>, frozen: List<String>): Boolean {
        val normalized = frozen.map { it.lowercase(Locale.ROOT) }
        return normalized.size == observed.size &&
            normalized.size == normalized.toSet().size &&
            normalized.toSet() == observed
    }

    private fun safeJournal(
        journal: SnapshotRestoreJournalV1,
        review: SnapshotRemoteDefinitionRestore.Review
    ): Boolean {
        val entry = journal.entries().singleOrNull() ?: return false
        if (entry.infoHashV1 != review.hash) return false
        return entry.phase == SnapshotRestoreJournalV1.Phase.REVIEW ||
            (entry.phase == SnapshotRestoreJournalV1.Phase.READY &&
                entry.placeFingerprint == placeKey(review))
    }

    private fun placeKey(review: SnapshotRemoteDefinitionRestore.Review) = digest(
        (if (review.serverDefault) "chunks-rtorrent-snapshot-default-v1:"
         else "chunks-rtorrent-snapshot-selected-v1:") + review.place)

    private fun accountKey(scope: String) =
        digest("chunks-rtorrent-snapshot-account-v1:" + scope)

    private fun authenticated() =
        remote.readAuthenticationEvidence() == RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED

    private fun digest(value: String) =
        SnapshotArchiveV1.digest(value.toByteArray(Charsets.UTF_8))

    private suspend fun <T> readOrNull(read: suspend () -> T): T? = try {
        read()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }
}
