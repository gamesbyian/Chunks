package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Best-effort, read-only enrichment of already verified definitions and exact-scope aliases. */
internal class SnapshotSettingsCapture(
    private val readAlias: (String) -> String?,
    private val readFiles: suspend (String) -> List<SnapshotFilePriorities.Observation>?
) {
    suspend fun collect(
        manifest: SnapshotManifestV1.Manifest,
        metainfo: Map<String, ByteArray>,
        stillCurrent: () -> Boolean = { true }
    ): SnapshotManifestV1.Manifest {
        var bytesLeft = SnapshotManifestV1.remainingManifestBytes(manifest)
        var filesLeft = SnapshotFilePriorities.MAX_TOTAL_FILES
        val items = manifest.items.sortedBy { it.infoHashV1 }.map { item ->
            currentCoroutineContext().ensureActive()
            check(stillCurrent()) { "Snapshot source changed during settings capture" }
            val hash = item.infoHashV1
            val alias = readOrUnavailable { readAlias(hash)?.let(SnapshotManifestV1::validateAlias) }
            val priorities = metainfo[hash]?.let { bytes ->
                val layout = TorrentMetainfoReader.preview(bytes)
                val contentCount = SnapshotFilePriorities.layout(layout).size
                if (contentCount in 1..minOf(SnapshotFilePriorities.MAX_FILES, filesLeft))
                    readOrUnavailable {
                        SnapshotFilePriorities.capture(layout, readFiles(hash), manifest.source)
                    }
                else null
            }
            check(stillCurrent()) { "Snapshot source changed during settings capture" }
            // Spend available manifest bytes without dropping valid definitions.
            val choices = listOf(
                SnapshotManifestV1.Settings(alias, priorities),
                SnapshotManifestV1.Settings(filePriorities = priorities),
                SnapshotManifestV1.Settings(displayAlias = alias)
            )
            for (settings in choices) {
                if (settings.displayAlias == null && settings.filePriorities == null) continue
                val cost = SnapshotManifestV1.encodeSettings(settings).toByteArray(Charsets.UTF_8).size
                if (cost <= bytesLeft) {
                    bytesLeft -= cost
                    filesLeft -= settings.filePriorities?.size ?: 0
                    return@map item.copy(settings = settings)
                }
            }
            item
        }
        return manifest.copy(items = items)
    }

    private suspend fun <T> readOrUnavailable(read: suspend () -> T?): T? = try {
        read()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }
}
