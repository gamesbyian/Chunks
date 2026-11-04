package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Advanced remote-list actions belong to its persistent Options destination.
 * When batch selection is already active, keep Exit selection available even
 * after Options closes. This is presentation only; the owning screen retains
 * every exact-session, stale-read and command-confirmation gate.
 */
@Composable
internal fun RemoteAdvancedListActions(
    optionsOpen: Boolean,
    selecting: Boolean,
    toolsOpen: Boolean,
    enabled: Boolean,
    onToggleSelection: () -> Unit,
    onToggleTools: () -> Unit
) {
    if (!optionsOpen && !selecting) return
    val wideText = LocalDensity.current.fontScale >= 1.5f
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onToggleSelection,
            enabled = enabled,
            modifier = (if (wideText || !optionsOpen) Modifier.fillMaxWidth()
                else Modifier.weight(1f)).heightIn(min = 52.dp)
        ) {
            Text(if (selecting) "Exit batch selection" else "Select transfers for a batch")
        }
        if (optionsOpen) OutlinedButton(
            onClick = onToggleTools,
            modifier = Modifier.heightIn(min = 52.dp)
                .semantics { stateDescription = if (toolsOpen) "Expanded" else "Collapsed" }
        ) { Text(if (toolsOpen) "Hide tools" else "More tools") }
    }
}
