package io.github.gamesbyian.chunks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.media.MediaKind
import io.github.gamesbyian.chunks.media.preferredArtworkRatio
import io.github.gamesbyian.chunks.media.MediaTransferKey
import io.github.gamesbyian.chunks.media.TransferMediaUiState
import io.github.gamesbyian.chunks.media.rememberTransferMedia
import io.github.gamesbyian.chunks.media.ui.TransferMediaArtwork
import io.github.gamesbyian.chunks.model.UnifiedTransferKey

/** Same footprint as the earlier format badge, so rows keep their compact density. */
internal val TransferRowArtworkSize: Dp = 40.dp
/** The optional "Larger artwork in lists" size, within TRANSFER-ARTWORK's 64–80dp row guidance. */
internal val TransferRowArtworkLargeSize: Dp = 64.dp

/**
 * Width follows the *drawn* image ratio, not a fixed thumbnail slot. Landscape art
 * is reduced proportionally when its natural width would starve the adjacent text.
 * The caller controls its height budget and the viewport-derived width cap.
 */
internal fun heightLedArtworkBounds(height: Dp, maxWidth: Dp, ratio: Float): Pair<Dp, Dp> {
    require(height.value > 0f && maxWidth.value > 0f)
    val validRatio = ratio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val width = minOf(height.value * validRatio, maxWidth.value)
    return width.dp to (width / validRatio).dp
}

/** "Larger artwork" increases the requested height, subject to each card's width/text cap. */
internal fun requestedListArtworkHeight(normalHeight: Dp, large: Boolean): Dp =
    if (large) normalHeight * 1.25f else normalHeight

/**
 * Read-only artwork for a transfer list row (local, rTorrent and Combined).
 *
 * Recognition always uses the [originalName] (never the user's display alias). Only local state is
 * read: an assignment the user made, a consented automatic match, or bundled category art. No
 * provider request is started here unless the user turned on automatic lookup, which the media
 * controller bounds itself. When [key] is unknown (ambiguous scope) or the source is [hidden] by a
 * privacy gate, only the name-derived format badge is shown, never identifying artwork.
 */
@Composable
internal fun TransferRowArtwork(
    key: UnifiedTransferKey?,
    originalName: String,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
    /** Height-led layout opt-in; omitted for legacy square rows. */
    height: Dp? = null,
    maxWidth: Dp = 112.dp
) {
    if (key == null || hidden) {
        TransferFileTypeBadge(originalName, modifier, height?.let { minOf(it, maxWidth) } ?: TransferRowArtworkSize)
        return
    }
    val mediaKey = remember(key) { MediaTransferKey.of(key) }
    val state by rememberTransferMedia(mediaKey, originalName)
    val format = remember(originalName) { transferNameFormatHint(originalName) }
    val description = transferRowArtworkDescription(state, format)
    val large = io.github.gamesbyian.chunks.media.LocalTransferMediaController.current
        ?.largeListArtwork?.collectAsState()?.value == true
    val baseSize = if (large) TransferRowArtworkLargeSize else TransferRowArtworkSize
    val bitmap = state.artwork?.takeIf { state.mayShowRealArt }
    val ratio = bitmap?.let { it.width.toFloat() / it.height.toFloat() }
        ?: state.displayKind.preferredArtworkRatio
    val bounds = height?.let {
        heightLedArtworkBounds(requestedListArtworkHeight(it, large), maxWidth, ratio)
    } ?: (baseSize to baseSize)
    TransferMediaArtwork(state, baseSize, modifier = modifier, description = description,
        width = bounds.first, height = bounds.second)
}

/** TalkBack text: what is drawn and its provenance first (never a provider title for adult content), then the format hint. */
internal fun transferRowArtworkDescription(state: TransferMediaUiState, format: TransferNameFormatHint): String {
    // Describe what is actually drawn: until an image is loaded (or when artwork is off) that is category art.
    val showingImage = state.mayShowRealArt && state.artwork != null
    val art = when {
        state.displayKind == MediaKind.ADULT -> "Generic artwork"
        !showingImage && state.displayKind == MediaKind.UNKNOWN -> "Generic artwork"
        !showingImage -> state.displayKind.label + " category artwork"
        state.userArtwork != null -> "Your artwork"
        state.match != null -> "Artwork: " + state.match!!.title + " (" + state.state.label.lowercase() + ")"
        state.displayKind == MediaKind.UNKNOWN -> "Generic artwork"
        else -> state.displayKind.label + " category artwork"
    }
    return "$art. ${format.explanation}"
}

/** Exact local identity; null for a malformed hash, which then shows only the format badge. */
internal fun localTransferKey(infoHash: String): UnifiedTransferKey? =
    runCatching { UnifiedTransferKey.of(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL, infoHash) }.getOrNull()

/** Exact authenticated server identity; null when the account scope is ambiguous. */
internal fun remoteTransferKey(environmentId: String?, infoHash: String): UnifiedTransferKey? =
    environmentId?.let { id -> runCatching { UnifiedTransferKey.of(io.github.gamesbyian.chunks.model.EnvironmentId(id), infoHash) }.getOrNull() }

@Composable
internal fun rememberLocalTransferKey(infoHash: String): UnifiedTransferKey? =
    remember(infoHash) { localTransferKey(infoHash) }

@Composable
internal fun rememberRemoteTransferKey(environmentId: String?, infoHash: String): UnifiedTransferKey? =
    remember(environmentId, infoHash) { remoteTransferKey(environmentId, infoHash) }
