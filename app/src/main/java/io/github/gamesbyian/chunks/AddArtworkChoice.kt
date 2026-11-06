package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** One shared per-Add choice. Expanding or changing it never starts a lookup. */
@Composable
internal fun AddArtworkChoice(automatic: Boolean, enabled: Boolean, onAutomatic: (Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }) { Text("Artwork & metadata") }
        if (expanded) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(automatic, enabled = enabled, role = Role.Checkbox, onValueChange = onAutomatic),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = automatic, onCheckedChange = null, enabled = enabled)
                Text("Automatically identify and fetch artwork & metadata", Modifier.padding(start = 8.dp))
            }
            Text("Uses your provider consent and privacy settings. Turning this off excludes this exact transfer before Add. " +
                "Existing exclusions stay off; change them later in Transfer Details.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
