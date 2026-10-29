package io.github.gamesbyian.chunks.seeding

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.util.Locale

/**
 * Smart Seeding Rules domain (docs/SMART-SEEDING-RULES.md), shared by the
 * phone engine and every rTorrent profile. Pure and offline: no engine,
 * tracker API or network. Enforcement belongs to per-engine adapters that
 * must verify what they apply; nothing here claims a policy is active.
 */

/** An upload cap in exact bytes per second, remembering the unit the user chose. */
data class RateLimit(val bytesPerSecond: Long, val unit: Unit) {
    enum class Unit(val label: String, val bytes: BigDecimal) {
        BYTES("B/s", BigDecimal.ONE),
        KIB("KiB/s", BigDecimal(1024)),
        MIB("MiB/s", BigDecimal(1024 * 1024)),
        KB("kB/s", BigDecimal(1000)),
        MB("MB/s", BigDecimal(1_000_000)),
        KBIT("kbit/s", BigDecimal(125)),
        MBIT("Mbit/s", BigDecimal(125_000))
    }

    /** "24 KiB/s (196.6 kbit/s)": the chosen unit, plus bits so kbps/KiB confusion is visible. */
    fun describe(): String {
        val amount = BigDecimal(bytesPerSecond).divide(unit.bytes, 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val kbit = BigDecimal(bytesPerSecond).divide(BigDecimal(125), 1, RoundingMode.HALF_UP).toPlainString()
        return if (unit == Unit.KBIT) "$amount kbit/s (${Units.bytes(bytesPerSecond)}/s)" else "$amount ${unit.label} ($kbit kbit/s)"
    }

    companion object {
        private val pattern = Regex("""^\s*(\d+(?:\.\d+)?)\s*([A-Za-z/]+)\s*$""")
        private val units = mapOf(
            "b/s" to Unit.BYTES, "bps" to null, // "bps" alone is ambiguous between bits and bytes
            "kib/s" to Unit.KIB, "kibps" to Unit.KIB, "mib/s" to Unit.MIB,
            "kb/s" to Unit.KB, "mb/s" to Unit.MB,
            "kbit/s" to Unit.KBIT, "kbps" to Unit.KBIT, "kbits/s" to Unit.KBIT,
            "mbit/s" to Unit.MBIT, "mbps" to Unit.MBIT, "mbits/s" to Unit.MBIT)

        /**
         * Parses "24 KiB/s", "24 kB/s", "24 kbps" or "1.5 MiB/s". Bare numbers,
         * "k", "bps" and anything that would need rounding to whole bytes are
         * refused with a reason; nothing is silently reinterpreted.
         */
        fun parse(text: String): Result<RateLimit> {
            val match = pattern.matchEntire(text) ?: return Result.failure(IllegalArgumentException(
                "Enter a number and a unit, for example 24 KiB/s or 192 kbit/s"))
            val key = match.groupValues[2].lowercase(Locale.ROOT)
            if (key !in units || units[key] == null) return Result.failure(IllegalArgumentException(
                "\"${match.groupValues[2]}\" is ambiguous. Use KiB/s or kB/s for bytes, kbit/s for bits"))
            val unit = units.getValue(key)!!
            val bytes = BigDecimal(match.groupValues[1]).multiply(unit.bytes)
            if (bytes.signum() <= 0) return Result.failure(IllegalArgumentException("The limit must be above zero"))
            val whole = try { bytes.toBigIntegerExact().toLong() } catch (_: ArithmeticException) {
                return Result.failure(IllegalArgumentException("That limit is not a whole number of bytes per second; choose another value or unit"))
            }
            return Result.success(RateLimit(whole, unit))
        }
    }
}

/** Ratio, duration and upload cap are independent; a cap never means "stop". */
data class SeedingPolicy(
    /** Null: never stop because of ratio. */
    val ratioGoal: BigDecimal? = null,
    /** Seed at least this long before any stop condition counts. */
    val minSeedSeconds: Long? = null,
    /** Stop after this long, if [combine] allows. Null: no time limit. */
    val maxSeedSeconds: Long? = null,
    val uploadLimit: RateLimit? = null,
    val combine: Combine = Combine.ANY
) {
    enum class Combine { ANY, ALL }

    init {
        require(ratioGoal == null || ratioGoal.signum() > 0) { "Ratio goal must be above zero" }
        require(minSeedSeconds == null || minSeedSeconds >= 0)
        require(maxSeedSeconds == null || maxSeedSeconds > 0)
    }

    val seedsForever get() = ratioGoal == null && maxSeedSeconds == null

    /** Contradictions the editor must show instead of quietly fixing. */
    fun warnings(): List<String> = buildList {
        if (minSeedSeconds != null && maxSeedSeconds != null && minSeedSeconds > maxSeedSeconds)
            add("The minimum seeding time is longer than the maximum; the minimum wins.")
        if (combine == Combine.ALL && ratioGoal != null && maxSeedSeconds == null && minSeedSeconds == null)
            add("With a single condition, \"all conditions\" and \"any condition\" behave the same.")
    }

    fun describe(): String {
        val stops = buildList {
            ratioGoal?.let { add("ratio ${it.setScale(2, RoundingMode.HALF_UP).toPlainString()}") }
            maxSeedSeconds?.let { add(duration(it)) }
        }
        val stop = when {
            stops.isEmpty() -> "Seeds without a ratio or time limit"
            stops.size == 1 -> "Stops seeding at ${stops[0]}"
            combine == Combine.ANY -> "Stops seeding at ${stops.joinToString(" or ")}, whichever comes first"
            else -> "Stops seeding once both ${stops.joinToString(" and ")} are reached"
        }
        val minimum = minSeedSeconds?.let { " after at least ${duration(it)}" }.orEmpty()
        val cap = uploadLimit?.let { "; uploads at most ${it.describe()} per transfer" } ?: "; no upload limit"
        return "$stop$minimum$cap. Stopping never removes the transfer or deletes files."
    }

    private fun duration(seconds: Long): String = when {
        seconds % 86_400 == 0L -> "${seconds / 86_400} day" + if (seconds / 86_400 == 1L) "" else "s"
        seconds % 3_600 == 0L -> "${seconds / 3_600} hour" + if (seconds / 3_600 == 1L) "" else "s"
        else -> "${seconds / 60} min"
    }
}

/** Exact tracker host matching on whole DNS labels; never substrings, names or titles. */
object TrackerHosts {
    /** Host of a tracker URL, lower-cased; null for credentials, opaque or unparsable input. Never keeps paths or passkeys. */
    fun host(url: String): String? = try {
        val uri = URI(url.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in setOf("http", "https", "udp") || uri.rawUserInfo != null || uri.host.isNullOrBlank()) null
        else uri.host.lowercase(Locale.ROOT).trimEnd('.')
    } catch (_: Exception) { null }

