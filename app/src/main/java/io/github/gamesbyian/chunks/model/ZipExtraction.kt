package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * A destination the user explicitly approved (an Android SAF tree, or a plain
 * directory in tests). Implementations must be conservative:
 * - [createDirectory]/[createFile] return null if the name already exists or the
 *   provider would substitute a different name. They never reuse existing nodes.
 * - [rename] returns null rather than replacing an existing sibling.
 * - [delete] removes one file or one *empty* directory, never recursively.
 */
interface ZipExtractionTarget<N : Any> {
    val root: N
    fun child(parent: N, name: String): N?
    fun isDirectory(node: N): Boolean
    fun hasChildren(directory: N): Boolean
    fun createDirectory(parent: N, name: String): N?
    fun createFile(parent: N, name: String): N?
    fun openNewOutput(file: N): OutputStream
    /** Reopen bytes physically stored by the provider for verification before publish. */
    fun openExistingInput(file: N): InputStream? = null
    fun rename(node: N, parent: N, newName: String): N?
    fun delete(node: N): Boolean
    /** Free bytes on the destination volume, or null when the provider cannot say. */
    fun availableBytes(): Long?
}

/** Write-ahead operation log in app-private storage. Lines must be durable on return. */
interface ZipExtractionJournal {
    fun append(fields: List<String>)
    fun read(): List<List<String>>
}

enum class ZipExtractionStatus {
    /** Every member was written, re-verified while writing and published in a new folder. */
    EXTRACTED,
    /** Refused before creating anything in the destination. */
    BLOCKED,
    /** User cancelled; created partial output was removed. */
    CANCELLED,
    /** Writing failed; created partial output was removed. */
    FAILED_ROLLED_BACK,
    /** Partial output could not be fully removed or publication is uncertain. Nothing is retried. */
    NEEDS_ATTENTION
}

data class ZipExtractionResult(
    val status: ZipExtractionStatus,
    val operationId: String?,
    val folderName: String?,
    val writtenFiles: Int,
    val writtenBytes: Long,
    val reason: String?
)

data class ZipExtractionRequest(
    /** App-private copy whose bytes cannot change via the source document provider. */
    val stagedZip: File,
    /** Human label of the archive; used only to propose a new folder name. */
    val archiveLabel: String,
    /** Opaque, nonsecret identifier the caller can later resolve back to the target. */
    val targetId: String,
    val limits: ZipPreflightLimits = ZipPreflightLimits(),
    val maxExpandedBytes: Long = ZipPayloadVerifier.DEFAULT_MAX_EXPANDED_BYTES,
    /** True only after the user saw that destination free space is unknown. */
    val allowUnknownFreeSpace: Boolean = false
)

/**
 * Manual, bounded ZIP extraction into a **new** folder inside an approved target.
 *
 * Sequence: read-only verification pass, headroom check, write-ahead journal,
 * stream every member into a randomly named partial folder while re-checking
 * actual bytes, ratio and CRC, then a single non-replacing rename to a fresh
 * folder name. Existing files and folders are never opened for writing, merged,
 * replaced or deleted. The source archive is only read.
 */
object ZipExtractor {
    const val PARTIAL_PREFIX = ".chunks-partial-"
    private const val MAX_SEGMENT_UTF8_BYTES = 255
    private const val MAX_NAME_ATTEMPTS = 50

