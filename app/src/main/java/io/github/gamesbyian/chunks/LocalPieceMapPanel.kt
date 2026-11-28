package io.github.gamesbyian.chunks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.engine.LocalVerifiedPieceMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** On-demand, exact-identity Local diagnostic. The textual ranges are an
 * accessible equivalent of the bounded visual map; no per-piece UI nodes. */
@Composable
internal fun LocalPieceMapPanel(status: LocalTransferStatus, engine: () -> LocalDetailsPort?) {
    // A same-hash replacement or relocation must not inherit an old observation
    // or outstanding coroutine. Scope is disposed when the reviewed key changes.
    androidx.compose.runtime.key(status.infoHash, status.nativeReviewToken, status.savePath) {
        val scope = rememberCoroutineScope()
        var expanded by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var observed by remember { mutableStateOf<LocalVerifiedPieceMap?>(null) }
        var failed by remember { mutableStateOf(false) }
        var page by remember { mutableStateOf(0) }
        fun read() {
            if (busy) return
            expanded = true
            busy = true
            failed = false
            observed = null
            page = 0
            scope.launch {
                try {
                    observed = runInterruptible(Dispatchers.IO) {
                        engine()?.verifiedPieceMap(status.infoHash, status.nativeReviewToken, status.savePath)
                    }
                    failed = observed == null
                } finally { busy = false }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (expanded) { expanded = false; observed = null; failed = false }
                else read()
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (expanded) "Hide verified piece map" else "Review verified piece map…")
            }
            if (!expanded) return@Column
            Text("Piece order in the torrent, not download history. Green is hash-verified; the remaining portion is not confirmed verified. Downloaded blocks, requests, peer availability and skipped/shared-boundary pieces are not distinguished by this observation.",
                style = MaterialTheme.typography.bodySmall)
            if (busy) Text("Reading native piece information…")
            if (failed) Text("Current verified-piece information is unavailable or exceeds the 65,536-piece review limit.",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            observed?.let { map ->
                Text("At last review: " + map.verifiedPieces + " of " + map.totalPieces + " pieces verified.",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text("Each cell represents up to " + map.ranges.maxOf { it.count } +
                    " consecutive pieces; exact verified count, not a sample.",
                    style = MaterialTheme.typography.bodySmall)
                val rows = (map.ranges.size + 7) / 8
                val notConfirmed = MaterialTheme.colorScheme.outlineVariant
                val verified = ChunksColors.Green
                Canvas(Modifier.fillMaxWidth().height((rows * 18).dp)) {
                    val width = size.width / 8f
                    val height = size.height / rows
                    map.ranges.forEachIndexed { index, range ->
                        val left = (index % 8) * width
                        val top = (index / 8) * height
                        val gap = minOf(width, height) * 0.12f
                        val cell = Size(width - gap, height - gap)
                        drawRect(notConfirmed, Offset(left, top), cell)
                        if (range.verified > 0) drawRect(verified, Offset(left, top),
                            Size(cell.width * range.verified / range.count, cell.height))
                    }
                }
                val lastPage = (map.ranges.size - 1) / 8
                map.ranges.drop(page * 8).take(8).forEach { range ->
                    val label = if (range.count == 1) "Piece " + (range.first + 1)
                        else "Pieces " + (range.first + 1) + "–" + (range.first + range.count)
                    Text(label + ": " + range.verified + " of " + range.count + " verified",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (lastPage > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { page-- }, enabled = page > 0,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Previous ranges") }
                    OutlinedButton(onClick = { page++ }, enabled = page < lastPage,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Next ranges") }
                }
            }
            OutlinedButton(onClick = ::read, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Refresh piece map") }
        }
    }
}
