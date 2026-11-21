package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** RAM-only navigation identity. The selected target does not grant authentication or Add. */
internal sealed interface UniversalAddRouteTarget {
    data object Local : UniversalAddRouteTarget
    data class RTorrent(val accountScope: String) : UniversalAddRouteTarget
    data class Protocol(val profileId: String) : UniversalAddRouteTarget
}

@Composable
internal fun UniversalAddRoute(
    target: UniversalAddRouteTarget,
    localPane: LocalPane,
    local: @Composable (LocalPane) -> Unit,
    remote: (@Composable () -> Unit)?,
    protocol: (@Composable () -> Unit)?,
    destinationPicker: @Composable () -> Unit,
    onBack: () -> Unit
) {
    // Options can prepare a Local snapshot review before it opens Add.
    // Keep that adapter mounted across Local pane changes so it retains the
    // exact reviewed snapshot and journal. Remote UI is keyed by exact account.
    if (target == UniversalAddRouteTarget.Local) {
        local(localPane)
        return
    }
    key(target) {
        val clientContent = if (target is UniversalAddRouteTarget.RTorrent) remote else protocol
        if (clientContent != null) {
            clientContent()
        } else {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Add a transfer", style = MaterialTheme.typography.titleLarge)
                Text("Your Add source is retained. Reconnect to the selected client in Connections or choose another destination here.")
                destinationPicker()
                UniversalAddBackAction(onBack)
            }
        }
    }
}
