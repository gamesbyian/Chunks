package io.github.gamesbyian.chunks

/**
 * Pure Add draft/occupancy decisions extracted from `ChunksApp` (A2611-02: Add callbacks).
 * Inputs are plain observed values; nothing here reads composition state or writes.
 */
internal object AddSourceOccupancy {
    /**
     * An unsubmitted draft or a not-yet-releasable attempt holds a new shared source. A first
     * Android document being read is an occupied draft even before its metainfo is visible.
     * [incomingText] is the text a pending shared magnet would replace; equal text is not a
     * competing draft.
     */
    fun occupied(
        sharedTorrentReading: Boolean,
        draftText: String,
        incomingText: String?,
        localReviewedFilePresent: Boolean,
        serverHandoffPresent: Boolean,
        remoteFileChosen: Boolean,
        localAttemptSubmitted: Boolean,
        remoteAlreadyAttempted: Boolean,
        protocolAttemptHeldForSelectedClient: Boolean
    ): Boolean = sharedTorrentReading ||
        (draftText.isNotBlank() && draftText != incomingText) || localReviewedFilePresent ||
        serverHandoffPresent || remoteFileChosen || localAttemptSubmitted || remoteAlreadyAttempted ||
        protocolAttemptHeldForSelectedClient

    /**
     * Whether a verified Local attempt's source may be released for a new source: only when the
     * Local target is selected, the attempt was submitted and verified, no original-document
     * handling (a pending or running deletion, or a reviewed Android document) still depends on
     * it, and the person has not started a new document review.
     */
    fun localVerifiedAttemptReleasable(
        localSelected: Boolean,
        attemptSubmitted: Boolean,
        newReviewedDocumentPresent: Boolean,
        originalDocumentDeletionPending: Boolean,
        originalDocumentDeleting: Boolean,
        verified: Boolean
    ): Boolean = localSelected && attemptSubmitted && !newReviewedDocumentPresent &&
        !originalDocumentDeletionPending && !originalDocumentDeleting && verified
}
