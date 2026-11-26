package io.github.gamesbyian.chunks

/**
 * When the Local Add control may act, and why not. Pure, so every condition has a JVM witness
 * (A2611-08): the composable only supplies the current facts. The first unmet condition wins,
 * in the order a person would fix them.
 */
internal object LocalAddAvailability {
    /** The reason a typed or pasted magnet cannot be added yet, or null when it can. */
    fun magnetUnavailableReason(
        busy: Boolean,
        sourceReading: Boolean,
        inventoryReady: Boolean,
        settingsSaving: Boolean,
        hasVerifiedPlace: Boolean,
        magnetValid: Boolean,
        aliasValid: Boolean,
        previousAttemptUnresolved: Boolean
    ): String? = when {
        busy -> "A local operation is in progress."
        sourceReading -> "Waiting for source review."
        !inventoryReady -> "Waiting for a fresh confirmed local transfer list."
        settingsSaving -> "Waiting for local settings to settle."
        !hasVerifiedPlace -> "Choose a verified local download location."
        !magnetValid -> "Review a valid magnet."
        !aliasValid -> "Review the display name."
        previousAttemptUnresolved -> "Finish the previous Add before adding another."
        else -> null
    }

    /** Whether a reviewed .torrent may be added now. */
    fun torrentReady(
        hasReviewedTorrent: Boolean,
        hasDestination: Boolean,
        busy: Boolean,
        reviewedMetainfoCurrent: Boolean,
        aliasValid: Boolean,
        inventoryReady: Boolean,
        placeSaving: Boolean,
        hasVerifiedPlace: Boolean,
        previousAttemptUnresolved: Boolean,
        originalDocumentDeleting: Boolean
    ): Boolean = hasReviewedTorrent && hasDestination && !busy && reviewedMetainfoCurrent &&
        aliasValid && inventoryReady && !placeSaving && hasVerifiedPlace &&
        !previousAttemptUnresolved && !originalDocumentDeleting
}
