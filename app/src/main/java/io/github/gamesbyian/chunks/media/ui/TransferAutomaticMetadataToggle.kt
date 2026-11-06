package io.github.gamesbyian.chunks.media.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
        value = state.automaticLookupEnabled, enabled = !saving, role = Role.Checkbox,
        onValueChange = { enabled ->
            saving = true
            scope.launch {
                try { withContext(Dispatchers.IO) { controller.setAutomaticLookupEnabled(key, enabled) } }
                finally { saving = false }
            }
        }), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.automaticLookupEnabled, onCheckedChange = null, enabled = !saving)
        Text("Automatically identify and fetch artwork & metadata")
    }
}
