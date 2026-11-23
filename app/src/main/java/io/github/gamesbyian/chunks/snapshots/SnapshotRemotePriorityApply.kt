package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.rpc.FilePriorityOutcome
import io.github.gamesbyian.chunks.rpc.RemoteFile
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Explicitly confirmed priority restoration for an ALREADY PRESENT rTorrent
 * transfer. Only file priorities change. Uses the existing hash/index/path/
 * size/expected-old-priority verified writer; no Add, Start, Stop or payload IO.
 *
 * If a reply or postcondition is uncertain, stop at that exact file. Never
 * retry a write from this coordinator; a new user-reviewed read-only comparison
 * is required to understand which priorities actually changed.
 */
internal object SnapshotRemotePriorityApply {
    enum class Outcome { VERIFIED, NO_CHANGES, REFUSED, STALE, NEEDS_READ_ONLY_REVIEW }

    data class Result(val outcome: Outcome, val verifiedWrites: Int, val requestedWrites: Int)

    interface Port {
        /** One full authenticated, unfiltered and independently verified account session. */
        suspend fun completeSession(): List<RemoteTransfer>
        /** Exact-hash file observation with independent d.hash witnesses. */
        suspend fun readFiles(hash: String): List<RemoteFile>
        /** The existing guarded single-file writer. Never retry a failed reply. */
        suspend fun setFilePriority(
            hash: String, index: Int, path: String, sizeBytes: Long,
            expected: Int, desired: Int
        ): FilePriorityOutcome
    }

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    suspend fun apply(
        accountScope: String,
        entry: SnapshotRestorePreviewV1.Entry,
        reviewed: SnapshotRemotePriorityComparison,
        confirmed: Boolean,
        stillCurrent: () -> Boolean,
        port: Port
    ): Result {
        val total = reviewed.changeCount
        fun result(outcome: Outcome, verified: Int = 0) = Result(outcome, verified, total)
        if (!confirmed || EnvironmentSnapshots.serverIdOfAliasScope(accountScope) == null ||
            !entry.infoHashV1.matches(Regex("[a-f0-9]{40}")) ||
            entry.decision != SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT ||
            reviewed.rows.size !in 1..SnapshotFilePriorities.MAX_FILES ||
            reviewed.rows.size != entry.fileMappings.size ||
            reviewed.rows.map { SnapshotFilePriorities.Mapping(it.file, it.saved) } != entry.fileMappings ||
            reviewed.rows.withIndex().any { (position, row) -> row.file.index != position } ||
            reviewed.unsupportedCount > 0 || !stillCurrent())
            return result(Outcome.REFUSED)

        val key = EnvironmentSnapshots.serverIdOfAliasScope(accountScope)!!.value +
            ":" + entry.infoHashV1
        if (!inFlight.add(key)) return result(Outcome.REFUSED)
        var verified = 0
        try {
            // Read a COMPLETE account session around the file observation.
            // Matching a filtered view or a lone hash is insufficient.
            suspend fun observe(): Pair<RemoteTransfer, List<RemoteFile>>? {
                if (!stillCurrent()) return null
                val before = port.completeSession().singleOrNull {
                    it.hash.equals(entry.infoHashV1, ignoreCase = true)
                } ?: return null
                val files = port.readFiles(entry.infoHashV1)
                val after = port.completeSession().singleOrNull {
                    it.hash.equals(entry.infoHashV1, ignoreCase = true)
                } ?: return null
                if (!stillCurrent() || before.name != after.name ||
                    before.directory != after.directory ||
                    before.sizeBytes != after.sizeBytes) return null
                return after to files
            }

            val first = try { observe() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
                ?: return result(Outcome.STALE)
            val current = SnapshotRemotePriorityComparison.plan(entry, first.first, first.second)
                ?: return result(Outcome.STALE)
            if (current.rows != reviewed.rows) return result(Outcome.STALE)
            if (total == 0) return result(Outcome.NO_CHANGES)

            for ((position, row) in reviewed.rows.withIndex()) {
                if (row.saved == row.current) continue
                if (!stillCurrent()) return result(Outcome.NEEDS_READ_ONLY_REVIEW, verified)
                val beforeCode = row.current.rTorrent ?: return result(Outcome.REFUSED, verified)
                val afterCode = row.saved.rTorrent ?: return result(Outcome.REFUSED, verified)
                val exactServerFile = first.second[position]
                val outcome = try {
                    port.setFilePriority(entry.infoHashV1, row.file.index,
                        exactServerFile.path, exactServerFile.sizeBytes, beforeCode, afterCode)
                } catch (cancel: CancellationException) {
                    // A cancelled in-flight RPC may still have mutated the server.
                    // Never automatically resume or repeat this file.
                    throw cancel
                } catch (_: Exception) {
                    return result(Outcome.NEEDS_READ_ONLY_REVIEW, verified)
                }
                if (outcome != FilePriorityOutcome.Verified(
                        entry.infoHashV1, row.file.index, afterCode)) {
                    return result(Outcome.NEEDS_READ_ONLY_REVIEW, verified)
                }
                verified++
            }

            // A successful RPC is not sufficient: independently observe the
            // complete account and exact saved vector one final time.
            val final = try { observe() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
                ?: return result(Outcome.NEEDS_READ_ONLY_REVIEW, verified)
            val compared = SnapshotRemotePriorityComparison.plan(entry, final.first, final.second)
            return if (stillCurrent() && compared != null && compared.changeCount == 0 &&
                compared.rows.size == reviewed.rows.size)
                result(Outcome.VERIFIED, verified)
            else result(Outcome.NEEDS_READ_ONLY_REVIEW, verified)
        } finally {
            inFlight.remove(key)
        }
    }
}
