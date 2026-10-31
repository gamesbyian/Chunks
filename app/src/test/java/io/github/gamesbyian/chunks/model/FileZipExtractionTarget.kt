package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Intentionally UNSAFE legacy characterization fixture for JVM model tests
 * only. Reopens created names for writing and deletes by pathname; these
 * operations cannot establish creation-owned identity against substitution.
 *
 * Never copy into app/src/main or use as a release/storage executor. Real
 * Android SAF publication remains blocked until #809 ownership acceptance.
 */
class FileZipExtractionTarget(override val root: File) : ZipExtractionTarget<File> {
    init { require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "Target root must be a real directory" } }

    override fun child(parent: File, name: String): File? {
        val file = File(parent, name)
        return if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) file else null
    }
    override fun isDirectory(node: File) = Files.isDirectory(node.toPath(), LinkOption.NOFOLLOW_LINKS)
    override fun hasChildren(directory: File) = Files.newDirectoryStream(directory.toPath()).use { it.iterator().hasNext() }
    override fun createDirectory(parent: File, name: String): File? = try {
        Files.createDirectory(File(parent, name).toPath()).toFile()
    } catch (_: FileAlreadyExistsException) { null }
    override fun createFile(parent: File, name: String): File? = try {
        Files.createFile(File(parent, name).toPath()).toFile()
    } catch (_: FileAlreadyExistsException) { null }
    override fun openNewOutput(file: File): OutputStream = object : FileOutputStream(file) {
        override fun close() {
            try { fd.sync() } finally { super.close() }
        }
    }
    override fun openExistingInput(file: File): InputStream? =
        if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) FileInputStream(file) else null
    override fun rename(node: File, parent: File, newName: String): File? = try {
        // No ATOMIC_MOVE: on Linux it may silently replace an existing target.
        Files.move(node.toPath(), File(parent, newName).toPath(), LinkOption.NOFOLLOW_LINKS).toFile()
    } catch (_: FileAlreadyExistsException) { null }
    override fun delete(node: File): Boolean = try { Files.deleteIfExists(node.toPath()) } catch (_: IOException) { false }
    override fun availableBytes(): Long? = root.usableSpace.takeIf { it > 0 }
}
