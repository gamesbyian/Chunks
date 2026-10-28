package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import java.util.Locale

/**
 * Reviewed bulk Start/Stop on the phone engine, one transfer at a time.
 * Anything other than a verified readback (uncertain, missing, failure)
 * halts the batch; nothing after it runs and nothing is retried.
 */
internal object LocalBatchLifecycle {
    const val MAX_ITEMS = 512

    data class Target(val infoHash: String, val stopped: Boolean)

    data class Report(
        val start: Boolean,
        val verified: Int,
        val skipped: Int,
        val haltedOn: String?,
        val notAttempted: Int
    )

    fun run(targets: List<Target>, start: Boolean, command: (String, Boolean) -> LocalCommandOutcome): Report {
        val ids = targets.map { it.infoHash.lowercase(Locale.ROOT) }
        require(targets.isNotEmpty() && targets.size <= MAX_ITEMS) { "Select 1 to $MAX_ITEMS transfers" }
        require(ids.all { Regex("[0-9a-f]{40}([0-9a-f]{24})?").matches(it) }) { "Unsupported identity" }
        require(ids.size == ids.toSet().size) { "Duplicate identities" }
        var verified = 0
        var skipped = 0
        for ((index, target) in targets.withIndex()) {
            if (target.stopped == !start) { skipped++; continue }
            val outcome = try { command(target.infoHash, start) } catch (_: RuntimeException) { null }
            if (outcome == LocalCommandOutcome.VERIFIED) verified++
            else return Report(start, verified, skipped, target.infoHash, targets.size - index - 1)
        }
        return Report(start, verified, skipped, null, 0)
    }

    fun describe(report: Report): String {
        val verb = if (report.start) "started" else "stopped"
        var text = "${report.verified} confirmed $verb on this phone" +
            (if (report.skipped > 0) ", ${report.skipped} already $verb" else "") + "."
        if (report.haltedOn != null) text += " Halted: one transfer's state could not be confirmed, so " +
            "${report.notAttempted} were not attempted. Wait for a fresh list before trying again."
        return text
    }
}
