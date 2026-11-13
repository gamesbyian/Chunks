package io.github.gamesbyian.chunks

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Same responsive review/decline presentation for local Remove and
 * local-to-server Add review. Callbacks stay with the owning exact transfer
 * controller: this view never executes or retries commands by itself.
 */
@Composable
internal fun LocalTransferConfirmationActions(
    confirmLabel: String,
    cancelLabel: String,
    enabled: Boolean = true,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    ResponsiveActionGroup(maxColumns = 2) { actionModifier ->
        Button(onClick = onConfirm, enabled = enabled, modifier = actionModifier) {
            Text(confirmLabel)
        }
        OutlinedButton(onClick = onCancel, modifier = actionModifier) {
            Text(cancelLabel)
        }
    }
}
