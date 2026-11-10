package io.github.gamesbyian.chunks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class FooterDestination { TRANSFERS, ADD, CONNECTIONS, OPTIONS }

/**
 * Persistent, measured navigation beneath the authenticated server inventory.
 * The parent owns the actual Transfers/Add/Connections/Options callbacks; this UI
 * cannot change a server or torrent. All destinations occupy one stable row
 * at 320dp and 200% font scale; labels may wrap but never disappear.
 * The parent measures this bar outside its scrollable content.
 */
@Composable
internal fun AuthenticatedTransfersFooter(
    /** Null when Options is a destination rather than an expandable panel. */
    optionsOpen: Boolean?,
    onTransfers: () -> Unit,
    onAdd: () -> Unit,
    onOptions: () -> Unit,
    /** A shared Connections destination, independent of current transfer environment. */
    onConnections: (() -> Unit)? = null,
    /** Supplied by the phone route; a null scope preserves existing remote labels. */
    environmentLabel: String? = null,
    /** Spoken name for Add where no single environment owns it (Combined). */
    addDescription: String? = null,
    /** Retained for independent keyboard regression tests; never hides navigation. */
    imeVisible: Boolean = false,
    activeDestination: FooterDestination = FooterDestination.TRANSFERS
) {
    Surface(color = ChunksColors.Surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FooterTransfers(onTransfers, Modifier.weight(1f), environmentLabel,
                FourItemPadding, compact = true, isCurrent = activeDestination == FooterDestination.TRANSFERS)
            FooterAdd(onAdd, Modifier.weight(1f), environmentLabel, addDescription,
                FourItemPadding, compact = true, isCurrent = activeDestination == FooterDestination.ADD)
            if (onConnections != null) {
                FooterConnections(onConnections, Modifier.weight(1f), FourItemPadding, compact = true,
                    isCurrent = activeDestination == FooterDestination.CONNECTIONS)
            }
            FooterOptions(optionsOpen, onOptions, Modifier.weight(1f), environmentLabel,
                FourItemPadding, compact = true, isCurrent = activeDestination == FooterDestination.OPTIONS)
        }
    }
}

private fun Modifier.currentDestination(isCurrent: Boolean): Modifier =
    this.then(if (isCurrent) Modifier.border(2.dp, ChunksColors.Text,
        RoundedCornerShape(16.dp)) else Modifier)
        .semantics { selected = isCurrent }

private val FourItemPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 1.dp, vertical = 6.dp)

/** Decorative strokes, not extra accessible labels or external icon dependencies. */
private enum class FooterGlyphType { TRANSFERS, ADD, CONNECTIONS, OPTIONS }

@Composable
private fun FooterGlyph(kind: FooterGlyphType) {
    val ink = LocalContentColor.current
    Canvas(Modifier.size(14.dp)) {
        val unit = size.width / 14f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(ink, Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit),
                strokeWidth = 1.6f * unit, cap = StrokeCap.Round)
        }
        when (kind) {
            FooterGlyphType.TRANSFERS -> {
                line(2f, 3f, 12f, 3f)
                line(2f, 7f, 12f, 7f)
                line(2f, 11f, 12f, 11f)
            }
            FooterGlyphType.ADD -> {
                line(7f, 2f, 7f, 12f)
                line(2f, 7f, 12f, 7f)
            }
            FooterGlyphType.CONNECTIONS -> {
                drawCircle(ink, radius = 2f * unit, center = Offset(3f * unit, 3f * unit))
                drawCircle(ink, radius = 2f * unit, center = Offset(11f * unit, 11f * unit))
                line(5f, 5f, 9f, 9f)
            }
            FooterGlyphType.OPTIONS -> {
                line(2f, 3f, 12f, 3f)
                line(2f, 7f, 12f, 7f)
                line(2f, 11f, 12f, 11f)
                drawCircle(ink, radius = 1.7f * unit, center = Offset(8f * unit, 3f * unit))
                drawCircle(ink, radius = 1.7f * unit, center = Offset(5f * unit, 7f * unit))
                drawCircle(ink, radius = 1.7f * unit, center = Offset(9f * unit, 11f * unit))
            }
        }
    }
}

@Composable
private fun FooterTransfers(
    onClick: () -> Unit, modifier: Modifier, environmentLabel: String?,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.material3.ButtonDefaults.ContentPadding,
    compact: Boolean = false,
    isCurrent: Boolean = false
) {
    OutlinedButton(onClick = onClick, contentPadding = contentPadding, modifier = modifier.heightIn(min = 48.dp)
        .currentDestination(isCurrent)
        .then(if (environmentLabel == null) Modifier else Modifier.semantics {
            contentDescription = "Show transfers on $environmentLabel"
        })) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FooterGlyph(FooterGlyphType.TRANSFERS)
            Text("Transfers", fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                fontSize = if (compact) 11.sp else 14.sp,
                maxLines = 3, textAlign = TextAlign.Center, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
private fun FooterAdd(
    onClick: () -> Unit, modifier: Modifier, environmentLabel: String?, description: String?,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.material3.ButtonDefaults.ContentPadding,
    compact: Boolean = false,
    isCurrent: Boolean = false
) {
    val spoken = description ?: environmentLabel?.let { "Add torrent on $it" }
    Button(onClick = onClick, contentPadding = contentPadding, modifier = modifier.heightIn(min = 48.dp)
        .currentDestination(isCurrent)
        .then(if (spoken == null) Modifier else Modifier.semantics {
            contentDescription = spoken
        })) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FooterGlyph(FooterGlyphType.ADD)
            Text("+ Add", fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                fontSize = if (compact) 11.sp else 14.sp,
                maxLines = 3, textAlign = TextAlign.Center, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
private fun FooterConnections(onClick: () -> Unit, modifier: Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.material3.ButtonDefaults.ContentPadding,
    compact: Boolean = false,
    isCurrent: Boolean = false) {
    OutlinedButton(onClick = onClick, contentPadding = contentPadding,
        modifier = modifier.heightIn(min = 48.dp)
        .currentDestination(isCurrent)
        .semantics { contentDescription = "Connections" }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FooterGlyph(FooterGlyphType.CONNECTIONS)
            Text("Connections", fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                fontSize = if (compact) 11.sp else 14.sp,
                maxLines = 3, textAlign = TextAlign.Center, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
private fun FooterOptions(
    open: Boolean?, onClick: () -> Unit, modifier: Modifier, environmentLabel: String?,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.material3.ButtonDefaults.ContentPadding,
    compact: Boolean = false,
    isCurrent: Boolean = false
) {
    OutlinedButton(
        contentPadding = contentPadding,
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp)
            .currentDestination(isCurrent)
            .semantics {
                contentDescription = if (environmentLabel == null) "Options"
                    else "Options on $environmentLabel"
                if (open != null) stateDescription = if (open) "Expanded" else "Collapsed"
            }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FooterGlyph(FooterGlyphType.OPTIONS)
            Text("Options", fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                fontSize = if (compact) 11.sp else 14.sp,
                maxLines = 3, textAlign = TextAlign.Center, overflow = TextOverflow.Clip)
        }
    }
}
