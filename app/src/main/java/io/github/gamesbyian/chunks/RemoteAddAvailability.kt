package io.github.gamesbyian.chunks

/**
 * When the remote (rTorrent) Add control may act, and what to tell the person when it may not.
 * Pure, so each condition has a JVM witness (A2611-08); the composable only supplies the facts.
 */
internal object RemoteAddAvailability {
    fun enabled(
        hasPlan: Boolean,
        submitting: Boolean,
        choosing: Boolean,
        externalSourceReading: Boolean,
        noFileChosen: Boolean,
        mutationReady: Boolean,
        aliasValid: Boolean
    ): Boolean = hasPlan && !submitting && !choosing && !externalSourceReading &&
        !noFileChosen && mutationReady && aliasValid

    /** The guidance shown while the control is unavailable; null when no specific reason applies. */
    fun unavailableReason(hasPlan: Boolean, mutationReady: Boolean, planGuidance: String?): String? = when {
        !hasPlan -> planGuidance
        !mutationReady -> "Waiting for a fresh confirmed remote transfer list."
        else -> null
    }
}
