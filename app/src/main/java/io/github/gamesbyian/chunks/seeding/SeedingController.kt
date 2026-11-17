package io.github.gamesbyian.chunks.seeding

/**
 * Applies Smart Seeding Rules to one engine's transfers, without retroactive
 * surprises (SMART-SEEDING-RULES):
 *
 * - A transfer gets a policy **snapshot** the first time it is seen after the
 *   controller was first enabled; transfers that existed before stay untouched
 *   for good, and later rule edits never change an existing snapshot.
 * - The upload cap is applied only with exact readback.
 * - "Stop" has a durable write-ahead attempt marker: an uncertain pause
 *   is never automatically replayed. A confirmed pause remains a one-time
 *   action even if the user resumes. Nothing is removed or deleted.
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
        val note: String? = null,
        /** Persisted BEFORE any automatic Pause call. Non-null means the
         * engine may have acted, even if readback or this process failed.
         * A later tick must not blindly send that command a second time. */
        val stopAttemptedReason: String? = null
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
                    // Tracker URLs may arrive before the metainfo private flag.
                    // Never durably snapshot a provisional fallback as if the
                    // rule were resolved: assignments do not auto-rematch after
                    // later metadata arrives.
                    if (facts.trackerUrls == null) continue
                    val r = SeedingRuleEngine.resolve(
                        TransferFacts(environment, facts.trackerUrls, facts.isPrivate), rules, default)
                    if (r.source == SeedingResolution.Source.PENDING) continue
                    Assignment(r.policy, r.source, r.ruleId, r.reasons)
                }
                if (!store.put(facts.infoHash, assignment)) continue
            }
            val policy = assignment.policy ?: continue
            enforce(facts.infoHash, policy, assignment)
        }
    }

    private fun enforce(hash: String, policy: SeedingPolicy, assignment: Assignment) {
        // No cap in a policy means "do not manage this setting", not an
        // instruction to erase an independently configured per-job cap.
        // libtorrent exposes an Int upload limit. Never narrow a saved Long
        // before checking it: overflow used to produce negative readbacks
        // and could throw from the foreground service's periodic tick.
        policy.uploadLimit?.bytesPerSecond?.let { bytes ->
            if (bytes !in 1L..Int.MAX_VALUE.toLong()) {
                store.put(hash, assignment.copy(
                    note = "This upload limit is outside the client's supported range; it was not applied."))
            } else if (engine.uploadLimit(hash) != bytes.toInt()) {
                // A failed or unsupported engine setter must not abort
                // enforcement for this or the remaining transfers.
                val confirmed = runCatching { engine.setUploadLimit(hash, bytes) }.getOrDefault(false)
                if (!confirmed) store.put(hash, assignment.copy(
                    note = "The client did not confirm the upload limit."))
            }
        }
        // A verified stop or an uncertain earlier attempt is terminal for
        // automatic enforcement. Observing an unpaused torrent later is not
        // authority to replay Pause: the owner may have resumed it.
        if (assignment.stoppedByRule != null || assignment.stopAttemptedReason != null) return
        val observation = engine.observe(hash) ?: return
        val decision = SeedingEnforcement.decide(policy, observation)
        if (decision is SeedingEnforcement.Decision.Stop) {
            // Commit the attempt BEFORE calling the engine. A failed commit is
            // a known no-write outcome. If the process dies after this commit,
            // the durable marker prevents repeating an uncertain Pause.
            val attempted = assignment.copy(
                stopAttemptedReason = decision.reason,
                note = "Automatic stop is pending confirmation; it will not be retried automatically."
            )
            if (!store.put(hash, attempted)) return
            val confirmed = runCatching { engine.pause(hash) }.getOrDefault(false)
            if (confirmed) {
                store.put(hash, attempted.copy(stoppedByRule = decision.reason, note = null))
            } else {
                store.put(hash, attempted.copy(
                    note = "The engine did not confirm the pause. It may have happened; Chunks will not retry automatically."
                ))
            }
        }
    }
}
