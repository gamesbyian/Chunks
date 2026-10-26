package io.github.gamesbyian.chunks

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.gamesbyian.chunks.model.FileZipExtractionJournal
import io.github.gamesbyian.chunks.model.LibraryCopier
import io.github.gamesbyian.chunks.model.LibraryCopyFile
import io.github.gamesbyian.chunks.model.LibraryCopyRequest
import io.github.gamesbyian.chunks.model.ZipExtractionRequest
import io.github.gamesbyian.chunks.model.ZipExtractionResult
import io.github.gamesbyian.chunks.model.ZipExtractionStatus
import io.github.gamesbyian.chunks.model.ZipExtractionTarget
import io.github.gamesbyian.chunks.model.ZipExtractor
import java.io.File
import java.io.OutputStream
import java.io.InputStream

/**
 * A user-approved Storage Access Framework tree. Every create/rename is read back:
 * providers that silently pick another name ("x (1)") are treated as refusals and
 * the substitute is undone, so extraction never lands somewhere unreviewed.
 * Directory deletes are guarded by an emptiness check because SAF deletes recurse.
 */
internal class SafZipExtractionTarget(
    private val resolver: ContentResolver,
    private val treeUri: Uri
) : ZipExtractionTarget<Uri> {
    override val root: Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private class Row(val uri: Uri, val name: String, val directory: Boolean)

    private fun children(parent: Uri): List<Row> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(parent))
        val rows = ArrayList<Row>()
        resolver.query(childrenUri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                rows += Row(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name,
                    cursor.getString(2) == Document.MIME_TYPE_DIR)
            }
        } ?: throw java.io.IOException("Destination folder could not be listed")
        return rows
    }

    private fun describe(node: Uri): Row? = resolver.query(node,
        arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) null
        else Row(node, cursor.getString(0) ?: "", cursor.getString(1) == Document.MIME_TYPE_DIR)
    }

    override fun child(parent: Uri, name: String): Uri? = children(parent).firstOrNull { it.name == name }?.uri
    override fun isDirectory(node: Uri) = describe(node)?.directory == true
    override fun hasChildren(directory: Uri) = children(directory).isNotEmpty()

    private fun create(parent: Uri, mime: String, name: String): Uri? {
        if (child(parent, name) != null) return null
        val created = DocumentsContract.createDocument(resolver, parent, mime, name) ?: return null
        if (describe(created)?.name == name) return created
        // Provider substituted a name: remove our own just-created, empty substitute.
        DocumentsContract.deleteDocument(resolver, created)
        return null
    }

    override fun createDirectory(parent: Uri, name: String) = create(parent, Document.MIME_TYPE_DIR, name)
    override fun createFile(parent: Uri, name: String) = create(parent, "application/octet-stream", name)

    override fun openNewOutput(file: Uri): OutputStream {
        val descriptor = resolver.openFileDescriptor(file, "w")
            ?: throw java.io.IOException("Destination file could not be opened")
        return object : ParcelFileDescriptor.AutoCloseOutputStream(descriptor) {
            override fun close() {
                try { flush(); fd.sync() } catch (_: java.io.SyncFailedException) {
                    // Some providers expose pipes; the CRC was already checked on bytes written.
                } finally {
                    super.close()
                }
            }
        }
    }

    override fun openExistingInput(file: Uri): InputStream? = resolver.openInputStream(file)

    override fun rename(node: Uri, parent: Uri, newName: String): Uri? {
        if (child(parent, newName) != null) return null
        val previous = describe(node)?.name ?: return null
        val renamed = DocumentsContract.renameDocument(resolver, node, newName) ?: return null
        if (describe(renamed)?.name == newName) return renamed
        // Provider chose a different unique name; put it back rather than publish it.
        DocumentsContract.renameDocument(resolver, renamed, previous)
        return null
    }

    override fun delete(node: Uri): Boolean {
        val row = describe(node) ?: return true
        if (row.directory && hasChildren(node)) return false
        return DocumentsContract.deleteDocument(resolver, node)
    }

    /**
     * SAF has no general free-space query. Only the primary shared-storage provider
     * maps to a volume we can measure; everything else is honestly unknown.
     */
    override fun availableBytes(): Long? {
        if (treeUri.authority != "com.android.externalstorage.documents") return null
        if (!DocumentsContract.getTreeDocumentId(treeUri).startsWith("primary:")) return null
        return try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (_: Exception) { null }
    }

    fun displayName(): String = describe(root)?.name?.take(120) ?: "Selected folder"
}

/** Coordinates staging, persisted tree access, journals and post-crash reconciliation. */
internal object LocalZipExtraction {
    private val journalName = Regex("^[a-f0-9]{32}\\.log$")
    /** Operations running in this process; their journals are live, not abandoned. */
    private val active: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun journalDir(context: Context) = File(context.filesDir, "zip-extractions").apply { mkdirs() }
    /** Library copies share the journal format; a separate folder records which kind each was. */
    private fun copyJournalDir(context: Context) = File(context.filesDir, "library-copies").apply { mkdirs() }

