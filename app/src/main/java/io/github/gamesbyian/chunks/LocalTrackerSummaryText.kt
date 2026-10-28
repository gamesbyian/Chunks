package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTrackerSummary

/** Plain-language tracker line for a local transfer card. Never shows URLs. */
internal object LocalTrackerSummaryText {
    fun describe(summary: LocalTrackerSummary?, stopped: Boolean, dhtEnabled: Boolean): String? = when {
        summary == null -> null
        summary.total == 0 -> if (dhtEnabled) "No trackers: peers can only come from DHT or web sources."
            else "No trackers and DHT is off: peers can only come from web sources."
        stopped -> "Trackers: ${summary.total} listed (contacted after Start)."
        summary.working > 0 -> "Trackers: ${summary.working} of ${summary.total} responding."
        summary.updating > 0 -> "Trackers: contacting ${summary.total}…"
        summary.failing > 0 -> "Trackers: none of ${summary.total} responding yet."
        else -> "Trackers: ${summary.total} listed, not contacted yet."
    }
}
