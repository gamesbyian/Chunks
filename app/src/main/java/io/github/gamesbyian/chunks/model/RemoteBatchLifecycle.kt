package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Reviewed bulk Start/Stop over exact rTorrent hashes, one at a time.
 *
 * Never retries. The first uncertain outcome or failure halts the batch:
 * that command may already have applied, so the remaining items are left
 * untouched and the caller must refresh before anything else. Items already
 * in the requested state are skipped without a command.
 */
internal object RemoteBatchLifecycle {
    const val MAX_ITEMS = 512

    data class Target(val hash: String, val started: Boolean)

    data class Report(
        val requestedStarted: Boolean,
        val verified: List<String>,
        val pending: List<String>,
        val skipped: List<String>,
        /** The item whose outcome is unknown, if the batch halted on one. */
        val haltedOn: String?,
        val haltedUncertain: Boolean,
        val notAttempted: List<String>
    ) {
        val halted get() = haltedOn != null
    }

    suspend fun run(
        targets: List<Target>,
        desiredStarted: Boolean,
        setStarted: suspend (String, Boolean) -> LifecycleOutcome
    ): Report {
        val hashes = targets.map { it.hash.lowercase(Locale.ROOT) }
        require(targets.isNotEmpty() && targets.size <= MAX_ITEMS) { "Select 1 to $MAX_ITEMS transfers" }
        require(hashes.all { Regex("[0-9a-f]{40}").matches(it) }) { "Batch contains an unsupported identity" }
        require(hashes.size == hashes.toSet().size) { "Batch contains duplicate identities" }
        val verified = ArrayList<String>()
        val pending = ArrayList<String>()
        val skipped = ArrayList<String>()
        for ((index, target) in targets.withIndex()) {
            if (target.started == desiredStarted) { skipped += target.hash; continue }
            val outcome = try {
                setStarted(target.hash, desiredStarted)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            when (outcome) {
                is LifecycleOutcome.Verified -> verified += target.hash
                is LifecycleOutcome.Pending -> pending += target.hash
                else -> return Report(desiredStarted, verified, pending, skipped, target.hash,
                    haltedUncertain = outcome is LifecycleOutcome.Uncertain,
                    notAttempted = targets.drop(index + 1).map { it.hash })
            }
        }
        return Report(desiredStarted, verified, pending, skipped, null, false, emptyList())
    }
}
