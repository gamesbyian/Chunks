package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Bounded read-only survey of Chunks' own on-phone download folder.
 *
 * Counts logical file sizes, including payload left behind after session-only
 * removal. This is NOT allocated filesystem blocks, unique inode usage, a
 * server quota, or a reliable measure of bytes currently owned by libtorrent.
 * Symlinks are NEVER followed; failures and size/entry limits make the
 * observation explicitly incomplete rather than implying a complete total.
 */
data class LocalFolderInventory(
    val logicalBytes: Long?,
    val regularFiles: Int,
    val complete: Boolean
)

object LocalFolderInventoryProbe {
    const val MAX_ENTRIES = 50_000
    private const val MAX_DEPTH = 32

    fun measure(folder: File?, maxEntries: Int = MAX_ENTRIES): LocalFolderInventory {
        require(maxEntries > 0)
        if (folder == null) return LocalFolderInventory(null, 0, false)
        val root = folder.toPath()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            return LocalFolderInventory(null, 0, false)

        var bytes: Long? = 0L
        var files = 0
        var entries = 0
        var complete = true
        try {
            Files.walkFileTree(root, emptySet<FileVisitOption>(), MAX_DEPTH,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        entries++
                        if (entries > maxEntries) {
                            complete = false
                            return FileVisitResult.TERMINATE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        entries++
                        if (entries > maxEntries) {
                            complete = false
                            return FileVisitResult.TERMINATE
                        }
                        if (attrs.isRegularFile) {
                            files++
                            bytes = bytes?.let {
                                try { Math.addExact(it, attrs.size()) }
                                catch (_: ArithmeticException) { null }
                            }
                            if (bytes == null) complete = false
                        } else {
                            // Symlinks, devices and depth-limited directories
                            // are not traversed or counted as regular files.
                            complete = false
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        complete = false
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) complete = false
                        return FileVisitResult.CONTINUE
                    }
                })
        } catch (_: IOException) {
            complete = false
        } catch (_: SecurityException) {
            complete = false
        }
        return LocalFolderInventory(bytes, files, complete)
    }
}
