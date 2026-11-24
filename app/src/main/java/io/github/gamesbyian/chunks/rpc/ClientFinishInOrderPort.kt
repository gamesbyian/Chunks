package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner
import io.github.gamesbyian.chunks.engine.FinishInOrderRecord
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import kotlinx.coroutines.CancellationException

/**
 * Finish in Order on any [TorrentClient] reached through the one shared
 * [ClientOperations] workflow (Transmission, qBittorrent, Deluge). The same
 * journaled [FinishInOrderRunner] decides each step; this port only observes
 * and applies.
 *
 * - Levels: a client that stores a distinct Low runs the planner's full mode
 *   (active High, lookahead Normal, background Low); one that does not runs
 *   two-band mode (active High, the rest Normal), so Low is never requested.
 * - Completion is the client's own per-file completed bytes. A client that
 *   does not report them makes the transfer not observable, so nothing is written.
 * - Each step is at most ONE [ClientOperations.setFilePriorities] write, after
 *   a fresh read confirms every changed file still has its expected level;
 *   that write is verified by the shared readback and never replayed.
 */
internal class ClientFinishInOrderPort(
    private val operations: ClientOperations,
    storedLevels: Set<ClientFilePriority>,
    private val saveRecord: (String, FinishInOrderRecord) -> Boolean
) : FinishInOrderRunner.Port {
    override val twoBands: Boolean = ClientFilePriority.LOW !in storedLevels

    override suspend fun observe(infoHash: String): List<FinishInOrderPlanner.Observed>? = try {
        operations.files(infoHash).let { files ->
            if (files.any { it.completedBytes == null }) null
            else files.map { file ->
                FinishInOrderPlanner.Observed(asPlannerFile(file), verifiedBytes = requireNotNull(file.completedBytes))
            }
        }
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { null }

    override suspend fun apply(
        infoHash: String, record: FinishInOrderRecord, changes: List<FinishInOrderPlanner.Change>
    ): LocalFileBatchOutcome {
        if (changes.isEmpty()) return LocalFileBatchOutcome.UNCHANGED
        val identity = record.policy.order.associateBy { it.index }
        // Fresh preflight: every changed file is still the reviewed file at its expected level.
        val current = try { operations.files(infoHash) } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return LocalFileBatchOutcome.STALE_REVIEW }
        val byIndex = current.associateBy { it.index }
        if (byIndex.size != current.size || changes.map { it.index }.toSet().size != changes.size)
            return LocalFileBatchOutcome.STALE_REVIEW
        // The reviewed paths came from this same adapter, so a suffix match
        // cannot establish identity. Check the whole reviewed order again.
        if (record.policy.order.any { reviewed ->
            val now = byIndex[reviewed.index]
            now == null || now.sizeBytes != reviewed.sizeBytes || now.path != reviewed.path
        }) return LocalFileBatchOutcome.STALE_REVIEW
        for (change in changes) {
            val reviewed = identity[change.index] ?: return LocalFileBatchOutcome.STALE_REVIEW
            val now = byIndex[change.index] ?: return LocalFileBatchOutcome.STALE_REVIEW
            if (now.sizeBytes != reviewed.sizeBytes || now.path != reviewed.path ||
                toPlanner(now.priority) != change.expectedPriority) return LocalFileBatchOutcome.STALE_REVIEW
        }
        val wanted = changes.associate { it.index to toClient(it.requestedPriority) }
        return when (val outcome = operations.setFilePriorities(infoHash, wanted)) {
            is ClientOperationOutcome.Verified -> LocalFileBatchOutcome.VERIFIED
            // A definite refusal sent nothing.
            is ClientOperationOutcome.Refused -> LocalFileBatchOutcome.STALE_REVIEW
            // Pending or Uncertain: something may have landed; the runner reconciles, never replays.
            is ClientOperationOutcome.Pending, is ClientOperationOutcome.Uncertain -> LocalFileBatchOutcome.UNCERTAIN
        }
    }

    override fun save(infoHash: String, record: FinishInOrderRecord) = saveRecord(infoHash, record)

    internal companion object {
        fun toPlanner(priority: ClientFilePriority): Int = when (priority) {
            ClientFilePriority.SKIP -> 0
            ClientFilePriority.LOW -> FinishInOrderPlanner.LOW
            ClientFilePriority.NORMAL -> FinishInOrderPlanner.NORMAL
            ClientFilePriority.HIGH -> FinishInOrderPlanner.HIGH
        }

        fun toClient(planner: Int): ClientFilePriority {
            require(planner in 0..FinishInOrderPlanner.HIGH) { "Unsupported Finish in Order priority" }
            return when {
                planner == 0 -> ClientFilePriority.SKIP
                planner >= FinishInOrderPlanner.HIGH -> ClientFilePriority.HIGH
                planner <= FinishInOrderPlanner.LOW -> ClientFilePriority.LOW
                else -> ClientFilePriority.NORMAL
            }
        }

        /** The client's files in the planner's terms (index = client file index). */
        fun asPlannerFile(file: ClientFile) = LocalTransferFile(
            file.index, file.path, file.sizeBytes, doneBytes = file.completedBytes ?: 0L,
            wanted = file.priority != ClientFilePriority.SKIP, priority = toPlanner(file.priority))

    }
}