    fun <N : Any> extract(
        request: ZipExtractionRequest,
        target: ZipExtractionTarget<N>,
        journal: ZipExtractionJournal,
        cancelled: () -> Boolean = { false },
        operationId: String = UUID.randomUUID().toString().replace("-", "")
    ): ZipExtractionResult {
        require(operationId.matches(Regex("^[a-f0-9]{8,64}$"))) { "Invalid operation id" }
        val verification = ZipPayloadVerifier.verify(request.stagedZip, request.limits, request.maxExpandedBytes)
        if (verification.status != ZipPayloadStatus.VERIFIED_READ_ONLY)
            return blocked(verification.reason ?: "ZIP did not pass verification")
        val expected = verification.actualOutputBytes ?: return blocked("ZIP size is unknown")

        val free = try { target.availableBytes() } catch (_: Exception) { null }
        if (free == null && !request.allowUnknownFreeSpace)
            return blocked("Destination free space is unknown")
        if (free != null) {
            val reserve = maxOf(16L * 1024 * 1024, expected / 20)
            if (free < 0 || expected > free - minOf(free, reserve))
                return blocked("Not enough free space at the destination for " + expected + " bytes plus a safety reserve")
        }

        val plan = (try { plan(request) } catch (_: IOException) { null })
            ?: return blocked("ZIP data was unreadable or incomplete")
        plan.problem?.let { return blocked(it) }
        if (cancelled()) return ZipExtractionResult(ZipExtractionStatus.CANCELLED, null, null, 0, 0, "Cancelled before writing")

        val partialName = PARTIAL_PREFIX + operationId
        val baseName = folderBaseName(request.archiveLabel)
        if (target.child(target.root, partialName) != null)
            return blocked("A partial folder for this operation already exists")

        journal.append(listOf("BEGIN", operationId, request.targetId, partialName, baseName))
        val partial = (try { target.createDirectory(target.root, partialName) } catch (_: Exception) { null })
            ?: return finish(journal, target, partialName, operationId,
                ZipExtractionStatus.FAILED_ROLLED_BACK, "Could not create a new folder at the destination", 0, 0)

        var files = 0
        var bytes = 0L
        val failure: Pair<ZipExtractionStatus, String>? = try {
            writeMembers(request, plan, target, partial, journal, cancelled) { f, b -> files = f; bytes = b }
        } catch (_: IOException) {
            ZipExtractionStatus.FAILED_ROLLED_BACK to "Writing to the destination failed"
        } catch (_: SecurityException) {
            ZipExtractionStatus.FAILED_ROLLED_BACK to "Destination access was revoked"
        } catch (_: RuntimeException) {
            ZipExtractionStatus.FAILED_ROLLED_BACK to "The destination provider failed unexpectedly"
        }
        if (failure != null)
            return finish(journal, target, partialName, operationId, failure.first, failure.second, files, bytes)
        if (files != verification.verifiedFiles || bytes != expected)
            return finish(journal, target, partialName, operationId, ZipExtractionStatus.FAILED_ROLLED_BACK,
                "Written output differs from the verified archive", files, bytes)

        return publish(target, journal, partial, partialName, baseName, operationId, files, bytes, cancelled)
    }

    /**
     * Publication: one non-replacing rename of the finished partial folder. A
     * provider refusing every free name leaves the partial folder for rollback,
     * never a merged folder. Shared with [LibraryCopier].
     */
    internal fun <N : Any> publish(
        target: ZipExtractionTarget<N>,
        journal: ZipExtractionJournal,
        partial: N,
        partialName: String,
        baseName: String,
        operationId: String,
        files: Int,
        bytes: Long,
        cancelled: () -> Boolean
    ): ZipExtractionResult {
        for (attempt in 1..MAX_NAME_ATTEMPTS) {
            if (cancelled())
                return finish(journal, target, partialName, operationId, ZipExtractionStatus.CANCELLED,
                    "Cancelled before publishing", files, bytes)
            val candidate = if (attempt == 1) baseName else "$baseName ($attempt)"
            if (target.child(target.root, candidate) != null) continue
            journal.append(listOf("PUBLISH_INTENT", candidate))
            val published = try { target.rename(partial, target.root, candidate) } catch (_: Exception) { null }
            val readBack = target.child(target.root, candidate)
            val stillPartial = target.child(target.root, partialName)
            if (published != null && readBack != null && stillPartial == null && target.isDirectory(readBack)) {
                journal.append(listOf("PUBLISHED", candidate))
                return ZipExtractionResult(ZipExtractionStatus.EXTRACTED, operationId, candidate, files, bytes, null)
            }
            if (stillPartial == null)
                return needsAttention(journal, operationId, files, bytes,
                    "Publishing the extracted folder could not be confirmed")
            // Partial folder still in place: the name was taken concurrently or refused.
        }
        return finish(journal, target, partialName, operationId, ZipExtractionStatus.FAILED_ROLLED_BACK,
            "No free folder name was accepted by the destination", files, bytes)
    }

