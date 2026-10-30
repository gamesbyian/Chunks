package io.github.gamesbyian.chunks.rpc

/**
 * Exact-infohash read-only observations. d.down.total counts cumulative
 * received traffic (including wasted/redundant bytes); d.completed_bytes is
 * hash-verified torrent completion. Neither a nonzero instantaneous d.down.rate
 * nor d.state=1 proves a file is downloading. A changed counter is evidence
 * only for the bounded observed window, not for future network availability.
 */
data class RemoteTrafficSample(
    val hash: String,
    val monotonicNanos: Long,
    val receivedBytes: Long?,
    val verifiedBytes: Long?,
    val expectedBytes: Long?,
    val active: Boolean?,
    val peers: Long?,
    val rateBytesPerSecond: Long?
)

enum class TrafficVerdict {
    RECEIVED_AND_VERIFIED,
    RECEIVED_ONLY,
    VERIFIED_ONLY,
    NO_PROGRESS_OBSERVED,
    COUNTER_RESET_OR_SIZE_CHANGED,
    INSUFFICIENT_EVIDENCE
}

/**
 * Pure, conservative reducer: requires three separately sampled observations
 * of the same hash, strictly increasing monotonic timestamps and nonnegative
 * values. Any reset, impossible completion, or total size change invalidates
 * a positive conclusion. Incomplete/unavailable RPC counters stay unknown.
 */
data class RemoteTrafficWindow(
    val verdict: TrafficVerdict,
    val elapsedMillis: Long,
    val receivedDelta: Long?,
    val verifiedDelta: Long?,
    val peakPeers: Long?,
    val observedActive: Boolean?
) {
    companion object {
        fun evaluate(samples: List<RemoteTrafficSample>): RemoteTrafficWindow {
            require(samples.size == 3) { "Three consecutive read-only observations required" }
            val first = samples.first()
            require(Regex("[0-9a-fA-F]{40}").matches(first.hash)) { "Invalid transfer identity" }
            require(samples.all { it.hash.equals(first.hash, ignoreCase = true) }) {
                "Server changed transfer identity during observation"
            }
            require(samples.zipWithNext().all { (a, b) -> b.monotonicNanos > a.monotonicNanos }) {
                "Nonmonotonic observation clock"
            }
            val elapsed = (samples.last().monotonicNanos - first.monotonicNanos) / 1_000_000L
            val peaks = samples.mapNotNull { it.peers?.takeIf { n -> n >= 0 } }.maxOrNull()
            val active = when {
                samples.any { it.active == true } -> true
                samples.all { it.active == false } -> false
                else -> null
            }
            val received = samples.map { it.receivedBytes }
            val verified = samples.map { it.verifiedBytes }
            val sizes = samples.mapNotNull { it.expectedBytes }
            val invalid = samples.any {
                listOf(it.receivedBytes, it.verifiedBytes, it.expectedBytes,
                    it.peers, it.rateBytesPerSecond).any { n -> n != null && n < 0L }
            } || sizes.distinct().size > 1 ||
                (sizes.isNotEmpty() && verified.any { it != null && it > sizes[0] })
            fun delta(values: List<Long?>): Long? {
                if (values.any { it == null }) return null
                val present = values.filterNotNull()
                if (present.zipWithNext().any { (a, b) -> b < a }) return null
                return present.last() - present.first()
            }
            val reset = invalid || listOf(received, verified).any { values ->
                val known = values.filterNotNull()
                known.zipWithNext().any { (a, b) -> b < a }
            }
            val inDelta = if (reset) null else delta(received)
            val verifiedDelta = if (reset) null else delta(verified)
            val verdict = when {
                reset -> TrafficVerdict.COUNTER_RESET_OR_SIZE_CHANGED
                inDelta != null && inDelta > 0 && verifiedDelta != null && verifiedDelta > 0 ->
                    TrafficVerdict.RECEIVED_AND_VERIFIED
                inDelta != null && inDelta > 0 -> TrafficVerdict.RECEIVED_ONLY
                verifiedDelta != null && verifiedDelta > 0 -> TrafficVerdict.VERIFIED_ONLY
                // A missing sample from either cumulative counter prevents a
                // justified "no progress" verdict. Any *positive* complete
                // counter is still separately reportable above.
                inDelta == null || verifiedDelta == null -> TrafficVerdict.INSUFFICIENT_EVIDENCE
                else -> TrafficVerdict.NO_PROGRESS_OBSERVED
            }
            return RemoteTrafficWindow(verdict, elapsed, inDelta, verifiedDelta, peaks, active)
        }
    }

    fun summary(): String = when (verdict) {
        TrafficVerdict.RECEIVED_AND_VERIFIED ->
            "Download traffic and hash-verified completed bytes both increased during this observation. This confirms progress on the server, not the phone's own download."
        TrafficVerdict.RECEIVED_ONLY ->
            "Download traffic increased, but growth of hash-verified completion was not confirmed. Data may still be in-flight, redundant, or rejected."
        TrafficVerdict.VERIFIED_ONLY ->
            "Hash-verified completion increased, but received network traffic was not confirmed. Rechecking existing bytes can produce this result."
        TrafficVerdict.NO_PROGRESS_OBSERVED ->
            "No positive byte growth was observed in this short window. This does not establish a permanent stall or a tracker failure."
        TrafficVerdict.COUNTER_RESET_OR_SIZE_CHANGED ->
            "Counters decreased or the reported torrent size changed; this observation cannot establish download progress. Recheck the current transfer identity and state."
        TrafficVerdict.INSUFFICIENT_EVIDENCE ->
            "This rTorrent gateway did not provide enough stable cumulative counters to determine progress."
    }
}
