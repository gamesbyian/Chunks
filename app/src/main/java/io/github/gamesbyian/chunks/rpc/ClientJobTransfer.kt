package io.github.gamesbyian.chunks.rpc

import kotlinx.coroutines.CancellationException

/**
 * Job-only Copy / Move of one exact torrent JOB between two remote clients
 * (CROSS-ENGINE-TRANSFER-JOBS): payload bytes are never copied, moved or
 * deleted. The target downloads (or rechecks) independently.
 *
 * Copy = exact source read → faithful re-creation source (exported metainfo,
 * else the job's own magnet) → ONE verified target Add (shared workflow).
 * Move = a verified Copy followed by a SEPARATE, caller-confirmed source
 * retirement that is session-only (files kept) and refused unless the target
 * still holds the exact job at that moment.
 */
internal class ClientJobTransfer(
    private val source: RemoteClientOperations,
    private val sourceClient: RemoteTorrentClient,
    private val target: RemoteClientOperations,
    private val targetClient: RemoteTorrentClient
) {
    init {
        require(source !== target && sourceClient !== targetClient) { "Choose two different clients" }
    }

    sealed interface CopyResult {
        val hash: String
        data class Copied(override val hash: String, val viaMetainfo: Boolean) : CopyResult
        data class NotCopied(override val hash: String, val outcome: ClientOperationOutcome) : CopyResult
        data class Refused(override val hash: String, val reason: String) : CopyResult
    }

    suspend fun copy(hash: String, targetDirectory: String?, startOnTarget: Boolean): CopyResult {
        val exact = ClientHashes.requireHash(hash)
        val caps = try { source.session().capabilities } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return CopyResult.Refused(exact, "Source client unavailable") }
        if (ClientCapability.EXPORT_JOB !in caps) return CopyResult.Refused(exact, "Source cannot export this job")
        val present = try { sourceClient.torrent(exact) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return CopyResult.Refused(exact, "Could not read the source first") }
            ?: return CopyResult.Refused(exact, "Transfer is not in the source client")
        val recreate = try { sourceClient.transferSource(present.hash) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            ?: return CopyResult.Refused(exact, "This job cannot be re-created faithfully (private or no metainfo)")
        if (recreate.infoHash != exact) return CopyResult.Refused(exact, "Source exported a different identity")
        val outcome = target.add(ClientAddRequest(recreate, targetDirectory, startOnTarget))
        return if (outcome is ClientOperationOutcome.Verified)
            CopyResult.Copied(exact, recreate is ClientAddSource.TorrentFile)
        else CopyResult.NotCopied(exact, outcome)
    }

    /** Second, separately confirmed step of Move. Never deletes payload data. */
    suspend fun retireSource(hash: String): ClientOperationOutcome {
        val exact = ClientHashes.requireHash(hash)
        val stillOnTarget = try { targetClient.torrent(exact) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        if (stillOnTarget == null)
            return ClientOperationOutcome.Refused(exact, "The target no longer shows this job; source kept")
        return source.remove(exact, deleteData = false)
    }
}
