package io.github.gamesbyian.chunks

/**
 * A verified .torrent stays in the draft while another text candidate is being
 * checked, but it may only be submitted when it is the active source again.
 * A typed URL/magnet has source precedence even if malformed or unreachable.
 * Clearing that candidate is the explicit way to reactivate prior metainfo.
 */
internal fun phoneReviewedMetainfoIsCurrent(sourceText: String, reading: Boolean): Boolean =
    sourceText.isBlank() && !reading
