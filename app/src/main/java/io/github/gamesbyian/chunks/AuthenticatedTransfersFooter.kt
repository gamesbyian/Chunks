package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Persistent, measured navigation beneath the authenticated server inventory.
 * The parent owns the actual Transfers/Add/Options callbacks; this UI cannot
 * choose another server or change the torrent. A two-row fallback preserves
 * readable labels at enlarged type instead of squeezing three actions into
 * equally narrow buttons. All touch targets stay at least 48dp.
 *
 * Always present, including with a soft keyboard, so switching destinations
 * never depends on the current focus, input method, or scroll position.
 * The parent measures this bar outside its scrollable content.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    imeVisible: Boolean = false
) {
    Surface(color = ChunksColors.Surface) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // At 412dp, 140% fonts already make "Transfers" wrap awkwardly
            // in a three-column row. Give Options its own row before that
            // threshold, without penalizing normal-scale everyday layouts.
            val stacked = maxWidth < 400.dp || LocalDensity.current.fontScale >= 1.3f
            if (stacked) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // An equal split broke "Transfers" mid-word at 320dp and 200%
                    // ("Transfe/rs"). Transfers takes its natural width and Add
                    // fills the rest; both keep 48dp height.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FooterTransfers(onTransfers, Modifier, environmentLabel, CompactFooterPadding)
                        FooterAdd(onAdd, Modifier.weight(1f), environmentLabel, addDescription, CompactFooterPadding)
                    }
                    if (onConnections != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FooterConnections(onConnections, Modifier.weight(1f))
                            FooterOptions(optionsOpen, onOptions, Modifier.weight(1f), environmentLabel)
                        }
                    } else FooterOptions(optionsOpen, onOptions, Modifier.fillMaxWidth(), environmentLabel)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onConnections != null) {
                        // Four equal, compact destinations at ordinary handset font size.
                        // The taller two-row variant above remains for narrow/large text.
                        FooterTransfers(onTransfers, Modifier.weight(1f), environmentLabel,
                            FourItemPadding, compact = true)
                        FooterAdd(onAdd, Modifier.weight(1f), environmentLabel, addDescription,
                            FourItemPadding, compact = true)
                        FooterConnections(onConnections, Modifier.weight(1f), FourItemPadding, compact = true)
                        FooterOptions(optionsOpen, onOptions, Modifier.weight(1f), environmentLabel,
                            FourItemPadding, compact = true)
                    } else {
                        FooterTransfers(onTransfers, Modifier.weight(1f), environmentLabel)
                        FooterAdd(onAdd, Modifier.weight(1f), environmentLabel, addDescription)
                        FooterOptions(optionsOpen, onOptions, Modifier.weight(1f), environmentLabel)
                    }
                }
            }
        }
    }
}

private val CompactFooterPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
private val FourItemPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 8.dp)

@Composable
private fun FooterTransfers(
    onClick: () -> Unit, modifier: Modifier, environmentLabel: String?,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.material3.ButtonDefaults.ContentPadding,
    compact: Boolean = false
) {
    OutlinedButton(onClick = onClick, contentPadding = contentPadding, modifier = modifier.heightIn(min = 48.dp)
        .then(if (environmentLabel == null) Modifier else Modifier.semantics {
            contentDescription = "Show transfers on $environmentLabel"
        })) {
        Text("Transfers", fontSize = if (compact) 12.sp else 14.sp,
            maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FooterAdd(
    onClick: () -> Unit, modifier: Modifier, environmentLabel: String?, description: String?,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.material3.ButtonDefaults.ContentPadding,
    compact: Boolean = false
) {
    val spoken = description ?: environmentLabel?.let { "Add torrent on $it" }
    Button(onClick = onClick, contentPadding = contentPadding, modifier = modifier.heightIn(min = 48.dp)
        .then(if (spoken == null) Modifier else Modifier.semantics {
            contentDescription = spoken
        })) {
        Text("+ Add", fontSize = if (compact) 12.sp else 14.sp,
            maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FooterConnections(onClick: () -> Unit, modifier: Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.material3.ButtonDefaults.ContentPadding,
    compact: Boolean = false) {
    OutlinedButton(onClick = onClick, contentPadding = contentPadding,
        modifier = modifier.heightIn(min = 48.dp)
        .semantics { contentDescription = "Connections" }) {
        Text("Connections", fontSize = if (compact) 12.sp else 14.sp,
            maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FooterOptions(
    open: Boolean?, onClick: () -> Unit, modifier: Modifier, environmentLabel: String?,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.material3.ButtonDefaults.ContentPadding,
    compact: Boolean = false
) {
    OutlinedButton(
        contentPadding = contentPadding,
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp)
            .semantics {
                contentDescription = if (environmentLabel == null) "Options"
                    else "Options on $environmentLabel"
                if (open != null) stateDescription = if (open) "Expanded" else "Collapsed"
            }
    ) {
        Text("Options", fontSize = if (compact) 12.sp else 14.sp,
            maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
    }
}
