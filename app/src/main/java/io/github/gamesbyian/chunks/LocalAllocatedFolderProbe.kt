package io.github.gamesbyian.chunks

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * An observational snapshot of an app-owned POSIX download directory.
 *
 * st_blocks counts 512-byte disk blocks, not logical file length. Repeated
 * (device,inode) hardlinks inside the folder contribute allocation just once.
 * This is NOT a deletion authorization or a guarantee of reclaimable blocks:
 * providers, concurrent writers, reflinks, filesystem compression and links
 * elsewhere may change the true physical accounting.
 */
internal data class LocalAllocatedInventory(
    val allocatedBytes: Long?,
    val logicalBytes: Long?,
    val regularPaths: Int,
    val uniqueInodes: Int,
    val complete: Boolean,
    val externalHardlinkPossible: Boolean
)

internal object LocalAllocatedFolderProbe {
    const val MAX_ENTRIES = 50_000
    private const val MAX_DEPTH = 32
    /** Never traverses symlinks or leaves the supplied directory. */
    fun measure(folder: File?, maxEntries: Int = MAX_ENTRIES): LocalAllocatedInventory {
        require(maxEntries > 0)
        val empty = LocalAllocatedInventory(null, null, 0, 0, false, false)
        if (folder == null) return empty
        val root = folder.toPath()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return empty
        val rootDevice = try {
            val stat = Os.lstat(root.toString())
            if (!OsConstants.S_ISDIR(stat.st_mode)) return empty
            stat.st_dev
        } catch (_: Exception) {
            return empty
        }

        var count = 0
        var paths = 0
        var complete = true
        val accounting = LocalInodeAccounting()
        try {
            Files.walkFileTree(root, emptySet<FileVisitOption>(), MAX_DEPTH,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        count++
                        if (count > maxEntries) {
                            complete = false
                            return FileVisitResult.TERMINATE
                        }
                        if (dir != root) {
                            try {
                                val dirStat = Os.lstat(dir.toString())
                                if (!OsConstants.S_ISDIR(dirStat.st_mode) ||
                                    dirStat.st_dev != rootDevice) {
                                    // A nested mount or swapped directory
                                    // cannot count against this volume.
                                    complete = false
                                    return FileVisitResult.SKIP_SUBTREE
                                }
                            } catch (_: Exception) {
                                complete = false
                                return FileVisitResult.SKIP_SUBTREE
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        count++
                        if (count > maxEntries) {
                            complete = false
                            return FileVisitResult.TERMINATE
                        }
                        if (!attrs.isRegularFile) {
                            // Symlinks, special files and a depth-limited
                            // directory make the full inventory untrustworthy.
                            complete = false
                            return FileVisitResult.CONTINUE
                        }
                        paths++
                        try {
                            val stat = Os.lstat(file.toString())
                            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_dev != rootDevice ||
                                stat.st_size < 0L ||
                                stat.st_blocks < 0L || stat.st_nlink < 1L ||
                                attrs.size() != stat.st_size) {
                                // NIO enumeration and lstat disagreed: a
                                // concurrent writer/rename may have raced.
                                complete = false
                                return FileVisitResult.CONTINUE
                            }
                            if (!accounting.add(stat.st_dev, stat.st_ino,
                                    stat.st_size, stat.st_blocks, stat.st_nlink)) complete = false
                        } catch (_: Exception) {
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
        // st_nlink may also include links OUTSIDE this folder. Those blocks
        // are observed here but cannot be promised as reclaimable by deletion.
        return LocalAllocatedInventory(accounting.allocatedBytes, accounting.logicalBytes, paths,
            accounting.uniqueInodes, complete, accounting.outsideHardlinksPossible)
    }
}
