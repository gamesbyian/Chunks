package io.github.gamesbyian.chunks.seeding

import java.math.BigDecimal
import java.math.RoundingMode

/** Engine counters for one transfer; all-time values carried across restarts by the engine. */
data class SeedingObservation(
    val uploadedBytes: Long,
    val downloadedBytes: Long,
    /**
     * Time spent seeding as reported by the engine, in seconds. Null when the
     * engine keeps no such counter (rTorrent: d.timestamp.finished is the
     * completion instant, not accumulated seed time). A time condition can
     * then never be judged, so it never stops a transfer.
     */
    val seedingSeconds: Long?,
    val seeding: Boolean
)

/**
 * Decides whether a policy says "stop sharing now" for one transfer. Stopping
 * means pausing the job only; this never removes a transfer or deletes data.
 * A ratio needs a real denominator: with nothing downloaded here (a seeded
 * copy, an imported complete file) the ratio is not meaningful and never
 * triggers a stop by itself.
 */
object SeedingEnforcement {
    sealed class Decision {
        object KeepSeeding : Decision()
        data class Stop(val reason: String) : Decision()
        /** The policy cannot be judged from these counters; nothing should change. */
        data class CannotJudge(val reason: String) : Decision()
    }

    private fun unjudged(conditions: List<Pair<String, Boolean?>>): String {
        val unknown = conditions.filter { it.second == null }.map { it.first }
        return when {
            unknown == listOf("ratio") ->
                "Nothing was downloaded here, so a ratio goal can't be measured; seeding continues."
            unknown == listOf("time") ->
                "This client doesn't report seeding time, so the time limit can't be checked; seeding continues."
            else -> "Neither the ratio goal nor the seeding time can be measured here; seeding continues."
        }
    }

    fun ratio(o: SeedingObservation): BigDecimal? =
        if (o.downloadedBytes <= 0) null
        else BigDecimal(o.uploadedBytes).divide(BigDecimal(o.downloadedBytes), 4, RoundingMode.DOWN)

    fun decide(policy: SeedingPolicy, o: SeedingObservation): Decision {
        if (!o.seeding) return Decision.KeepSeeding
        if (policy.seedsForever) return Decision.KeepSeeding
        if (o.uploadedBytes < 0 || o.downloadedBytes < 0 || (o.seedingSeconds ?: 0) < 0)
            return Decision.CannotJudge("The engine reported invalid counters.")
        if (policy.minSeedSeconds != null) {
            val seconds = o.seedingSeconds ?: return Decision.CannotJudge(
                "This client doesn't report seeding time, so the minimum seeding time can't be checked; seeding continues.")
            if (seconds < policy.minSeedSeconds) return Decision.KeepSeeding
        }

        // Comparing a ratio rounded down to four decimals can miss a
        // legitimately reached goal (for example 1,000,001 / 1,000,000
        // exactly reaches 1.000001). Compare exact integer counters against
        // the decimal goal; round only the explanation shown to the owner.
        val displayScale = maxOf(4, policy.ratioGoal?.scale() ?: 4)
        val ratioNow = if (o.downloadedBytes > 0)
            BigDecimal(o.uploadedBytes).divide(BigDecimal(o.downloadedBytes),
                displayScale, RoundingMode.DOWN) else null
        val ratioMet: Boolean? = policy.ratioGoal?.let { goal ->
            if (o.downloadedBytes > 0)
                BigDecimal(o.uploadedBytes) >= goal.multiply(BigDecimal(o.downloadedBytes))
            else null
        }
        val timeMet: Boolean? = policy.maxSeedSeconds?.let { max -> o.seedingSeconds?.let { it >= max } }
        val goalText = policy.ratioGoal?.let {
            it.setScale(maxOf(2, it.scale()), RoundingMode.HALF_UP).toPlainString()
        }
        val conditions = listOfNotNull(
            policy.ratioGoal?.let { "ratio" to ratioMet },
            policy.maxSeedSeconds?.let { "time" to timeMet })
        val reasonFor = { met: List<String> -> met.joinToString(" and ") { if (it == "ratio")
            "ratio ${ratioNow!!.setScale(maxOf(2, policy.ratioGoal?.scale() ?: 2), RoundingMode.DOWN).toPlainString()} reached the goal of $goalText"
            else "seeded for the maximum time" } }

        return when (policy.combine) {
            SeedingPolicy.Combine.ANY -> {
                val met = conditions.filter { it.second == true }.map { it.first }
                when {
                    met.isNotEmpty() -> Decision.Stop(reasonFor(met).replaceFirstChar { it.uppercase() } + ".")
                    conditions.all { it.first == "ratio" && it.second == null } -> Decision.CannotJudge(
                        "Nothing was downloaded here, so a ratio goal can't be measured; seeding continues.")
                    conditions.isNotEmpty() && conditions.all { it.second == null } -> Decision.CannotJudge(
                        unjudged(conditions))
                    else -> Decision.KeepSeeding
                }
            }
            SeedingPolicy.Combine.ALL -> when {
                conditions.any { it.second == null } -> Decision.CannotJudge(unjudged(conditions))
                conditions.all { it.second == true } ->
                    Decision.Stop(reasonFor(conditions.map { it.first }).replaceFirstChar { it.uppercase() } + ".")
                else -> Decision.KeepSeeding
            }
        }
    }
}
