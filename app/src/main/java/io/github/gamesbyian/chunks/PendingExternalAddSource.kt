package io.github.gamesbyian.chunks

import android.net.Uri

/**
 * Temporary, process-only source selection for Android Share/Open With.
 * An unrecognized or malformed new Intent is not a request to discard the
 * last valid, still-queued Add source. New *valid* sources retain the existing
 * explicit-source precedence, awaiting their normal Add destination review.
 *
 * This value never authorizes Add/Start and must never enter SavedState,
 * preferences, logs or a second submission coordinator.
 */
internal data class PendingExternalAddSource(
    val text: String?,
    val torrentDocument: Uri?
) {
    // Never expose private tracker queries or provider document URIs through
    // automatic Kotlin data-class rendering (including failed test diffs).
    override fun toString(): String = "PendingExternalAddSource(private)"

    fun withIncoming(candidate: ExternalAddSource?): PendingExternalAddSource =
        when (candidate) {
            null -> this
            is ExternalAddSource.Magnet -> PendingExternalAddSource(candidate.value, null)
            is ExternalAddSource.TorrentLink -> PendingExternalAddSource(candidate.value, null)
            is ExternalAddSource.TorrentDocument -> PendingExternalAddSource(null, candidate.uri)
        }
}
