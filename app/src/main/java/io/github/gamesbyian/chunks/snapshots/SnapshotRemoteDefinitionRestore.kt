package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
import io.github.gamesbyian.chunks.rpc.RemoteReadAuthEvidence
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.StoppedRemoteAddPlan
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Restores one original definition at a reviewed rTorrent Place, using the ordinary stopped Add. */
internal class SnapshotRemoteDefinitionRestore(
    private val remote: RTorrentRemote,
    private val store: SnapshotRestoreJournalV1.Store,
    private val stillCurrent: () -> Boolean,
    private val admitWrite: () -> Boolean,
    private val settleWrite: () -> Unit
) {
    enum class Outcome { VERIFIED, OBSERVED_STOPPED, STALE, BLOCKED, UNCERTAIN }

    /**
     * Prior-journal report for a bounded verified archive. None of these states
     * grants permission for a new load; even an unavailable journal may conceal
     * an earlier submitted Add.
     */
    enum class PreviousState {
        VERIFIED_STOPPED, NO_SUBMISSION_IN_THIS_JOURNAL,
        JOURNAL_UNAVAILABLE, DIFFERENT_REVIEWED_PLACE, UNRESOLVED
    }

    data class PreviousItem(val hash: String, val name: String, val state: PreviousState)
    data class PreviousReport(val items: List<PreviousItem>) {
        val verifiedCount: Int get() = items.count { it.state == PreviousState.VERIFIED_STOPPED }
        val unresolvedCount: Int get() = items.count {
            it.state == PreviousState.UNRESOLVED ||
                it.state == PreviousState.JOURNAL_UNAVAILABLE ||
                it.state == PreviousState.DIFFERENT_REVIEWED_PLACE
        }
    }

    class Review internal constructor(
        internal val owner: SnapshotRemoteDefinitionRestore,
        internal val journalIdentity: String,
        internal val original: SnapshotVerifiedTorrentDocumentV1.Prepared,
        internal val frozenHashes: Set<String>,
        internal val plan: StoppedRemoteAddPlan,
        internal val expectedDirectory: String,
        internal val recoveryOnly: Boolean
    ) {
        val hash: String get() = original.infoHashV1
        val name: String get() = original.originalName
        val place: String get() = plan.destination
        val serverDefault: Boolean get() = plan.useServerDefault
    }

    /** Read-only. A missing complete-session row is required before offering an Add review. */
    suspend fun review(
        archive: SnapshotManifestV1.Verified,
        hash: String,
        comparedHashes: List<String>,
        recoveryOnly: Boolean,
        selectedPlace: String? = null
    ): Review? = readOrNull {
        if (!stillCurrent() || !authenticated()) return@readOrNull null
        val observed = remote.listCompleteSessionTransfers().identities()
        if (observed.size != comparedHashes.size ||
            observed != comparedHashes.map { it.lowercase(Locale.ROOT) }.toSet() ||
            (!recoveryOnly && hash in observed)) return@readOrNull null
        if (!recoveryOnly && remote.probe().advertisedMethods?.contains("load.raw_verbose") != true)
            return@readOrNull null
        // A user-picked destination is never trusted from text or old browse state.
        // Establish a fresh root and exact folder on this authenticated mount.
        // Recovery remains read-only even if the folder plugin later disappears:
        // its independently observed stopped job and journal prove the Place.
        val place = selectedPlace ?: remote.defaultDirectory() ?: return@readOrNull null
        if (selectedPlace != null && !recoveryOnly &&
            !SnapshotRemotePlaceProbe.confirm(place, remote::browseDirectories))
            return@readOrNull null
        withContext(Dispatchers.Default) {
            val original = SnapshotVerifiedTorrentDocumentV1.prepare(archive, hash)
            val preview = TorrentMetainfoReader.preview(original.copyVerifiedBytes())
            val magnet = "magnet:?xt=urn:btih:$hash"
            val plan = if (selectedPlace == null)
                StoppedRemoteAddPlan.fromMagnetAtServerDefault(magnet, place)
            else StoppedRemoteAddPlan.fromMagnet(magnet, place)
            val directory = if (preview.files.isNotEmpty()) place.trimEnd('/') + "/" + original.originalName else place
            val archiveKey = SnapshotManifestV1.definitionArchiveFingerprint(archive)
            Review(this@SnapshotRemoteDefinitionRestore,
                SnapshotRestoreOnceV1.itemArchiveFingerprint(archiveKey, hash),
                original, observed, plan, directory, recoveryOnly)
        }.takeIf { stillCurrent() }
    }

    suspend fun restore(review: Review, confirmed: Boolean): Outcome =
        restoreItem(review, confirmed, leaseHeld = false)

    /** Batch-held mutation lease cannot be invoked outside this coordinator. */
    private suspend fun restoreItem(
        review: Review, confirmed: Boolean, leaseHeld: Boolean
    ): Outcome {
        if (!confirmed || review.owner !== this || review.recoveryOnly || !stillCurrent() || !authenticated())
            return Outcome.STALE
        val journal = readOrNull { open(review, existingOnly = false) } ?: return Outcome.BLOCKED
        val entry = journal.entries().single()
        if (entry.phase != SnapshotRestoreJournalV1.Phase.REVIEW &&
            (entry.phase != SnapshotRestoreJournalV1.Phase.READY ||
                entry.placeFingerprint != placeKey(review))) return Outcome.BLOCKED

        var admitted = false
        var enteredLoad = false
        try {
            val loaded = readOrNull {
                remote.loadStoppedTorrentBytes(
                    review.original.copyVerifiedBytes(), review.place,
                    useServerDefault = review.serverDefault,
                    beforeLoad = {
                        // Hidden jobs must never become restore candidates.
                        val destinationVerified = if (review.serverDefault)
                            remote.defaultDirectory() == review.place
                        else SnapshotRemotePlaceProbe.confirm(review.place, remote::browseDirectories)
                        check(remote.listCompleteSessionTransfers().identities() == review.frozenHashes &&
                            destinationVerified && stillCurrent() && authenticated())
                        if (!leaseHeld) {
                            check(admitWrite())
                            admitted = true
                        }
                    },
                    beforeWrite = {
                        // Runs on the transport's IO dispatcher after its authentication
                        // preflight and before the one non-idempotent request body.
                        check(stillCurrent() && authenticated())
                        if (entry.phase == SnapshotRestoreJournalV1.Phase.REVIEW)
                            journal.prepare(review.hash, placeKey(review))
                        journal.beforeAdd(review.hash, placeKey(review))
                        check(stillCurrent() && authenticated())
                        enteredLoad = true
                    }
                )
            }
            if (!enteredLoad) return if (stillCurrent()) Outcome.BLOCKED else Outcome.STALE

            val acknowledged = loaded is RemoteAddOutcome.VerifiedStopped &&
                loaded.hash == review.hash && loaded.destination == review.expectedDirectory
            if (acknowledged) withContext(Dispatchers.IO) {
                readOrNull { journal.acknowledged(review.hash) }
            }
            return observe(review, journal, acknowledged)
        } finally {
            if (admitted) settleWrite()
        }
    }

    /**
     * One approval covers a BOUNDED list of missing definitions on the same
     * authenticated rTorrent account and one current Place. Merely preparing a
     * batch creates no journal and sends no remote mutation.
     */
    class BatchReview internal constructor(
        internal val owner: SnapshotRemoteDefinitionRestore,
        val originals: List<Review>
    ) {
        val count: Int get() = originals.size
        val place: String get() = originals.first().place
        val serverDefault: Boolean get() = originals.first().serverDefault
    }

    data class BatchResult(
        val total: Int,
        val verified: Int,
        val stoppedAtInfoHash: String?,
        val stop: Outcome?
    ) {
        val complete: Boolean get() = total > 0 && verified == total && stop == null
        val remaining: Int get() = total - verified
    }

    /**
     * Prepare every definition, exact account and folder while inventory is
     * still complete. No Add or journal transition occurs until confirmation.
     * Limit both item count and total original metainfo to protect devices.
     */
    suspend fun reviewBatch(
        archive: SnapshotManifestV1.Verified,
        hashes: List<String>,
        comparedHashes: List<String>,
        selectedPlace: String? = null
    ): BatchReview? {
        val normalized = hashes.map { it.lowercase(Locale.ROOT) }
        if (normalized.size !in 2..32 || normalized.toSet().size != normalized.size ||
            normalized.any { !Regex("[0-9a-f]{40}").matches(it) }) return null
        val reviews = ArrayList<Review>(normalized.size)
        var bytes = 0L
        for (hash in normalized.sorted()) {
            val item = review(archive, hash, comparedHashes, recoveryOnly = false,
                selectedPlace = selectedPlace) ?: return null
            bytes += item.original.bytes.size.toLong()
            if (bytes > 24L * 1024 * 1024 ||
                reviews.firstOrNull()?.let {
                    it.frozenHashes != item.frozenHashes || it.place != item.place ||
                        it.serverDefault != item.serverDefault
                } == true) return null
            reviews += item
        }
        if (!stillCurrent() || !authenticated()) return null
        return BatchReview(this, reviews)
    }

    /**
     * Check ALL item journals before the first remote command. A pending or
     * mismatched later journal therefore cannot cause earlier Adds. Recheck
     * complete session identity before each successive single-item restore;
     * only independently VERIFIED results permit moving to the next item.
     * A timeout, ambiguous acknowledgement or cancellation never retries.
     */
    suspend fun restoreBatch(batch: BatchReview, confirmed: Boolean): BatchResult {
        val total = batch.originals.size
        fun fail(outcome: Outcome, hash: String? = null, done: Int = 0) =
            BatchResult(total, done, hash, outcome)
        if (!confirmed || batch.owner !== this || total !in 2..32 ||
            !stillCurrent() || !authenticated()) return fail(Outcome.STALE)
        val frozen = batch.originals.first().frozenHashes
        if (batch.originals.map { it.hash }.toSet().size != total ||
            batch.originals.any {
                it.owner !== this || it.recoveryOnly ||
                    it.frozenHashes != frozen || it.hash in frozen ||
                    it.place != batch.place || it.serverDefault != batch.serverDefault
            }) return fail(Outcome.STALE)
        val rows = readOrNull { remote.listCompleteSessionTransfers() }
            ?: return fail(Outcome.STALE)
        if (rows.size != frozen.size || rows.identities() != frozen)
            return fail(Outcome.STALE)
        // Any journal write here is local REVIEW state only. Never start an
        // rTorrent Add until every old journal is readable and safe.
        for (item in batch.originals) {
            if (!stillCurrent() || !authenticated()) return fail(Outcome.STALE, item.hash)
            val journal = readOrNull { open(item, existingOnly = false) }
                ?: return fail(Outcome.BLOCKED, item.hash)
            val entry = journal.entries().singleOrNull()
            if (entry?.phase != SnapshotRestoreJournalV1.Phase.REVIEW &&
                !(entry?.phase == SnapshotRestoreJournalV1.Phase.READY &&
                    entry.placeFingerprint == placeKey(item)))
                return fail(Outcome.BLOCKED, item.hash)
        }
        // One app-level write lease for the entire group. Releasing it after
        // each item would mark the snapshot stale and permit unrelated writes
        // or UI refresh between batch members. Every item still uses the
        // original exact-account and complete-session readbacks.
        if (!admitWrite()) return fail(Outcome.BLOCKED)
        try {
            val expected = frozen.toMutableSet()
            var verified = 0
            for (item in batch.originals) {
                if (!stillCurrent() || !authenticated())
                    return fail(Outcome.STALE, item.hash, verified)
                val step = Review(this, item.journalIdentity, item.original,
                    expected.toSet(), item.plan, item.expectedDirectory, recoveryOnly = false)
                val outcome = restoreItem(step, confirmed = true, leaseHeld = true)
                if (outcome != Outcome.VERIFIED)
                    return fail(outcome, item.hash, verified)
                verified++
                expected += item.hash
            }
            return BatchResult(total, verified, null, null)
        } finally {
            settleWrite()
        }
    }

    /**
     * Read-only multi-item recovery for *one current authenticated account* and
     * one newly reviewed Place. This is a report, never a batch Add authority.
     * A fresh whole-session inventory brackets the investigation, and every
     * previously submitted item still needs its own independent stopped/Place
     * readback. Only successful confirmations may advance a hash-only local
     * journal to VERIFIED; no remote write, Start or retry is reachable.
     *
     * An absent/corrupt journal is deliberately unknown. A different Place
     * fingerprint never lets a previous attempt masquerade as this review.
     */
    suspend fun inspectPreviousBatch(
        archive: SnapshotManifestV1.Verified,
        comparedHashes: List<String>,
        selectedPlace: String? = null
    ): PreviousReport? = readOrNull {
        val originals = archive.manifest.items.filter {
            it.recovery == SnapshotManifestV1.Recovery.METAINFO
        }.sortedBy { it.infoHashV1 }
        if (originals.size !in 1..512 || !stillCurrent() || !authenticated()) return@readOrNull null
        val initialRows = remote.listCompleteSessionTransfers()
        val initial = initialRows.identities()
        val frozen = comparedHashes.map { it.lowercase(Locale.ROOT) }
        if (initialRows.size != initial.size || frozen.size != initial.size ||
            frozen.toSet() != initial || frozen.size != frozen.toSet().size)
            return@readOrNull null

        // A selected Place need not remain browser-accessible to CHECK an old
        // attempt. Validation is local; unlike restore(), no folder operation
        // authorizes any write here. Default mode retains its v1 journal key.
        val place = selectedPlace ?: remote.defaultDirectory() ?: return@readOrNull null
        val archiveKey = SnapshotManifestV1.definitionArchiveFingerprint(archive)
        val found = ArrayList<PreviousItem>(originals.size)
        val expectedPlaces = HashMap<String, String>(originals.size)
        for (item in originals) {
            if (!stillCurrent() || !authenticated()) return@readOrNull null
            val hash = item.infoHashV1
            val magnet = "magnet:?xt=urn:btih:$hash"
            val plan = if (selectedPlace == null)
                StoppedRemoteAddPlan.fromMagnetAtServerDefault(magnet, place)
            else StoppedRemoteAddPlan.fromMagnet(magnet, place)
            val original = withContext(Dispatchers.Default) {
                SnapshotVerifiedTorrentDocumentV1.prepare(archive, hash)
            }
            val fileLayout = withContext(Dispatchers.Default) {
                TorrentMetainfoReader.preview(original.copyVerifiedBytes())
            }
            val expected = if (fileLayout.files.isNotEmpty())
                place.trimEnd('/') + "/" + original.originalName else place
            expectedPlaces[hash] = expected
            val review = Review(this@SnapshotRemoteDefinitionRestore,
                SnapshotRestoreOnceV1.itemArchiveFingerprint(archiveKey, hash),
                original, initial, plan, expected, recoveryOnly = true)
            val journal = readOrNull { open(review, existingOnly = true) }
            val entry = journal?.entries()?.singleOrNull()
            val state = when {
                entry == null || entry.infoHashV1 != hash ->
                    PreviousState.JOURNAL_UNAVAILABLE
                entry.placeFingerprint != null && entry.placeFingerprint != placeKey(review) ->
                    PreviousState.DIFFERENT_REVIEWED_PLACE
                entry.phase in setOf(
                    SnapshotRestoreJournalV1.Phase.REVIEW,
                    SnapshotRestoreJournalV1.Phase.READY,
                    SnapshotRestoreJournalV1.Phase.REFUSED
                ) -> PreviousState.NO_SUBMISSION_IN_THIS_JOURNAL
                entry.placeFingerprint != placeKey(review) ->
                    PreviousState.DIFFERENT_REVIEWED_PLACE
                else -> when (observe(review, requireNotNull(journal), acknowledgedNow = false)) {
                    Outcome.VERIFIED, Outcome.OBSERVED_STOPPED -> PreviousState.VERIFIED_STOPPED
                    else -> PreviousState.UNRESOLVED
                }
            }
            found += PreviousItem(hash, original.originalName, state)
        }
        if (!stillCurrent() || !authenticated()) return@readOrNull null
        val finalRows = remote.listCompleteSessionTransfers()
        if (finalRows.size != initial.size || finalRows.identities() != initial)
            return@readOrNull null
        // A later change to a verified item revokes the whole summary. No
        // identity-only end check can prove an earlier stopped/Place result.
        if (found.any { item ->
                if (item.state != PreviousState.VERIFIED_STOPPED) false
                else {
                    val row = finalRows.singleOrNull { it.hash.equals(item.hash, true) }
                    row == null || row.started || row.metadataPending == true ||
                        row.name != item.name || row.directory != expectedPlaces[item.hash]
                }
            }) return@readOrNull null
        PreviousReport(found)
    }

    /** Opens an existing journal only. No Add, settings mutation, or retry is reachable. */
    suspend fun reconcile(review: Review): Outcome {
        if (review.owner !== this || !review.recoveryOnly || !stillCurrent() || !authenticated())
            return Outcome.STALE
        val journal = readOrNull { open(review, existingOnly = true) } ?: return Outcome.BLOCKED
        return observe(review, journal, acknowledgedNow = false)
    }

    private suspend fun observe(
        review: Review, journal: SnapshotRestoreJournalV1, acknowledgedNow: Boolean
    ): Outcome {
        val entry = journal.entries().single()
        if (entry.phase !in setOf(SnapshotRestoreJournalV1.Phase.IN_FLIGHT,
                SnapshotRestoreJournalV1.Phase.ACK_UNVERIFIED, SnapshotRestoreJournalV1.Phase.VERIFIED) ||
            entry.placeFingerprint != placeKey(review)) return Outcome.BLOCKED
        if (!stillCurrent() || !authenticated()) return Outcome.UNCERTAIN

        val hashes = readOrNull {
            val rows = remote.listCompleteSessionTransfers()
            val exact = rows.singleOrNull { it.hash.equals(review.hash, true) }
            if (exact == null || exact.started || exact.metadataPending == true ||
                exact.name != review.name || exact.directory != review.expectedDirectory)
                return@readOrNull null
            val independentlyStopped = remote.reconcileStoppedAdd(review.plan)
            if (independentlyStopped != RemoteAddOutcome.VerifiedStopped(
                    review.hash, review.expectedDirectory) || !stillCurrent() || !authenticated())
                return@readOrNull null
            rows.identities()
        } ?: return Outcome.UNCERTAIN

        val saved = readOrNull {
            withContext(Dispatchers.IO) {
                journal.reconcileWithCompleteTarget(hashes, true, listOf(review.hash))
            }
            journal.entries().single().phase == SnapshotRestoreJournalV1.Phase.VERIFIED
        } == true
        return when {
            !saved || !stillCurrent() -> Outcome.UNCERTAIN
            acknowledgedNow || entry.phase == SnapshotRestoreJournalV1.Phase.ACK_UNVERIFIED -> Outcome.VERIFIED
            else -> Outcome.OBSERVED_STOPPED
        }
    }

    private suspend fun open(review: Review, existingOnly: Boolean) = withContext(Dispatchers.IO) {
        val target = fingerprint("chunks-rtorrent-snapshot-account-v1:" + remote.addAccountScope())
        val hashes = listOf(review.hash)
        if (existingOnly) SnapshotRestoreJournalV1.openExisting(store, review.journalIdentity, target, hashes)
        else SnapshotRestoreJournalV1.open(store, review.journalIdentity, target, hashes)
    }

    private fun authenticated(): Boolean =
        remote.readAuthenticationEvidence() == RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED

    private fun placeKey(review: Review): String = fingerprint(
        (if (review.serverDefault) "chunks-rtorrent-snapshot-default-v1:"
         else "chunks-rtorrent-snapshot-selected-v1:") + review.place)

    private fun fingerprint(value: String): String = SnapshotArchiveV1.digest(value.toByteArray(Charsets.UTF_8))

    private fun List<RemoteTransfer>.identities(): Set<String> =
        mapTo(linkedSetOf()) { it.hash.lowercase(Locale.ROOT) }

    private suspend fun <T> readOrNull(read: suspend () -> T): T? = try {
        read()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }
}
