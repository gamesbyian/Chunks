package io.github.gamesbyian.chunks.media.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.media.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Exact-job preference; global provider consent remains in Options. */
@Composable
fun TransferAutomaticMetadataToggle(key: MediaTransferKey, originalName: String) {
    val context = LocalContext.current
    val controller = LocalTransferMediaController.current ?: remember(context) { ChunksMedia.get(context).controller }
    val state by rememberTransferMedia(key, originalName, controller = controller)
    val scope = rememberCoroutineScope()
    var saving by remember(key) { mutableStateOf(false) }
    var saveFailed by remember(key) { mutableStateOf(false) }
    Column {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
        value = state.automaticLookupEnabled, enabled = !saving, role = Role.Checkbox,
        onValueChange = { enabled ->
            saving = true
            saveFailed = false
            scope.launch {
                try { saveFailed = !withContext(Dispatchers.IO) { controller.setAutomaticLookupEnabled(key, enabled) } }
                finally { saving = false }
            }
        }), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.automaticLookupEnabled, onCheckedChange = null, enabled = !saving)
        Text("Automatically identify and fetch artwork & metadata")
    }
    if (saveFailed) Text("Could not save this preference. Background lookup is paused.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}