    fun extract(
        context: Context,
        sourceUri: Uri,
        treeUri: Uri,
        archiveLabel: String,
        allowUnknownFreeSpace: Boolean,
        cancelled: () -> Boolean
    ): ZipExtractionResult {
        val resolver = context.contentResolver
        // Persist access so an interrupted operation can still be reconciled later.
        try {
            resolver.takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not persistable (e.g. debug fixture); reconciliation may then need attention.
        }
        val operationId = java.util.UUID.randomUUID().toString().replace("-", "")
        val journal = FileZipExtractionJournal(File(journalDir(context), "$operationId.log"))
        active += operationId
        val staged = LocalZipDocumentInspector.stage(context, sourceUri)
        val stagedFile = staged.file
        if (stagedFile == null) {
            active -= operationId
            return ZipExtractionResult(ZipExtractionStatus.BLOCKED, null, null, 0, 0,
                staged.failure ?: "The archive could not be copied for extraction")
        }
        try {
            val result = ZipExtractor.extract(
                ZipExtractionRequest(stagedFile, archiveLabel, treeUri.toString(),
                    allowUnknownFreeSpace = allowUnknownFreeSpace),
                SafZipExtractionTarget(resolver, treeUri), journal, cancelled, operationId)
            if (result.status != ZipExtractionStatus.NEEDS_ATTENTION) {
                File(journalDir(context), "$operationId.log").delete()
                releaseIfUnused(context, treeUri)
            }
            return result
        } finally {
            stagedFile.delete()
            active -= operationId
        }
    }

    /**
     * Copy a finished local transfer's files into a new folder inside [treeUri].
     * The originals are only read, so the transfer keeps seeding.
     */
    fun copyFinished(
        context: Context,
        sourceRoot: File,
        files: List<LibraryCopyFile>,
        label: String,
        treeUri: Uri,
        allowUnknownFreeSpace: Boolean,
        cancelled: () -> Boolean
    ): ZipExtractionResult {
        val resolver = context.contentResolver
        try {
            resolver.takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) { }
        val operationId = java.util.UUID.randomUUID().toString().replace("-", "")
        val log = File(copyJournalDir(context), "$operationId.log")
        active += operationId
        try {
            val result = LibraryCopier.copy(
                LibraryCopyRequest(sourceRoot, files, label, treeUri.toString(), allowUnknownFreeSpace),
                SafZipExtractionTarget(resolver, treeUri), FileZipExtractionJournal(log), cancelled, operationId)
            if (result.status != ZipExtractionStatus.NEEDS_ATTENTION) {
                log.delete()
                releaseIfUnused(context, treeUri)
            }
            return result
        } finally {
            active -= operationId
        }
    }

    /** An interrupted operation's outcome; [copy] tells a library copy from an extraction. */
    class Reconciled(val result: ZipExtractionResult, val copy: Boolean)

    /** Resolve journals left by process death. Never re-runs an extraction or copy. */
    fun reconcilePending(context: Context): List<Reconciled> {
        val results = ArrayList<Reconciled>()
        for ((log, copy) in journalDir(context).listFiles().orEmpty().map { it to false } +
            copyJournalDir(context).listFiles().orEmpty().map { it to true }) {
            if (!journalName.matches(log.name) || !log.isFile ||
                log.name.removeSuffix(".log") in active) continue
            val journal = FileZipExtractionJournal(log)
            val target = journal.read().firstOrNull { it.firstOrNull() == "BEGIN" }?.getOrNull(2)
                ?.let { Uri.parse(it) }?.takeIf { it.scheme == "content" }
            val result = try {
                if (target == null) null
                else ZipExtractor.reconcile(SafZipExtractionTarget(context.contentResolver, target), journal)
            } catch (_: Exception) { null }
                ?: ZipExtractionResult(ZipExtractionStatus.NEEDS_ATTENTION, log.name.removeSuffix(".log"),
                    null, 0, 0, "Destination folder access is no longer available")
            if (result.status != ZipExtractionStatus.NEEDS_ATTENTION) {
                log.delete()
                if (target != null) releaseIfUnused(context, target)
            }
            results += Reconciled(result, copy)
        }
        return results
    }

    /** Drop our persisted tree grant once no unfinished journal still needs it. */
    private fun releaseIfUnused(context: Context, treeUri: Uri) {
        val wanted = treeUri.toString()
        val stillNeeded = (journalDir(context).listFiles().orEmpty().toList() +
            copyJournalDir(context).listFiles().orEmpty().toList()).any { log ->
            journalName.matches(log.name) && FileZipExtractionJournal(log).read()
                .firstOrNull { it.firstOrNull() == "BEGIN" }?.getOrNull(2) == wanted
        }
        if (stillNeeded) return
        try {
            context.contentResolver.releasePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) { }
    }

    fun destinationLabel(context: Context, treeUri: Uri): String = try {
        SafZipExtractionTarget(context.contentResolver, treeUri).displayName()
    } catch (_: Exception) { "Selected folder" }

    fun availableBytes(context: Context, treeUri: Uri): Long? = try {
        SafZipExtractionTarget(context.contentResolver, treeUri).availableBytes()
    } catch (_: Exception) { null }
}
