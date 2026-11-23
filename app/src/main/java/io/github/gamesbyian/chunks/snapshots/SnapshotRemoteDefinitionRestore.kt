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
    }

    /** Read-only. A missing complete-session row is required before offering an Add review. */
    suspend fun review(
        archive: SnapshotManifestV1.Verified,
        hash: String,
        comparedHashes: List<String>,
        recoveryOnly: Boolean
    ): Review? = readOrNull {
        if (!stillCurrent() || !authenticated()) return@readOrNull null
        val observed = remote.listCompleteSessionTransfers().identities()
        if (observed != comparedHashes.map { it.lowercase(Locale.ROOT) }.toSet() ||
            (!recoveryOnly && hash in observed)) return@readOrNull null
        if (!recoveryOnly && remote.probe().advertisedMethods?.contains("load.raw_verbose") != true)
            return@readOrNull null
        val place = remote.defaultDirectory() ?: return@readOrNull null
        withContext(Dispatchers.Default) {
            val original = SnapshotVerifiedTorrentDocumentV1.prepare(archive, hash)
            val preview = TorrentMetainfoReader.preview(original.copyVerifiedBytes())
            val plan = StoppedRemoteAddPlan.fromMagnetAtServerDefault("magnet:?xt=urn:btih:$hash", place)
            val directory = if (preview.files.isNotEmpty()) place.trimEnd('/') + "/" + original.originalName else place
            val archiveKey = SnapshotManifestV1.definitionArchiveFingerprint(archive)
            Review(this@SnapshotRemoteDefinitionRestore,
                SnapshotRestoreOnceV1.itemArchiveFingerprint(archiveKey, hash),
                original, observed, plan, directory, recoveryOnly)
        }.takeIf { stillCurrent() }
    }

    suspend fun restore(review: Review, confirmed: Boolean): Outcome {
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
                    review.original.copyVerifiedBytes(), review.place, useServerDefault = true,
                    beforeLoad = {
                        // Hidden jobs must never become restore candidates.
                        check(remote.listCompleteSessionTransfers().identities() == review.frozenHashes &&
                            remote.defaultDirectory() == review.place && stillCurrent() && authenticated())
                        check(admitWrite())
                        admitted = true
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

    private fun placeKey(review: Review): String = fingerprint("chunks-rtorrent-snapshot-default-v1:" + review.place)

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
