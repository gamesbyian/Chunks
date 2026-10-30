package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.privacy.TransferLockMode

/**
 * What Chunks tells the user after Android's dataSync time limit (Android
 * 15+) requested a pause of phone transfers. The service removes its ongoing
 * notification when it stops, so without this the pause would be silent.
 * Counts are only claims about readback-confirmed pauses; a privacy-locked
 * phone scope gets no counts at all.
 */
internal object LocalServiceTimeoutNotice {
    const val NOTIFICATION_ID = 4102
    const val TITLE = "Chunks background time limit"

    /** [listed] is null when the running-transfer list itself could not be read. */
    data class PauseSummary(val listed: Int?, val confirmed: Int) {
        init { require(listed == null || confirmed in 0..listed) }
    }

    fun text(summary: PauseSummary, policy: TransferLockMode): String {
        val lead = "Android's background time limit was reached."
        if (policy != TransferLockMode.NEVER) return "$lead Open Chunks to check transfers on this phone."
        val listed = summary.listed
            ?: return "$lead Chunks tried to pause transfers on this phone but could not confirm it. Open Chunks to check."
        if (listed == 0) return "$lead No transfers were running on this phone."
        val noun = if (listed == 1) "transfer" else "transfers"
        return if (summary.confirmed == listed)
            "$lead Chunks paused $listed $noun on this phone. Open Chunks to resume."
        else "$lead Chunks paused ${summary.confirmed} of $listed $noun; " +
            "${listed - summary.confirmed} could not be confirmed. Open Chunks to check."
    }
}
