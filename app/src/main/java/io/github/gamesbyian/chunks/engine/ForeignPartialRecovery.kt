package io.github.gamesbyian.chunks.engine

import java.io.File
import org.libtorrent4j.TorrentInfo

/**
 * Metadata-aware entry point for foreign partial recovery. Derives the exact
 * declared payload paths (pad files excluded, same view as the engine's Add
 * survey) and delegates to the read-only scanner / reversible adoption.
 *
 * Adoption is an explicit user-approved filesystem rename; after it the normal
 * `addStoppedChecked` + Start path performs libtorrent's real hash check, so
 * nothing here ever claims progress.
 */
internal object ForeignPartialRecovery {
    fun declaredPaths(torrentBytes: ByteArray): List<String>? = try {
        val info = TorrentInfo.bdecode(torrentBytes)
        if (!info.isValid) null else {
            val files = info.files()
            val count = files.numFiles()
            if (!LocalPayloadCollisionSurvey.supportsNativeFileCount(count)) null
            else (0 until count).filterNot { files.padFileAt(it) }.map { files.filePath(it) }
        }
    } catch (_: Exception) { null }

    fun scan(torrentBytes: ByteArray, saveDirectory: File): ForeignPartialLayoutScanner.Result {
        val paths = declaredPaths(torrentBytes)
            ?: return ForeignPartialLayoutScanner.Result.Unknown("Torrent file paths could not be inspected")
        return ForeignPartialLayoutScanner.scan(saveDirectory, paths)
    }
}
