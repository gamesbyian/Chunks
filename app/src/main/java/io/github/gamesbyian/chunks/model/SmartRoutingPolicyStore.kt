package io.github.gamesbyian.chunks.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single persisted owner for Smart Routing's *proposed* destinations.
 *
 * Rules select candidate exact environment IDs only. No method in this store
 * can Add, Start, Move, authenticate, inspect a network, or override the
 * Universal Add ticket. This is deliberately safe to use before the future
 * Options rule editor and client-specific Add UI are connected.
 *
 * Persistence contains no tracker URL, account name, authentication principal,
 * magnet, infohash, raw source filename or server URL. Server target IDs must
 * be EnvironmentSnapshots' SHA-256 opaque IDs, never an endpoint address.
 */
internal class SmartRoutingPolicyStore(context: Context) {
    enum class Fallback { ASK }

    data class Policy(
        val enabled: Boolean = false,
        val fallback: Fallback = Fallback.ASK,
        /** Order is user authority when priorities tie. */
        val rules: List<SmartRoutingPreview.Rule> = emptyList(),
        /**
         * Explicit opt-in: Universal Add PRE-SELECTS a confident suggestion's
         * exact client (once per reviewed source, never over the person's own
         * choice). It never submits an Add; the Add tap stays the authority.
         */
        val autoSelect: Boolean = false
    )

    sealed class Read {
        data class Ready(val revision: Long, val policy: Policy) : Read()
        /** Unknown schema, corruption or storage type. Preserve raw bytes. */
        object Unavailable : Read()
    }

    enum class Save {
        SAVED, STALE_REVISION, UNAVAILABLE, INVALID, STORAGE_FAILED
    }

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    companion object {
        private const val PREFERENCES = "chunks-smart-routing-policy"
        private const val KEY = "routing-policy-v1"
        private const val SCHEMA = 1
        private const val MAX_RULES = 500
        private const val MAX_STORED_CHARS = 250_000
        private val opaqueServerId = Regex("[0-9a-f]{64}")
        private val safeRuleId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
        /** Serializes revisions even when callers create separate store instances. */
        private val writeLock = Any()

        private fun valid(policy: Policy): Boolean =
            policy.rules.size <= MAX_RULES &&
                policy.rules.map { it.id }.distinct().size == policy.rules.size &&
                policy.rules.all { rule ->
                    safeRuleId.matches(rule.id) &&
                        (rule.targetEnvironmentId == EnvironmentSnapshots.LOCAL.value ||
                            opaqueServerId.matches(rule.targetEnvironmentId)) &&
                        rule.minimumConfidence in 0..100
                }
    }

    private fun JSONObject.strictInteger(name: String): Int {
        val value = get(name)
        require(value is Number && value.toString() == value.toInt().toString())
        return value.toInt()
    }

    private fun JSONObject.strictString(name: String): String {
        val value = get(name)
        require(value is String)
        return value
    }

    /** A malformed saved policy is not an invitation to invent defaults. */
    fun read(): Read {
        val raw = try { prefs.getString(KEY, null) }
            catch (_: RuntimeException) { return Read.Unavailable }
            ?: return Read.Ready(0L, Policy())
        if (raw.length > MAX_STORED_CHARS) return Read.Unavailable
        return try {
            val objectValue = JSONObject(raw)
            if (objectValue.strictInteger("schema") != SCHEMA) return Read.Unavailable
            val revisionValue = objectValue.get("revision")
            if (revisionValue !is Number) return Read.Unavailable
            val revision = revisionValue.toLong()
            if (revision < 0 || revisionValue.toString() != revision.toString())
                return Read.Unavailable
            val enabledValue = objectValue.get("enabled")
            if (enabledValue !is Boolean) return Read.Unavailable
            val fallback = Fallback.valueOf(objectValue.strictString("fallback"))
            val jsonRules = objectValue.getJSONArray("rules")
            if (jsonRules.length() > MAX_RULES) return Read.Unavailable
            val rules = ArrayList<SmartRoutingPreview.Rule>(jsonRules.length())
            for (i in 0 until jsonRules.length()) {
                val entry = jsonRules.getJSONObject(i)
                rules.add(SmartRoutingPreview.Rule(
                    id = entry.strictString("id"),
                    priority = entry.strictInteger("priority"),
                    kind = SmartRoutingPreview.Kind.valueOf(entry.strictString("kind")),
                    minimumConfidence = entry.strictInteger("minimumConfidence"),
                    targetEnvironmentId = entry.strictString("targetEnvironmentId")
                ))
            }
            // Optional (absent in policies saved before it existed = off); a
            // present non-Boolean value is corruption, never a guessed default.
            val autoSelect = if (!objectValue.has("autoSelect")) false
                else objectValue.get("autoSelect") as? Boolean ?: return Read.Unavailable
            val policy = Policy(enabledValue, fallback, rules, autoSelect)
            if (!valid(policy)) Read.Unavailable else Read.Ready(revision, policy)
        } catch (_: Exception) {
            // Never overwrite an unknown future schema, legacy bytes, or a
            // corrupted policy with an apparently valid empty default.
            Read.Unavailable
        }
    }

    /**
     * Synchronous compare-and-set commit. Call off the UI thread.
     * A rejected update never rewrites, deletes, or "repairs" saved rules.
     */
    fun save(expectedRevision: Long, next: Policy): Save {
        if (expectedRevision < 0 || !valid(next)) return Save.INVALID
        return synchronized(writeLock) {
            val existing = read() as? Read.Ready ?: return@synchronized Save.UNAVAILABLE
            if (existing.revision != expectedRevision) return@synchronized Save.STALE_REVISION
            if (existing.revision == Long.MAX_VALUE) return@synchronized Save.INVALID
            val json = JSONObject()
                .put("schema", SCHEMA)
                .put("revision", existing.revision + 1)
                .put("enabled", next.enabled)
                .put("fallback", next.fallback.name)
                .put("autoSelect", next.autoSelect)
            val entries = JSONArray()
            for (rule in next.rules) {
                entries.put(JSONObject()
                    .put("id", rule.id)
                    .put("priority", rule.priority)
                    .put("kind", rule.kind.name)
                    .put("minimumConfidence", rule.minimumConfidence)
                    .put("targetEnvironmentId", rule.targetEnvironmentId))
            }
            json.put("rules", entries)
            try {
                if (prefs.edit().putString(KEY, json.toString()).commit()) Save.SAVED
                else Save.STORAGE_FAILED
            } catch (_: RuntimeException) { Save.STORAGE_FAILED }
        }
    }

    /**
     * Read-only candidate for an eventual capability-driven Add form.
     * Disabled/unknown policies always Ask; an explicitly chosen target can
     * still be checked independently, but never acquires write authority.
     */
    fun preview(
        evidence: SmartRoutingPreview.Evidence,
        destinations: List<SmartRoutingPreview.Destination>,
        explicitEnvironmentId: String? = null
    ): SmartRoutingPreview.Decision {
        val state = read() as? Read.Ready
        if (explicitEnvironmentId != null)
            return SmartRoutingPreview.decide(evidence, emptyList(), destinations,
                explicitEnvironmentId = explicitEnvironmentId)
        if (state == null || !state.policy.enabled)
            return SmartRoutingPreview.Decision(
                SmartRoutingPreview.Outcome.ASK,
                SmartRoutingPreview.classify(evidence),
                reason = if (state == null) "saved-routing-policy-unavailable"
                    else "smart-routing-disabled")
        return SmartRoutingPreview.decide(evidence, state.policy.rules, destinations)
    }
}
