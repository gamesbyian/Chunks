package io.github.gamesbyian.chunks.seeding

import android.content.Context
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import org.json.JSONArray
import org.json.JSONObject

/** The local engine as a [SeedingController.EnginePort]. */
class LocalSeedingPort(private val engine: LocalTorrentEngine) : SeedingController.EnginePort {
    override fun transfers() = engine.seedingFacts()
    override fun observe(infoHash: String) = engine.seedingObservation(infoHash)
    override fun uploadLimit(infoHash: String) = engine.uploadLimit(infoHash)
    override fun setUploadLimit(infoHash: String, bytesPerSecond: Long?) = engine.setUploadLimit(infoHash, bytesPerSecond)
    override fun pause(infoHash: String) = engine.pause(infoHash).outcome == LocalCommandOutcome.VERIFIED
}

/** Device-private per-transfer policy snapshots for the local device. Never holds tracker URLs. */
class PreferencesAssignmentStore(
    context: Context,
    /** Null: the local device's existing store. Otherwise one exact remote account's own store. */
    environment: String? = null
) : SeedingController.AssignmentStore {
    private val prefs = context.applicationContext.getSharedPreferences(
        if (environment == null) "chunks_seeding_assignments"
        else "chunks_seeding_assignments_" + digest(environment), Context.MODE_PRIVATE)

    companion object {
        private const val REMOTE_BASELINE = "remote_baseline_complete_v1"
        private val remoteActivationLock = Any()
        private val v1Hash = Regex("[0-9a-f]{40}")
        /** A stable, non-reversible file/key name for an environment ID (no endpoint or user in storage names). */
        internal fun digest(environment: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(environment.toByteArray())
                .take(16).joinToString("") { "%02x".format(it) }
    }
    /**
     * A corrupt/wrong-type epoch must never crash the service poll or be
     * treated as a new activation: doing so could apply rules to old jobs.
     * An unreadable existing key is preserved so setEpoch refuses to reset
     * the eligibility boundary without a separate explicit recovery action.
     */
    override fun epoch(): Long? = try {
        prefs.getLong("epoch", -1L).takeIf { it >= 0 }
    } catch (_: RuntimeException) { null }

    override fun setEpoch(ms: Long): Boolean {
        if (ms < 0L) return false
        return try {
            if (prefs.contains("epoch")) false
            else prefs.edit().putLong("epoch", ms).commit()
        } catch (_: RuntimeException) { false }
    }

    internal fun remoteBaselineComplete(): Boolean = try {
        prefs.getBoolean(REMOTE_BASELINE, false) && epoch() != null
    } catch (_: RuntimeException) { false }

    /**
     * Preserve every occupied assignment, including unreadable records and
     * unresolved Stop markers. Publish the exact baseline and epoch together.
     */
    internal fun establishRemoteBaseline(ms: Long, roster: List<String>, ownerCurrent: () -> Boolean): Boolean {
        if (ms < 0L || !ownerCurrent() || roster.any { !v1Hash.matches(it) } ||
            roster.toSet().size != roster.size) return false
        return synchronized(remoteActivationLock) {
            try {
                if (prefs.contains(REMOTE_BASELINE) || !ownerCurrent()) return@synchronized false
                val now = epoch()
                if (now == null && prefs.contains("epoch")) return@synchronized false
                val transaction = prefs.edit().putBoolean(REMOTE_BASELINE, true)
                if (now == null) transaction.putLong("epoch", ms)
                val unmanaged = encode(SeedingController.Assignment(null, null, null))
                for (hash in roster) {
                    val key = "a:$hash"
                    if (!prefs.contains(key)) transaction.putString(key, unmanaged)
                }
                ownerCurrent() && transaction.commit()
            } catch (_: RuntimeException) { false }
        }
    }

    override fun get(infoHash: String): SeedingController.Assignment? {
        val key = "a:" + infoHash.lowercase(java.util.Locale.ROOT)
        // An occupied but unreadable record may contain a previously
        // committed Pause attempt. Treating it as "no assignment" would
        // permit a new policy snapshot and repeat an uncertain engine write.
        // Fail closed for this transfer; preserve the original corrupt bytes.
        val raw = try { prefs.getString(key, null) }
            catch (_: RuntimeException) { return unreadableAssignment() }
            ?: return if (prefs.contains(key)) unreadableAssignment() else null
        return try {
            val o = JSONObject(raw)
            SeedingController.Assignment(
                policy = if (o.isNull("policy")) null else SeedingRuleStore.policy(o.getJSONObject("policy")),
                source = if (o.isNull("source")) null else SeedingResolution.Source.valueOf(o.getString("source")),
                ruleId = if (o.isNull("ruleId")) null else o.getString("ruleId"),
                reasons = o.optJSONArray("reasons")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                stoppedByRule = if (o.isNull("stopped")) null else o.getString("stopped"),
                note = if (o.isNull("note")) null else o.getString("note"),
                stopAttemptedReason = if (o.isNull("stopAttempt")) null else o.getString("stopAttempt"))
        } catch (_: Exception) { unreadableAssignment() }
    }

    private fun unreadableAssignment() = SeedingController.Assignment(
        policy = null, source = null, ruleId = null,
        note = "Saved seeding assignment could not be read; this transfer was left unchanged."
    )

    override fun put(infoHash: String, assignment: SeedingController.Assignment) = try {
        prefs.edit().putString("a:" + infoHash.lowercase(), encode(assignment)).commit()
    } catch (_: RuntimeException) { false }

    private fun encode(assignment: SeedingController.Assignment): String =
        JSONObject()
            .put("policy", assignment.policy?.let { SeedingRuleStore.policy(it) } ?: JSONObject.NULL)
            .put("source", assignment.source?.name ?: JSONObject.NULL)
            .put("ruleId", assignment.ruleId ?: JSONObject.NULL)
            .put("reasons", JSONArray(assignment.reasons))
            .put("stopped", assignment.stoppedByRule ?: JSONObject.NULL)
            .put("note", assignment.note ?: JSONObject.NULL)
            .put("stopAttempt", assignment.stopAttemptedReason ?: JSONObject.NULL)
            .toString()
}

/** One controller step for the local device, from the foreground service poll. Unreadable rules mean no change. */
object LocalSeeding {
    const val ENVIRONMENT = "local"

    fun tick(context: Context, engine: LocalTorrentEngine, nowMs: Long = System.currentTimeMillis()) {
        val snapshot = SeedingRuleStore(context).load()
        if (snapshot.unreadable) return
        SeedingController(LocalSeedingPort(engine), PreferencesAssignmentStore(context))
            .tick(ENVIRONMENT, snapshot.rules, snapshot.defaults[ENVIRONMENT] ?: SeedingPolicy(), nowMs)
    }
}
