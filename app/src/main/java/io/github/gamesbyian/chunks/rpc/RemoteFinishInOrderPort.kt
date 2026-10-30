package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner
import io.github.gamesbyian.chunks.engine.FinishInOrderRecord
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import io.github.gamesbyian.chunks.engine.LocalTransferFile

/**
 * Finish in Order on rTorrent. rTorrent has only off(0)/normal(1)/high(2), so
 * the planner runs in two-band mode: the active (and fallback) file is High,
 * every other managed file stays Normal. Completion is rTorrent's own
 * hash-verified chunk count. It advances only while Chunks is open and
 * connected; the server itself does not keep scheduling (stated in the UI).
 *
 * Each change is one guarded [RTorrentRemote.setFilePriority] (fresh
 * identity/expected-priority preflight, then readback). If an earlier change
 * already landed, any later failure is UNCERTAIN, never replayed.
 */
internal class RemoteFinishInOrderPort(
    private val remote: RTorrentRemote,
    private val saveRecord: (String, FinishInOrderRecord) -> Boolean
) : FinishInOrderRunner.Port {
    override val twoBands: Boolean get() = true

    override suspend fun observe(infoHash: String): List<FinishInOrderPlanner.Observed>? = try {
        remote.readFileCompletion(infoHash).mapIndexed { index, file ->
            FinishInOrderPlanner.Observed(
                LocalTransferFile(index, file.path, file.sizeBytes,
                    doneBytes = if (file.verifiedComplete) file.sizeBytes else 0L,
                    wanted = file.priority != 0, priority = toPlanner(file.priority)),
                verifiedBytes = if (file.verifiedComplete) file.sizeBytes else 0L)
        }
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (_: Exception) { null }

    override suspend fun apply(
        infoHash: String, record: FinishInOrderRecord, changes: List<FinishInOrderPlanner.Change>
    ): LocalFileBatchOutcome {
        val identity = record.policy.order.associateBy { it.index }
        var landed = 0
        for (change in changes) {
            val file = identity[change.index] ?: return LocalFileBatchOutcome.STALE_REVIEW
            val outcome = try {
                remote.setFilePriority(infoHash, change.index, file.path, file.sizeBytes,
                    toRemote(change.expectedPriority), toRemote(change.requestedPriority))
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: IllegalArgumentException) {
                // Refused by the fresh preflight: nothing written for this file.
                return if (landed == 0) LocalFileBatchOutcome.STALE_REVIEW else LocalFileBatchOutcome.UNCERTAIN
            } catch (_: Exception) { return LocalFileBatchOutcome.UNCERTAIN }
            when (outcome) {
                is FilePriorityOutcome.Verified, is FilePriorityOutcome.Unchanged -> landed++
                else -> return LocalFileBatchOutcome.UNCERTAIN
            }
        }
        return LocalFileBatchOutcome.VERIFIED
    }

    override fun save(infoHash: String, record: FinishInOrderRecord) = saveRecord(infoHash, record)

    companion object {
        fun toPlanner(remote: Int): Int {
            // An unexpected value is not a Normal priority. Refuse the
            // observation so Finish in Order cannot write against a false
            // priority baseline supplied by a gateway or malformed response.
            require(remote in 0..2) { "Unsupported rTorrent file priority" }
            return when (remote) {
                0 -> 0
                2 -> FinishInOrderPlanner.HIGH
                else -> FinishInOrderPlanner.NORMAL
            }
        }

        fun toRemote(planner: Int): Int {
            require(planner in 0..FinishInOrderPlanner.HIGH) {
                "Unsupported Finish in Order priority"
            }
            return when {
                planner == 0 -> 0
                planner >= FinishInOrderPlanner.HIGH -> 2
                else -> 1
            }
        }
    }
}
