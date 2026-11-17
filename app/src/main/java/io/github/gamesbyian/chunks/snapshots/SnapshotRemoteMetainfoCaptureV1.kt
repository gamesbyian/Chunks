package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

/**
 * Upgrade an explicitly selected remote inventory export only when an
 * authenticated read-only source provider returns the exact original v1
 * metainfo. Every unavailable/mismatching definition remains inventory-only.
 * No guessed magnets, file payloads, server mutation or implicit source path.
 *
 * The original inventory revision/account must stay current throughout.
 * The overall raw-definition budget limits on-device memory use, independently
 * of the archive's stricter 64 MiB decompression/ZIP validation.
 */
internal object SnapshotRemoteMetainfoCaptureV1 {
    private const val MAX_EMBEDDED_BYTES = 32 * 1024 * 1024

    data class Result(
        val archive: ByteArray,
        val recovered: Int,
        val unavailable: Int,
        val sizeLimited: Int,
        val inspected: Int
    )

    suspend fun collect(
        capture: SnapshotInventoryCaptureV1.Capture,
        readOriginal: suspend (String) -> ByteArray?,
        stillCurrent: () -> Boolean = { true },
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result {
        require(capture.manifest.source == SnapshotManifestV1.Source.RTORRENT) {
            "Remote source capture requires a server inventory"
        }
        val items = ArrayList<SnapshotManifestV1.Item>(capture.manifest.items.size)
        val definitions = LinkedHashMap<String, ByteArray>()
        var unavailable = 0
        var tooLarge = 0
        var used = 0L
        val total = capture.manifest.items.size
        for ((index, item) in capture.manifest.items.withIndex()) {
            currentCoroutineContext().ensureActive()
            check(stillCurrent()) { "The server inventory or account changed during export" }
            val candidate = try {
                readOriginal(item.infoHashV1)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            check(stillCurrent()) { "The server inventory or account changed during export" }
            val preview = if (candidate == null) null else withContext(Dispatchers.Default) {
                runCatching { TorrentMetainfoReader.preview(candidate) }.getOrNull()
            }
            val verified = candidate != null && preview != null &&
                preview.infoHashV1 == item.infoHashV1 &&
                preview.name == item.originalName
            if (!verified) {
                unavailable++
                items += item
            } else if (used + candidate!!.size > MAX_EMBEDDED_BYTES) {
                // A real original can be large. Preserve its inventory record
                // rather than exhausting a phone's heap while building a ZIP.
                tooLarge++
                items += item
            } else {
                definitions[item.infoHashV1] = candidate!!.copyOf()
                used += candidate.size
                items += item.copy(recovery = SnapshotManifestV1.Recovery.METAINFO)
            }
            onProgress(index + 1, total)
        }
        check(stillCurrent()) { "The server inventory or account changed during export" }
        val complete = capture.manifest.copy(items = items)
        // ZIP encoding and SHA-256/metadata verification can be sizable.
        // Never block Compose's main thread while assembling the archive.
        val archive = withContext(Dispatchers.Default) {
            SnapshotManifestV1.encode(complete, definitions)
        }
        check(stillCurrent()) { "The server inventory or account changed during export" }
        return Result(archive, definitions.size, unavailable, tooLarge, total)
    }
}
