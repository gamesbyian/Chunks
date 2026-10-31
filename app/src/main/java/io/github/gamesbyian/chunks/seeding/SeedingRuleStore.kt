package io.github.gamesbyian.chunks.seeding

import android.content.Context
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, Chunks-only storage for Smart Seeding Rules and per-environment
 * defaults. Rules hold tracker *domains* and policy numbers only: no tracker
 * URLs, passkeys or infohashes ever enter this record. A record that cannot be
 * read is reported as unreadable instead of being silently replaced, so a later
 * save never wipes rules the user still expects.
 */
class SeedingRuleStore(context: Context) {
    /**
     * A v1 remote binding used an account ID derived from a trimmed username.
     * It can accidentally match a DIFFERENT exact username after #810.
     * Preserve the original opaque IDs, but never make them active by guessing.
     */
    data class QuarantinedRemoteRule(
        val ruleId: String,
        val environments: Set<String>,
        val knownEnvironments: Set<String>,
        val includeFutureEnvironments: Boolean
    )

    data class Snapshot(
        val rules: List<SeedingRule>,
        /** Environment ID → default policy for transfers no rule matches. */
        val defaults: Map<String, SeedingPolicy>,
        /** True when a stored record existed but could not be read. */
        val unreadable: Boolean = false,
        /** Read-only v1 server bindings; reassign only after user review. */
        val quarantinedRemoteRules: List<QuarantinedRemoteRule> = emptyList(),
        val quarantinedRemoteDefaults: Map<String, SeedingPolicy> = emptyMap()
    )

    private val prefs = context.applicationContext.getSharedPreferences("chunks_seeding_rules", Context.MODE_PRIVATE)

    fun load(): Snapshot {
        // An occupied preference of the wrong type is an unreadable record,
        // NOT an empty ruleset. Turning it into an empty, writable snapshot
        // would allow a routine Save to destroy the user's original policy.
        val raw = try { prefs.getString(KEY, null) }
            catch (_: RuntimeException) { return Snapshot(emptyList(), emptyMap(), unreadable = true) }
            ?: return Snapshot(emptyList(), emptyMap(), unreadable = prefs.contains(KEY))
        return try { decode(raw) } catch (_: Exception) { Snapshot(emptyList(), emptyMap(), unreadable = true) }
    }

    /** Refuses to overwrite an unreadable record; the caller must ask the user first. */
    fun save(snapshot: Snapshot, replaceUnreadable: Boolean = false): Boolean {
        if (!replaceUnreadable && load().unreadable) return false
        return try { prefs.edit().putString(KEY, encode(snapshot)).commit() } catch (_: RuntimeException) { false }
    }

