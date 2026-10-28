package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Always shows the resolved default; changing it is optional. Only Places
 * that passed this session's write probe can be chosen, and a refused Place
 * shows why rather than disappearing.
 */
@Composable
internal fun LocalDownloadPlacePicker(
    resolution: LocalPlaceResolution?,
    enabled: Boolean,
    onChoose: (LocalPlaceChoice) -> Unit,
    onRequestStoragePermission: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val selected = resolution?.selected
        when {
            resolution == null -> Text("Checking where this phone can save downloads…")
            selected == null -> Text("No phone download location is available.",
                color = MaterialTheme.colorScheme.error)
            else -> {
                Text("Saves to: " + selected.title, fontWeight = FontWeight.Bold)
                Text(selected.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
        resolution?.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        // Android 10 only: an explicit, user-started request. Granting it lets
        // the regular write probe decide; it is never asked for on its own.
        if (resolution != null && resolution.choices.any { it.needsStoragePermission }) {
            TextButton(onClick = onRequestStoragePermission, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Allow storage access for Downloads/" + LocalDownloadPlaces.PUBLIC_FOLDER_NAME)
            }
        }
        if (resolution != null && resolution.choices.size > 1) {
            TextButton(onClick = { expanded = !expanded }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (expanded) "Done choosing location" else "Change download location")
            }
        }
        if (expanded && resolution != null) {
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                resolution.choices.forEach { choice ->
                    val isSelected = choice.place.kind == selected?.kind
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                            selected = isSelected,
                            enabled = enabled && choice.verified,
                            role = Role.RadioButton,
                            onClick = { onChoose(choice) }
                        ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSelected, onClick = null,
                            enabled = enabled && choice.verified)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(choice.place.title)
                            Text(choice.failure?.let { "Unavailable: $it" } ?: choice.place.detail,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("Your choice is remembered for new phone Adds only. Existing transfers " +
                "keep their files where they are.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
