package io.github.gamesbyian.chunks.rpc

/**
 * Read-only, exact-hash rTorrent facts for a future per-account policy adapter.
 *
 * This is deliberately NOT a SeedingController observation or command ticket.
 * Unknown counters remain null. Historical totals can reflect imported
 * sessions, a timestamp is not cumulative active seed time, and a named
 * throttle is a shared rate bucket rather than a per-torrent upload cap.
 * None of these fields grants automatic Stop, throttle creation or Start.
 */
data class RemoteSeedingEvidence(
    /** Exact authenticated endpoint + opaque principal; never a display alias.
     * Hash alone does not identify which remote client owns these counters. */
    val accountScope: String,
    val infoHashV1: String,
    val uploadedBytes: Long?,
    val downloadedBytes: Long?,
    val complete: Boolean?,
    val active: Boolean?,
    val finishedAtEpochSeconds: Long?,
    val throttleName: String?
) {
    // An infohash and a server-defined throttle name may reveal private
    // transfer information. Kotlin's default data-class rendering must never
    // leak them into unexpected test failures or log messages.
    override fun toString() = "RemoteSeedingEvidence(private transfer)"
}