    companion object {
        private const val KEY = "rules"
        const val VERSION = 1
        /** Explicit identity scheme marker added after remote principal v1 trimming was retired. */
        private const val EXACT_PRINCIPAL_SCHEME = "remote-principal-exact-v2"

        fun encode(snapshot: Snapshot): String = JSONObject()
            .put("version", VERSION)
            .put("identityScheme", EXACT_PRINCIPAL_SCHEME)
            .put("rules", JSONArray().apply { snapshot.rules.forEach { put(rule(it)) } })
            .put("defaults", JSONObject().apply { snapshot.defaults.forEach { (env, p) -> put(env, policy(p)) } })
            .put("quarantinedRemoteRules", JSONArray().apply {
                snapshot.quarantinedRemoteRules.forEach { old -> put(JSONObject()
                    .put("ruleId", old.ruleId)
                    .put("environments", JSONArray(old.environments.sorted()))
                    .put("knownEnvironments", JSONArray(old.knownEnvironments.sorted()))
                    .put("includeFuture", old.includeFutureEnvironments)) }
            })
            .put("quarantinedRemoteDefaults", JSONObject().apply {
                snapshot.quarantinedRemoteDefaults.forEach { (env, p) -> put(env, policy(p)) }
            })
            .toString()

        fun decode(raw: String): Snapshot {
            val root = JSONObject(raw)
            val version = root.getInt("version")
            require(version == VERSION) { "Unsupported seeding rules version $version" }
            val rules = root.getJSONArray("rules").let { a -> (0 until a.length()).map { rule(a.getJSONObject(it)) } }
            require(rules.map { it.id }.toSet().size == rules.size) { "Duplicate rule identity" }
            val defaults = root.getJSONObject("defaults").let { o -> o.keys().asSequence().associateWith { policy(o.getJSONObject(it)) } }
            val scheme = root.optString("identityScheme", "")
            require(scheme.isEmpty() || scheme == EXACT_PRINCIPAL_SCHEME) {
                "Unknown seeding environment identity scheme"
            }
            if (scheme.isEmpty()) {
                // Every legacy v1 server ID is untrustworthy, including IDs that
                // now happen to match a saved exact principal. An old account
                // " alice " and today's "alice" share the *old* trimmed hash.
                // Future-environment matching is also revoked: otherwise it
                // would silently reach a newly saved server.
                val archival = rules.filter { old ->
                    old.environments.any { it != "phone" } ||
                        old.knownEnvironments.any { it != "phone" } ||
                        old.includeFutureEnvironments
                }.map { old -> QuarantinedRemoteRule(
                    old.id, old.environments, old.knownEnvironments,
                    old.includeFutureEnvironments)
                }
                val safe = rules.map { old -> old.copy(
                    environments = old.environments.filterTo(mutableSetOf()) { it == "phone" },
                    knownEnvironments = old.knownEnvironments.filterTo(mutableSetOf()) { it == "phone" },
                    includeFutureEnvironments = false)
                }
                return Snapshot(safe, defaults.filterKeys { it == "phone" },
                    quarantinedRemoteRules = archival,
                    quarantinedRemoteDefaults = defaults.filterKeys { it != "phone" })
            }
            val oldRules = root.getJSONArray("quarantinedRemoteRules").let { a ->
                (0 until a.length()).map { i ->
                    val obj = a.getJSONObject(i)
                    QuarantinedRemoteRule(obj.getString("ruleId"),
                        strings(obj.getJSONArray("environments")).toSet(),
                        strings(obj.getJSONArray("knownEnvironments")).toSet(),
                        obj.getBoolean("includeFuture"))
                }
            }
            require(oldRules.map { it.ruleId }.toSet().size == oldRules.size)
            val oldDefaults = root.getJSONObject("quarantinedRemoteDefaults").let { o ->
                o.keys().asSequence().associateWith { policy(o.getJSONObject(it)) }
            }
            // Archived identities can intentionally outlive a deleted rule,
            // and a user may explicitly rebind today's account to an ID that
            // coincides with an older trimmed principal. The archive is never
            // active, so neither case must make the whole vault unreadable.
            require(oldRules.all { it.ruleId.length in 1..64 } &&
                oldRules.size <= 2048 && oldDefaults.size <= 2048) {
                "Invalid archived server binding"
            }
            return Snapshot(rules, defaults, quarantinedRemoteRules = oldRules,
                quarantinedRemoteDefaults = oldDefaults)
        }

        private fun rule(r: SeedingRule) = JSONObject()
            .put("id", r.id).put("name", r.name).put("nameIsCustom", r.nameIsCustom)
            .put("domains", JSONArray(r.trackerDomains.sorted()))
            .put("privateOnly", r.privateOnly ?: JSONObject.NULL)
            .put("policy", policy(r.policy))
            .put("environments", JSONArray(r.environments.sorted()))
            .put("includeFuture", r.includeFutureEnvironments)
            .put("knownEnvironments", JSONArray(r.knownEnvironments.sorted()))

        private fun rule(o: JSONObject): SeedingRule {
            val domains = strings(o.getJSONArray("domains"))
            // Domains only: anything that looks like a URL or carries a query never loads.
            require(domains.all { it.isNotBlank() && it.none { c -> c == '/' || c == '?' || c == ':' || c == '@' } }) {
                "Invalid tracker domain"
            }
            return SeedingRule(
                id = o.getString("id").also { require(it.isNotBlank() && it.length <= 64) },
                name = o.getString("name").also { require(it.length <= 120) },
                nameIsCustom = o.getBoolean("nameIsCustom"),
                trackerDomains = domains.toSet(),
                privateOnly = if (o.isNull("privateOnly")) null else o.getBoolean("privateOnly"),
                policy = policy(o.getJSONObject("policy")),
                environments = strings(o.getJSONArray("environments")).toSet(),
                includeFutureEnvironments = o.getBoolean("includeFuture"),
                knownEnvironments = strings(o.getJSONArray("knownEnvironments")).toSet())
        }

        internal fun policy(p: SeedingPolicy) = JSONObject()
            .put("ratio", p.ratioGoal?.toPlainString() ?: JSONObject.NULL)
            .put("minSeconds", p.minSeedSeconds ?: JSONObject.NULL)
            .put("maxSeconds", p.maxSeedSeconds ?: JSONObject.NULL)
            .put("uploadBytes", p.uploadLimit?.bytesPerSecond ?: JSONObject.NULL)
            .put("uploadUnit", p.uploadLimit?.unit?.name ?: JSONObject.NULL)
            .put("combine", p.combine.name)

        internal fun policy(o: JSONObject) = SeedingPolicy(
            ratioGoal = if (o.isNull("ratio")) null else BigDecimal(o.getString("ratio")),
            minSeedSeconds = if (o.isNull("minSeconds")) null else o.getLong("minSeconds"),
            maxSeedSeconds = if (o.isNull("maxSeconds")) null else o.getLong("maxSeconds"),
            uploadLimit = if (o.isNull("uploadBytes")) null
                else RateLimit(o.getLong("uploadBytes").also { require(it > 0) }, RateLimit.Unit.valueOf(o.getString("uploadUnit"))),
            combine = SeedingPolicy.Combine.valueOf(o.getString("combine")))

        private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    }
}
