package io.github.gamesbyian.chunks.engine

import java.io.File

/**
 * Establish whether the private resume store is enumerable *before* starting
 * libtorrent. File.listFiles() returns null both when the path is not a
 * directory and when enumeration fails: neither is evidence of zero jobs.
 * This is a read-only inventory except for creating a previously absent
 * state directory, consistent with LocalTorrentEngine's existing behavior.
 */
internal object LocalResumeDirectoryInventory {
    fun prepare(
        directory: File,
        listFiles: (File) -> Array<File>? = { it.listFiles() }
    ): List<File> {
        val available = try {
            directory.isDirectory || directory.mkdirs()
        } catch (_: SecurityException) {
            false
        }
        check(available && directory.isDirectory) {
            "Engine state directory is unavailable; saved transfers were not inspected"
        }
        val entries = try {
            listFiles(directory)
        } catch (_: Exception) {
            null
        }
        check(entries != null) {
            "Engine state directory cannot be listed; saved transfers may exist"
        }
        return entries.sortedBy { it.name }
    }
}
