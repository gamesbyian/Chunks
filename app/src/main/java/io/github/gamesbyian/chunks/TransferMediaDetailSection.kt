package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.media.MediaTransferKey
import io.github.gamesbyian.chunks.media.ui.TransferMediaIdentifyRoute
import io.github.gamesbyian.chunks.media.ui.TransferMediaSummaryRoute
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation

/**
 * Read-only media decoration and a user-requested identification editor for Combined details.
 * The caller must exclude privacy-locked observations. Never opens remote payloads, changes a
 * torrent, or sends a provider request simply because this section appears.
 */
@Composable
internal fun TransferMediaDetailSection(
    transfer: UnifiedTransferObservation,
    modifier: Modifier = Modifier
) = TransferMediaDetailSection(transfer.key, transfer.originalName, modifier)

/**
 * The same section for the phone and rTorrent list cards' expanded details, keyed by the exact
 * environment + infohash so an assignment made here is the one Combined shows, and vice versa.
 * [originalName] is the torrent's own name, never the user's display alias.
 */
@Composable
internal fun TransferMediaDetailSection(
    transferKey: io.github.gamesbyian.chunks.model.UnifiedTransferKey,
    originalName: String,
    modifier: Modifier = Modifier,
    /** Completed phone files only; null for remote or Combined observations. */
    localFiles: LocalCompletedMediaFiles? = null
) {
    val key = MediaTransferKey.of(transferKey)
    var editing by rememberSaveable(key.value) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Artwork & media information", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        io.github.gamesbyian.chunks.media.ui.TransferAutomaticMetadataToggle(key, originalName)
        TransferMediaSummaryRoute(
            key = key,
            originalName = originalName,
            onChange = { editing = !editing }
        )
        if (editing) {
            OutlinedButton(
                onClick = { editing = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text("Close media identification") }
            // Remote listings never get a read callback: an rTorrent listing does not establish
            // device-side permission to read a payload. Phone cards pass only verified completed files.
            if (localFiles == null) TransferMediaIdentifyRoute(key = key, originalName = originalName)
            else TransferMediaIdentifyRoute(key = key, originalName = originalName, files = localFiles.hints,
                readLocalFile = localFiles::read, openLocalFile = localFiles::open)
        }
    }
}