    /**
     * After process death, resolve an unfinished journal without retrying the
     * extraction: confirm a completed publish, or remove *only* journaled entries.
     */
    fun <N : Any> reconcile(target: ZipExtractionTarget<N>, journal: ZipExtractionJournal): ZipExtractionResult {
        val lines = journal.read()
        val begin = lines.firstOrNull { it.firstOrNull() == "BEGIN" && it.size == 5 }
            ?: return ZipExtractionResult(ZipExtractionStatus.NEEDS_ATTENTION, null, null, 0, 0,
                "Extraction journal is unreadable")
        val operationId = begin[1]
        val partialName = begin[3]
        if (!partialName.startsWith(PARTIAL_PREFIX) || partialName != PARTIAL_PREFIX + operationId)
            return ZipExtractionResult(ZipExtractionStatus.NEEDS_ATTENTION, operationId, null, 0, 0,
                "Extraction journal is inconsistent")
        lines.lastOrNull { it.firstOrNull() == "PUBLISHED" }?.let {
            return ZipExtractionResult(ZipExtractionStatus.EXTRACTED, operationId, it.getOrNull(1), 0, 0, null)
        }
        if (lines.any { it.firstOrNull() == "ROLLED_BACK" })
            return ZipExtractionResult(ZipExtractionStatus.FAILED_ROLLED_BACK, operationId, null, 0, 0,
                "Interrupted extraction was already rolled back")
        if (lines.any { it.firstOrNull() == "NEEDS_ATTENTION" })
            return ZipExtractionResult(ZipExtractionStatus.NEEDS_ATTENTION, operationId, null, 0, 0,
                "Interrupted extraction needs review")

        val partial = target.child(target.root, partialName)
        val intent = lines.lastOrNull { it.firstOrNull() == "PUBLISH_INTENT" }?.getOrNull(1)
        if (partial == null && intent != null) {
            val published = target.child(target.root, intent)
            if (published != null && target.isDirectory(published)) {
                // Rename finished but the process died before journaling it.
                journal.append(listOf("PUBLISHED", intent))
                return ZipExtractionResult(ZipExtractionStatus.EXTRACTED, operationId, intent, 0, 0,
                    "Recovered a completed extraction")
            }
        }
        return finish(journal, target, partialName, operationId, ZipExtractionStatus.FAILED_ROLLED_BACK,
            "Interrupted extraction was rolled back", 0, 0)
    }

    /** Proposed new folder name derived from the archive label; never a path. */
    fun folderBaseName(label: String): String {
        val trimmed = label.trim().let { if (it.lowercase(Locale.ROOT).endsWith(".zip")) it.dropLast(4) else it }
        val cleaned = trimmed.map { c ->
            if (c.isISOControl() || c in "/\\:*?\"<>|" || c in '‪'..'‮' || c in '⁦'..'⁩') '_' else c
        }.joinToString("").trim().trimEnd('.', ' ').trimStart('.')
        val bounded = boundUtf8(cleaned.ifBlank { "Extracted archive" }, 200)
        return bounded.ifBlank { "Extracted archive" }
    }

    private class Member(val rawName: String, val path: List<String>, val directory: Boolean)
    private class Plan(val members: List<Member>, val problem: String?)

