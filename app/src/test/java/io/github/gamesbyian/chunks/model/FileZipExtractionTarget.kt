package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
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


/** Test-only legacy named journal. It MUST NOT be used by production filesystem code. */
/** Append-only, fsynced, percent-encoded journal file for one operation. */
class FileZipExtractionJournal(private val file: File) : ZipExtractionJournal {
    override fun append(fields: List<String>) {
        val line = fields.joinToString("\t") { URLEncoder.encode(it, "UTF-8") } + "\n"
        FileOutputStream(file, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    override fun read(): List<List<String>> {
        if (!file.isFile) return emptyList()
        // A torn final line from a crash is ignored rather than misparsed.
        val text = file.readText(Charsets.UTF_8)
        val complete = if (text.endsWith("\n")) text else text.substringBeforeLast("\n", "")
        return complete.lineSequence().filter { it.isNotEmpty() }.map { line ->
            line.split("\t").map { URLDecoder.decode(it, "UTF-8") }
        }.toList()
    }
}
