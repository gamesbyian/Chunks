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
        /** False for engines with no per-transfer upload cap (rTorrent only has shared throttles). */
        val supportsPerTransferUploadCap: Boolean get() = true
        fun transfers(): List<Facts>
        /**
         * Tracker/private facts for a transfer not yet assigned, when [transfers]
         * leaves them unknown to avoid a per-transfer read every tick.
         */
        fun details(facts: Facts): Facts = facts
        fun observe(infoHash: String): SeedingObservation?
        fun uploadLimit(infoHash: String): Int?
        fun setUploadLimit(infoHash: String, bytesPerSecond: Long?): Boolean
        /** Whether this exact cap can be stored without rounding (else it is never written). */
        fun uploadCapRepresentable(bytesPerSecond: Long): Boolean = true
        /** True only after the engine confirms the transfer is paused. */
        fun pause(infoHash: String): Boolean
        fun mayMutate(): Boolean = true
        fun withPolicyAccess(infoHash: String, work: () -> Unit) = work()
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
        if (!engine.mayMutate()) return
        val epoch = store.epoch() ?: nowMs.also { if (!store.setEpoch(it)) return }
        for (facts in engine.transfers()) {
            if (!engine.mayMutate()) return
            var assignment = store.get(facts.infoHash)
            if (assignment == null) {
                assignment = if (facts.addedAtMs < epoch) Assignment(null, null, null)
                else {
                    // Tracker URLs may arrive before the metainfo private flag.
                    // Never durably snapshot a provisional fallback as if the
                    // rule were resolved: assignments do not auto-rematch after
                    // later metadata arrives.
                    val described = if (facts.trackerUrls == null)
                        runCatching { engine.details(facts) }.getOrNull() ?: continue else facts
                    if (described.trackerUrls == null) continue
                    val r = SeedingRuleEngine.resolve(
                        TransferFacts(environment, described.trackerUrls, described.isPrivate), rules, default)
                    if (r.source == SeedingResolution.Source.PENDING) continue
                    Assignment(r.policy, r.source, r.ruleId, r.reasons)
                }
                if (!store.put(facts.infoHash, assignment)) continue
            }
            val policy = assignment.policy ?: continue
            val mayStop = !policy.seedsForever && assignment.stoppedByRule == null &&
                assignment.stopAttemptedReason == null
            if (policy.uploadLimit == null && !mayStop) continue
            engine.withPolicyAccess(facts.infoHash) { enforce(facts.infoHash, policy, assignment, mayStop) }
        }
    }

    private fun enforce(hash: String, policy: SeedingPolicy, assignment: Assignment, mayStop: Boolean) {
        if (!engine.mayMutate()) return
        // No cap in a policy means "do not manage this setting", not an
        // instruction to erase an independently configured per-job cap.
        // libtorrent exposes an Int upload limit. Never narrow a saved Long
        // before checking it: overflow used to produce negative readbacks
        // and could throw from the foreground service's periodic tick.
        policy.uploadLimit?.bytesPerSecond?.let { bytes ->
            if (!engine.supportsPerTransferUploadCap) {
                // Never emulate a per-transfer cap with a shared throttle group.
                if (assignment.note == null) store.put(hash, assignment.copy(
                    note = "This client has no per-transfer upload limit; the rule's limit was not applied."))
            } else if (bytes !in 1L..Int.MAX_VALUE.toLong() || !engine.uploadCapRepresentable(bytes)) {
                // A persisted uncertain Pause is the stronger operational
                // warning. Never erase it while reporting a separate cap
                // fault. The policy remains unchanged and no cap is written.
                if (assignment.note == null) store.put(hash, assignment.copy(
                    note = "This upload limit is outside the client's supported range; it was not applied."))
            } else {
                // A native read may fail for one disappearing/invalid handle.
                // Failure grants no authority to write a cap and must not
                // prevent other transfers from being evaluated this tick.
                // LocalTorrentEngine already maps some libtorrent read
                // exceptions to null; a thrown error and a null response are
                // equally unknown, never permission to send a cap setter.
                val currentLimit = runCatching { engine.uploadLimit(hash) }.getOrNull()
                val readFailureNote =
                    "The client could not check this transfer's upload limit; it was left unchanged."
                if (currentLimit == null) {
                    // Existing notes can record a lost-ACK Pause or
                    // unconfirmed cap write. A read fault must never erase
                    // that more important safety warning.
                    if (assignment.note == null) store.put(hash,
                        assignment.copy(note = readFailureNote))
                } else {
                    if (currentLimit != bytes.toInt()) {
                        // The setter itself requires verified engine readback.
                        val confirmed = runCatching {
                            engine.setUploadLimit(hash, bytes)
                        }.getOrDefault(false)
                        if (!confirmed) {
                            // Preserve an earlier uncertain Pause or other
                            // safety warning. A cap setter failure is secondary,
                            // and cannot erase the durable no-retry explanation.
                            if (assignment.note == null || assignment.note == readFailureNote)
                                store.put(hash, assignment.copy(
                                    note = "The client did not confirm the upload limit."))
                        } else if (assignment.note == readFailureNote) store.put(hash,
                            assignment.copy(note = null))
                    } else if (assignment.note == readFailureNote) store.put(hash,
                        assignment.copy(note = null))
                }
            }
        }
        // A verified stop or an uncertain earlier attempt is terminal for
        // automatic enforcement. Observing an unpaused torrent later is not
        // authority to replay Pause: the owner may have resumed it.
        if (!mayStop) return
        // An inaccessible per-torrent native counter must never abort the
        // service-wide poll or authorize an unverified automatic Pause.
        val observation = runCatching { engine.observe(hash) }.getOrNull() ?: return
        val decision = SeedingEnforcement.decide(policy, observation)
        if (decision is SeedingEnforcement.Decision.Stop && engine.mayMutate()) {
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
