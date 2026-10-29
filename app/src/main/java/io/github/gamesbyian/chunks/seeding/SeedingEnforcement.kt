package io.github.gamesbyian.chunks.seeding

import java.math.BigDecimal
import java.math.RoundingMode

/** Engine counters for one transfer; all-time values carried across restarts by the engine. */
data class SeedingObservation(
    val uploadedBytes: Long,
    val downloadedBytes: Long,
    /** Time spent seeding as reported by the engine, in seconds. */
    val seedingSeconds: Long,
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

    fun ratio(o: SeedingObservation): BigDecimal? =
        if (o.downloadedBytes <= 0) null
        else BigDecimal(o.uploadedBytes).divide(BigDecimal(o.downloadedBytes), 4, RoundingMode.DOWN)

    fun decide(policy: SeedingPolicy, o: SeedingObservation): Decision {
        if (!o.seeding) return Decision.KeepSeeding
        if (policy.seedsForever) return Decision.KeepSeeding
        if (o.uploadedBytes < 0 || o.downloadedBytes < 0 || o.seedingSeconds < 0)
            return Decision.CannotJudge("The engine reported invalid counters.")
        if (policy.minSeedSeconds != null && o.seedingSeconds < policy.minSeedSeconds) return Decision.KeepSeeding

        val ratioNow = ratio(o)
        val ratioMet: Boolean? = policy.ratioGoal?.let { goal -> ratioNow?.let { it >= goal } }
        val timeMet: Boolean? = policy.maxSeedSeconds?.let { o.seedingSeconds >= it }
        val goalText = policy.ratioGoal?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()
        val conditions = listOfNotNull(
            policy.ratioGoal?.let { "ratio" to ratioMet },
            policy.maxSeedSeconds?.let { "time" to timeMet })
        val reasonFor = { met: List<String> -> met.joinToString(" and ") { if (it == "ratio")
            "ratio ${ratioNow!!.setScale(2, RoundingMode.DOWN).toPlainString()} reached the goal of $goalText"
            else "seeded for the maximum time" } }

        return when (policy.combine) {
            SeedingPolicy.Combine.ANY -> {
                val met = conditions.filter { it.second == true }.map { it.first }
                when {
                    met.isNotEmpty() -> Decision.Stop(reasonFor(met).replaceFirstChar { it.uppercase() } + ".")
                    conditions.all { it.first == "ratio" && it.second == null } -> Decision.CannotJudge(
                        "Nothing was downloaded here, so a ratio goal can't be measured; seeding continues.")
                    else -> Decision.KeepSeeding
                }
            }
            SeedingPolicy.Combine.ALL -> when {
                conditions.any { it.second == null } -> Decision.CannotJudge(
                    "Nothing was downloaded here, so a ratio goal can't be measured; seeding continues.")
                conditions.all { it.second == true } ->
                    Decision.Stop(reasonFor(conditions.map { it.first }).replaceFirstChar { it.uppercase() } + ".")
                else -> Decision.KeepSeeding
            }
        }
    }
}
