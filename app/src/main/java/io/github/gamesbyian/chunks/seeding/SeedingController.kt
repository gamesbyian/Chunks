package io.github.gamesbyian.chunks.seeding

/**
 * Applies Smart Seeding Rules to one engine's transfers, without retroactive
 * surprises (SMART-SEEDING-RULES):
 *
 * - A transfer gets a policy **snapshot** the first time it is seen after the
 *   controller was first enabled; transfers that existed before stay untouched
 *   for good, and later rule edits never change an existing snapshot.
 * - The upload cap is applied only with exact readback.
 * - "Stop" pauses the transfer once, after a verified pause; if the user
 *   resumes it, the rule never stops it again. Nothing is ever removed or deleted.
 */
class SeedingController(private val engine: EnginePort, private val store: AssignmentStore) {
    data class Facts(
        val infoHash: String,
        /** Wall-clock ms the engine added the transfer. */
        val addedAtMs: Long,
        /** Null while metadata is unknown. */
        val trackerUrls: List<String>?,
        val isPrivate: Boolean?
    )

    interface EnginePort {
        fun transfers(): List<Facts>
        fun observe(infoHash: String): SeedingObservation?
        fun uploadLimit(infoHash: String): Int?
        fun setUploadLimit(infoHash: String, bytesPerSecond: Long?): Boolean
        /** True only after the engine confirms the transfer is paused. */
        fun pause(infoHash: String): Boolean
    }

    data class Assignment(
        /** Null: existed before Smart Seeding was enabled; never managed. */
        val policy: SeedingPolicy?,
        val source: SeedingResolution.Source?,
        val ruleId: String?,
        val reasons: List<String> = emptyList(),
        val stoppedByRule: String? = null,
        val note: String? = null
    )

    interface AssignmentStore {
        /** Wall-clock ms of the first controller run; set once. */
        fun epoch(): Long?
        fun setEpoch(ms: Long): Boolean
        fun get(infoHash: String): Assignment?
        fun put(infoHash: String, assignment: Assignment): Boolean
    }

    fun tick(environment: String, rules: List<SeedingRule>, default: SeedingPolicy, nowMs: Long) {
        val epoch = store.epoch() ?: nowMs.also { if (!store.setEpoch(it)) return }
        for (facts in engine.transfers()) {
            var assignment = store.get(facts.infoHash)
            if (assignment == null) {
                assignment = if (facts.addedAtMs < epoch) Assignment(null, null, null)
                else {
                    // Magnets wait for metadata, so tracker identity and the private flag are known.
                    if (facts.trackerUrls == null) continue
                    val r = SeedingRuleEngine.resolve(TransferFacts(environment, facts.trackerUrls, facts.isPrivate), rules, default)
                    Assignment(r.policy, r.source, r.ruleId, r.reasons)
                }
                if (!store.put(facts.infoHash, assignment)) continue
            }
            val policy = assignment.policy ?: continue
            enforce(facts.infoHash, policy, assignment)
        }
    }

    private fun enforce(hash: String, policy: SeedingPolicy, assignment: Assignment) {
        val wanted = policy.uploadLimit?.bytesPerSecond?.toInt() ?: 0
        if (engine.uploadLimit(hash) != wanted && !engine.setUploadLimit(hash, policy.uploadLimit?.bytesPerSecond)) {
            store.put(hash, assignment.copy(note = "The phone engine did not confirm the upload limit."))
        }
        if (assignment.stoppedByRule != null) return
        val observation = engine.observe(hash) ?: return
        val decision = SeedingEnforcement.decide(policy, observation)
        if (decision is SeedingEnforcement.Decision.Stop) {
            if (engine.pause(hash)) store.put(hash, assignment.copy(stoppedByRule = decision.reason, note = null))
            else store.put(hash, assignment.copy(note = "Seeding should stop (${decision.reason}) but the engine did not confirm the pause."))
        }
    }
}