    private fun plan(request: ZipExtractionRequest): Plan? = ZipFile(request.stagedZip).use { zip ->
        val members = ArrayList<Member>()
        val seen = HashSet<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (members.size >= request.limits.maxEntries)
                return@use Plan(emptyList(), "ZIP entry count exceeds the configured limit")
            val canonical = ZipEntryPreflight.canonicalPath(entry.name, entry.isDirectory, request.limits.maxDepth)
                ?: return@use Plan(emptyList(), "ZIP entry has an unsafe path")
            val parts = canonical.split("/")
            if (parts.any { it.toByteArray(Charsets.UTF_8).size > MAX_SEGMENT_UTF8_BYTES })
                return@use Plan(emptyList(), "ZIP entry name is too long for Android storage")
            if (parts.first().startsWith(PARTIAL_PREFIX))
                return@use Plan(emptyList(), "ZIP entry uses a reserved name")
            if (!seen.add(canonical.lowercase(Locale.ROOT)))
                return@use Plan(emptyList(), "ZIP contains duplicate or case-colliding paths")
            members += Member(entry.name, parts, entry.isDirectory)
        }
        Plan(members, null)
    }

    private fun <N : Any> writeMembers(
        request: ZipExtractionRequest,
        plan: Plan,
        target: ZipExtractionTarget<N>,
        partial: N,
        journal: ZipExtractionJournal,
        cancelled: () -> Boolean,
        progress: (Int, Long) -> Unit
    ): Pair<ZipExtractionStatus, String>? {
        val directories = HashMap<String, N>()
        directories[""] = partial
        fun directory(path: List<String>): N? {
            var key = ""
            var node = partial
            for (segment in path) {
                key = if (key.isEmpty()) segment else "$key/$segment"
                val known = directories[key]
                if (known != null) { node = known; continue }
                journal.append(listOf("DIR", key))
                node = target.createDirectory(node, segment) ?: return null
                directories[key] = node
            }
            return node
        }

        var files = 0
        var total = 0L
        val buffer = ByteArray(32 * 1024)
        ZipFile(request.stagedZip).use { zip ->
            for (member in plan.members) {
                if (cancelled()) return ZipExtractionStatus.CANCELLED to "Extraction cancelled"
                if (member.directory) {
                    directory(member.path) ?: return ZipExtractionStatus.FAILED_ROLLED_BACK to
                        "Could not create a folder at the destination"
                    continue
                }
                val entry = zip.getEntry(member.rawName)
                    ?: return ZipExtractionStatus.FAILED_ROLLED_BACK to "ZIP inventory changed during extraction"
                if (entry.isDirectory || entry.size < 0 || entry.crc < 0 || entry.compressedSize < 0)
                    return ZipExtractionStatus.FAILED_ROLLED_BACK to "ZIP member changed during extraction"
                val parent = directory(member.path.dropLast(1))
                    ?: return ZipExtractionStatus.FAILED_ROLLED_BACK to "Could not create a folder at the destination"
                val relative = member.path.joinToString("/")
                journal.append(listOf("FILE", relative))
                val file = target.createFile(parent, member.path.last())
                    ?: return ZipExtractionStatus.FAILED_ROLLED_BACK to "Destination refused or renamed a new file"
                files++
                val crc = CRC32()
                var actual = 0L
                target.openNewOutput(file).use { output ->
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            if (cancelled()) return ZipExtractionStatus.CANCELLED to "Extraction cancelled"
                            if (Thread.currentThread().isInterrupted)
                                return ZipExtractionStatus.CANCELLED to "Extraction interrupted"
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) return ZipExtractionStatus.FAILED_ROLLED_BACK to "ZIP stream made no progress"
                            if (count.toLong() > request.maxExpandedBytes - total)
                                return ZipExtractionStatus.FAILED_ROLLED_BACK to "Actual ZIP expansion exceeded byte budget"
                            actual += count
                            if (actual > entry.size)
                                return ZipExtractionStatus.FAILED_ROLLED_BACK to "ZIP member exceeded its declared size"
                            val compressed = entry.compressedSize
                            if (compressed == 0L || actual / compressed > request.limits.maxRatio)
                                return ZipExtractionStatus.FAILED_ROLLED_BACK to "Actual ZIP member expansion ratio exceeded limit"
                            total += count
                            crc.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                    output.flush()
                }
                if (actual != entry.size)
                    return ZipExtractionStatus.FAILED_ROLLED_BACK to "ZIP member size differs from its header"
                if (crc.value != entry.crc)
                    return ZipExtractionStatus.FAILED_ROLLED_BACK to "ZIP member CRC mismatch while writing"
                progress(files, total)
            }
        }
        progress(files, total)
        return null
    }

    /** Remove only journaled children of our own partial folder, deepest first. */
    internal fun <N : Any> finish(
        journal: ZipExtractionJournal,
        target: ZipExtractionTarget<N>,
        partialName: String,
        operationId: String,
        status: ZipExtractionStatus,
        reason: String,
        files: Int,
        bytes: Long
    ): ZipExtractionResult {
        val clean = try { rollback(journal, target, partialName) } catch (_: Exception) { false }
        if (!clean) return needsAttention(journal, operationId, files, bytes,
            "$reason; partial output in $partialName could not be fully removed")
        journal.append(listOf("ROLLED_BACK"))
        return ZipExtractionResult(status, operationId, null, 0, 0, reason)
    }

    private fun <N : Any> rollback(journal: ZipExtractionJournal, target: ZipExtractionTarget<N>, partialName: String): Boolean {
        val partial = target.child(target.root, partialName) ?: return true
        if (!target.isDirectory(partial)) return false
        val recorded = journal.read().filter { it.size == 2 && (it[0] == "FILE" || it[0] == "DIR") }
        // Children before parents: longest paths first, files before directories.
        val ordered = recorded.distinctBy { it[1] }
            .sortedWith(compareByDescending<List<String>> { it[1].count { c -> c == '/' } }.thenBy { it[0] != "FILE" })
        for (entry in ordered) {
            var node: N? = partial
            for (segment in entry[1].split("/")) node = node?.let { target.child(it, segment) }
            if (node == null) continue
            if ((entry[0] == "DIR") != target.isDirectory(node)) return false
            if (entry[0] == "DIR" && target.hasChildren(node)) return false
            if (!target.delete(node)) return false
        }
        if (target.hasChildren(partial)) return false
        return target.delete(partial) && target.child(target.root, partialName) == null
    }

    internal fun needsAttention(journal: ZipExtractionJournal, operationId: String, files: Int, bytes: Long, reason: String): ZipExtractionResult {
        try { journal.append(listOf("NEEDS_ATTENTION")) } catch (_: IOException) { }
        return ZipExtractionResult(ZipExtractionStatus.NEEDS_ATTENTION, operationId, null, files, bytes, reason)
    }

    private fun blocked(reason: String) =
        ZipExtractionResult(ZipExtractionStatus.BLOCKED, null, null, 0, 0, reason)

    private fun boundUtf8(value: String, maxBytes: Int): String {
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + size > maxBytes) break
            out.appendCodePoint(cp)
            used += size
            i += Character.charCount(cp)
        }
        return out.toString().trim()
    }
}

/** Plain-directory target used by JVM tests and app-private storage. Never follows links. */
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