    fun matches(host: String, domain: String): Boolean {
        val d = domain.lowercase(Locale.ROOT).trim().trimEnd('.')
        return d.isNotEmpty() && (host == d || host.endsWith(".$d"))
    }
}

data class SeedingRule(
    /** Stable identity; never derived from the editable name. */
    val id: String,
    val name: String,
    /** True once the user edited the name; condition changes then never overwrite it. */
    val nameIsCustom: Boolean,
    /** Tracker domains, matched on whole labels. Empty: matches nothing (use the default instead). */
    val trackerDomains: Set<String>,
    /** Null: any; true/false: only private/public torrents. */
    val privateOnly: Boolean? = null,
    val policy: SeedingPolicy,
    /** Immutable environment IDs this rule applies to. */
    val environments: Set<String>,
    /** Also apply to environments added after [knownEnvironments] was recorded. Off unless chosen. */
    val includeFutureEnvironments: Boolean = false,
    val knownEnvironments: Set<String> = environments
) {
    fun appliesTo(environment: String) = environment in environments ||
        (includeFutureEnvironments && environment !in knownEnvironments)

    /** Recompute the suggested name unless the user has made it their own. */
    fun withConditions(domains: Set<String>, policy: SeedingPolicy): SeedingRule =
        copy(trackerDomains = domains, policy = policy,
            name = if (nameIsCustom) name else suggestedName(domains, policy))

    companion object {
        fun suggestedName(domains: Set<String>, policy: SeedingPolicy): String {
            val who = when {
                domains.isEmpty() -> "Matching trackers"
                domains.size == 1 -> domains.first().substringBeforeLast('.').substringAfterLast('.')
                    .replaceFirstChar { it.titlecase(Locale.ROOT) }
                else -> "${domains.size} trackers"
            }
            val what = buildList {
                if (policy.seedsForever) add("Seed forever")
                policy.ratioGoal?.let { add("Ratio ${it.setScale(2, RoundingMode.HALF_UP).toPlainString()}") }
                policy.maxSeedSeconds?.let { add("${it / 86_400}d max") }
                policy.uploadLimit?.let { add(BigDecimal(it.bytesPerSecond).divide(it.unit.bytes, 3, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString() + " " + it.unit.label) }
            }
            return (listOf(who) + what).joinToString(" · ")
        }
    }
}

/** What Chunks knows about one transfer when choosing its policy. Tracker URLs stay in memory only. */
data class TransferFacts(
    val environment: String,
    /** Null: metadata not known yet (for example a magnet still fetching). */
    val trackerUrls: List<String>?,
    val isPrivate: Boolean?,
    /** A manual per-transfer choice; it survives rematching. */
    val overrideRuleId: String? = null
)

data class SeedingResolution(
    val policy: SeedingPolicy,
    val source: Source,
    val ruleId: String?,
    /** Plain reasons for "Why this rule?", without URLs or passkeys. */
    val reasons: List<String>,
    /** Other rules that also matched at the same precedence, flagged for review. */
    val conflicts: List<String> = emptyList()
) {
    enum class Source { OVERRIDE, RULE, DEFAULT, PENDING }
}

object SeedingRuleEngine {
    /**
     * Manual override > first matching rule in saved order > environment default.
     * Missing tracker metadata is PENDING with the default as a disclosed fallback.
     */
    fun resolve(facts: TransferFacts, rules: List<SeedingRule>, default: SeedingPolicy): SeedingResolution {
        val usable = rules.filter { it.appliesTo(facts.environment) }
        facts.overrideRuleId?.let { id ->
            usable.firstOrNull { it.id == id }?.let {
                return SeedingResolution(it.policy, SeedingResolution.Source.OVERRIDE, it.id,
                    listOf("Chosen for this transfer: ${it.name}"))
            }
        }
        val urls = facts.trackerUrls ?: return SeedingResolution(default, SeedingResolution.Source.PENDING, null,
            listOf("Tracker details are not known yet; the default applies until they are."))
        val hosts = urls.mapNotNull(TrackerHosts::host).distinct()
        val matching = usable.mapNotNull { rule ->
            if (rule.privateOnly != null && facts.isPrivate != null && rule.privateOnly != facts.isPrivate) return@mapNotNull null
            val hit = hosts.firstOrNull { host -> rule.trackerDomains.any { TrackerHosts.matches(host, it) } }
                ?: return@mapNotNull null
            rule to hit
        }
        if (matching.isEmpty()) return SeedingResolution(default, SeedingResolution.Source.DEFAULT, null,
            listOf(if (hosts.isEmpty()) "No tracker, so no rule matched; the default applies."
                else "No rule matched ${hosts.size} tracker host(s); the default applies."))
        val (winner, host) = matching.first()
        return SeedingResolution(winner.policy, SeedingResolution.Source.RULE, winner.id,
            listOf("Tracker host $host matches ${winner.name}"),
            matching.drop(1).filter { it.first.policy != winner.policy }.map { it.first.name })
    }
}

/** Tiny local formatter so this package needs nothing from the UI layer. */
private object Units {
    fun bytes(value: Long): String = when {
        value >= 1024 * 1024 -> BigDecimal(value).divide(BigDecimal(1024 * 1024), 1, RoundingMode.HALF_UP).toPlainString() + " MiB"
        value >= 1024 -> BigDecimal(value).divide(BigDecimal(1024), 1, RoundingMode.HALF_UP).toPlainString() + " KiB"
        else -> "$value B"
    }
}
