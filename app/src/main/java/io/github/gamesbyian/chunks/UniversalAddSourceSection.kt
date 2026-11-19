package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * The ONE source-first intake block of Universal Add, identical for Local,
 * rTorrent, Transmission, qBittorrent and Deluge: a magnet or HTTPS torrent
 * link field, Open .torrent, an explicit clipboard Paste, and either the
 * HTTPS link review (fetched only when tapped) or the magnet check.
 *
 * It owns no source state and starts no IO: each client body supplies its
 * existing source/witness handlers, so the reviewed-source precedence,
 * stale-fetch refusal and one-Add fences stay where they are proven.
 * Nothing here authorizes an Add.
 */
internal object UniversalAddSourceText {
    const val FIELD_LABEL = "Magnet or torrent link"
    const val HEADING = "Torrent source"
    const val OPEN_TORRENT = "Open .torrent"
    const val NOTHING_TO_PASTE = "The clipboard has no text to paste. Copy a magnet or torrent link first."
}

@Composable
internal fun UniversalAddSourceSection(
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean,
    onOpenTorrent: () -> Unit,
    onFetchLink: (TorrentLinkCheck.Fetchable) -> Unit,
    onNothingToPaste: () -> Unit,
    modifier: Modifier = Modifier,
    /** A document is being read: Open .torrent stays visible but disabled. */
    readingTorrent: Boolean = false,
    linkFetching: Boolean = false,
    /** Paste replaces the draft through the same handler unless a client needs its own. */
    onPaste: (String) -> Unit = onTextChange,
    /** False while a reviewed .torrent (not the text) is the selected source. */
    textIsSelectedSource: Boolean = true,
    /** Client-neutral notes under the field (clipboard prefill note, etc.). */
    belowField: @Composable ColumnScope.() -> Unit = {}
) {
    val magnetCheck = remember(text) { MagnetDraftCheck.inspect(text) }
    // A web link is a different source: fetched only on request, then reviewed as a .torrent.
    val linkCheck = remember(text) { TorrentLinkCheck.inspect(text) }
    val isLink = linkCheck !is TorrentLinkCheck.NotALink
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(UniversalAddSourceText.HEADING, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            enabled = enabled,
            label = { Text(UniversalAddSourceText.FIELD_LABEL) },
            singleLine = true,
            isError = textIsSelectedSource && (if (isLink) linkCheck is TorrentLinkCheck.Problem
                else magnetCheck is MagnetDraftCheck.Problem),
            // Autocorrect must never rewrite a magnet's hash or trackers.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag(ChunksSemantics.ADD_SOURCE_FIELD)
        )
        // A stable label: reading is a disabled state with its own description, so the
        // one Open .torrent action never disappears while a document is read.
        ActionTile(UniversalAddSourceText.OPEN_TORRENT,
            ChunksColors.Green, onClick = onOpenTorrent,
            enabled = enabled && !readingTorrent,
            modifier = Modifier.fillMaxWidth().testTag(ChunksSemantics.ADD_OPEN_TORRENT)
                .semantics { if (readingTorrent) stateDescription = "Reading torrent" },
            icon = R.drawable.chunks_ic_add_torrent, stacked = false)
        PasteMagnetButton(enabled = enabled, onPaste = onPaste, onNothingToPaste = onNothingToPaste)
        belowField()
        if (textIsSelectedSource) {
            if (isLink) TorrentLinkPanel(linkCheck, fetching = linkFetching, enabled = enabled,
                onFetch = onFetchLink)
            else MagnetDraftSummary(magnetCheck)
        }
    }
}

/**
 * The one "Start when added" choice of Universal Add, identical for every
 * client (48dp row, checkbox semantics, ChunksSemantics.ADD_START_PREFERENCE).
 * It edits the draft only; the frozen ticket carries the reviewed value.
 */
@Composable
internal fun UniversalAddStartChoice(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag(ChunksSemantics.ADD_START_PREFERENCE)
            .toggleable(value = checked, enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        androidx.compose.material3.Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
        Text("Start when added", modifier = Modifier.padding(start = 8.dp))
    }
}
