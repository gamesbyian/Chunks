package io.github.gamesbyian.chunks.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Job-only Copy / Move of one exact torrent JOB between two remote clients
 * (CROSS-ENGINE-TRANSFER-JOBS): payload bytes are never copied, moved or
 * deleted. The target downloads (or rechecks) independently.
 *
 * Copy = exact source read → faithful re-creation source (exported metainfo,
 * else the job's own magnet) → ONE verified target Add (shared workflow).
 * Move = a verified Copy followed by a SEPARATE, caller-confirmed source
 * retirement that is session-only (files kept). Retirement requires the
 * original one-use successful Copy receipt for this exact client pair.
 *
 * IDs must be authenticated canonical *client* identities, not display names,
 * currently selected UI filters or mutable connection slots.
 */
internal class ClientJobTransfer(
    private val source: ClientOperations,
    private val sourceClient: TorrentClient,
    private val target: ClientOperations,
    private val targetClient: TorrentClient,
    sourceClientId: String,
    targetClientId: String
) {
    init {
        require(source.client === sourceClient && target.client === targetClient) {
            "Operation owners must match their exact client adapters"
        }
        require(source !== target && sourceClient !== targetClient) { "Choose two different clients" }
        require(sourceClientId.isNotBlank() && targetClientId.isNotBlank() &&
            sourceClientId != targetClientId) { "Choose two distinct authenticated clients" }
    }

    sealed interface CopyResult {
        val hash: String
        data class Copied(override val hash: String, val viaMetainfo: Boolean) : CopyResult
        data class NotCopied(override val hash: String, val outcome: ClientOperationOutcome) : CopyResult
        data class Refused(override val hash: String, val reason: String) : CopyResult
    }

    private val attemptLock = Mutex()
    /** The exact *object* returned by the last verified Copy; not a forgeable data-class value. */
    private var pendingRetirement: CopyResult.Copied? = null

    suspend fun copy(hash: String, targetDirectory: String?, startOnTarget: Boolean): CopyResult =
        attemptLock.withLock {
            // A newer attempt supersedes any earlier authority, even if refused or uncertain.
            pendingRetirement = null
            val exact = ClientHashes.requireHash(hash)
            val caps = try { source.session().capabilities }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { return@withLock CopyResult.Refused(exact, "Source client unavailable") }
            if (ClientCapability.EXPORT_JOB !in caps)
                return@withLock CopyResult.Refused(exact, "Source cannot export this job")
            val present = try { sourceClient.torrent(exact) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { return@withLock CopyResult.Refused(exact, "Could not read the source first") }
                ?: return@withLock CopyResult.Refused(exact, "Transfer is not in the source client")
            val recreate = try { sourceClient.transferSource(present.hash) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                ?: return@withLock CopyResult.Refused(exact,
                    "This job cannot be re-created faithfully (private or no metainfo)")
            if (recreate.infoHash != exact)
                return@withLock CopyResult.Refused(exact, "Source exported a different identity")
            val outcome = target.add(ClientAddRequest(recreate, targetDirectory, startOnTarget))
            if (outcome !is ClientOperationOutcome.Verified)
                return@withLock CopyResult.NotCopied(exact, outcome)

            CopyResult.Copied(exact, recreate is ClientAddSource.TorrentFile).also {
                pendingRetirement = it
            }
        }

    /**
     * Second, separately user-confirmed step of Move. Must present the *same*
     * successful Copy result object returned by this transfer instance.
     * A duplicate target, stale receipt, forged equal value or failed Copy
     * never authorizes source removal. Consume before any source write so
     * Pending/Uncertain outcomes cannot be replayed blindly.
     */
    suspend fun retireSource(
        hash: String,
        confirmedCopy: CopyResult.Copied,
        stillAuthorized: () -> Boolean = { true }
    ): ClientOperationOutcome = attemptLock.withLock {
        val exact = ClientHashes.requireHash(hash)
        if (pendingRetirement !== confirmedCopy || confirmedCopy.hash != exact)
            return@withLock ClientOperationOutcome.Refused(exact,
                "No verified Copy for this exact client and transfer; source kept")
        pendingRetirement = null
        val connectionChanged = "The selected connection changed; source job kept"
        if (!stillAuthorized()) return@withLock ClientOperationOutcome.Refused(exact, connectionChanged)
        val stillOnTarget = try { targetClient.torrent(exact) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        if (stillOnTarget?.hash?.equals(exact, ignoreCase = true) != true)
            return@withLock ClientOperationOutcome.Refused(exact,
                "The target no longer shows this job; source kept")
        source.remove(exact, deleteData = false,
            beforeRemove = { connectionChanged.takeUnless { stillAuthorized() } })
    }
}
