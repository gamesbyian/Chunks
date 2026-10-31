package io.github.gamesbyian.chunks.debug

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Debug-build-only DocumentsProvider over a disposable app-private directory.
 * It mirrors the platform file provider's habit of silently choosing a unique
 * name ("name (1)") on create/rename collisions, so callers' readback checks
 * are exercised through real ContentResolver/DocumentsContract calls.
 */
class FixtureDocumentsProvider : DocumentsProvider() {
    companion object {
        private const val ROOT_ID = "root"

        fun authority(context: Context) = context.packageName + ".debug.fixturedocs"
        fun baseDir(context: Context) = File(context.filesDir, "debug-saf-fixture")
        fun treeUri(context: Context): Uri = DocumentsContract.buildTreeDocumentUri(authority(context), ROOT_ID)
    }

    private val base: File get() = baseDir(requireNotNull(context)).apply { mkdirs() }

    override fun onCreate() = true

    private fun file(documentId: String): File {
        if (documentId == ROOT_ID) return base
        if (!documentId.startsWith("$ROOT_ID/")) throw FileNotFoundException(documentId)
        val file = File(base, documentId.removePrefix("$ROOT_ID/"))
        if (!file.canonicalPath.startsWith(base.canonicalPath + File.separator)) throw FileNotFoundException(documentId)
        return file
    }

    private fun id(file: File): String =
        if (file == base) ROOT_ID else ROOT_ID + "/" + file.relativeTo(base).invariantSeparatorsPath

    private fun unique(parent: File, name: String): File {
        var candidate = File(parent, name)
        var n = 1
        while (candidate.exists()) candidate = File(parent, "$name (${n++})")
        return candidate
    }

    private fun row(cursor: MatrixCursor, file: File) {
        val directory = file.isDirectory
        val fields: Map<String, Any?> = mapOf(
            Document.COLUMN_DOCUMENT_ID to id(file),
            Document.COLUMN_DISPLAY_NAME to if (file == base) "Chunks fixture" else file.name,
            Document.COLUMN_MIME_TYPE to if (directory) Document.MIME_TYPE_DIR else "application/octet-stream",
            Document.COLUMN_SIZE to if (directory) null else file.length(),
            Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            Document.COLUMN_FLAGS to (Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                if (directory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE)
        )
        cursor.addRow(cursor.columnNames.map { fields[it] }.toTypedArray())
    }

    private val documentColumns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES)).apply {
            val fields: Map<String, Any?> = mapOf(
                Root.COLUMN_ROOT_ID to ROOT_ID,
                Root.COLUMN_DOCUMENT_ID to ROOT_ID,
                Root.COLUMN_TITLE to "Chunks debug fixture",
                Root.COLUMN_FLAGS to (Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD),
                Root.COLUMN_AVAILABLE_BYTES to base.usableSpace
            )
            addRow(columnNames.map { fields[it] }.toTypedArray())
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: documentColumns).apply {
            val file = file(documentId)
            if (!file.exists()) throw FileNotFoundException(documentId)
            row(this, file)
        }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: documentColumns).apply {
            file(parentDocumentId).listFiles().orEmpty().sortedBy { it.name }.forEach { row(this, it) }
        }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val created = unique(file(parentDocumentId), displayName)
        val ok = if (mimeType == Document.MIME_TYPE_DIR) created.mkdir() else created.createNewFile()
        if (!ok) throw IllegalStateException("Create failed")
        return id(created)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val before = file(documentId)
        val after = unique(requireNotNull(before.parentFile), displayName)
        if (!before.renameTo(after)) throw IllegalStateException("Rename failed")
        return id(after)
    }

    override fun deleteDocument(documentId: String) {
        val target = file(documentId)
        if (target == base || !target.deleteRecursively()) throw IllegalStateException("Delete failed")
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val parent = file(parentDocumentId).canonicalPath
        file(documentId).canonicalPath.startsWith(parent + File.separator)
    } catch (_: FileNotFoundException) { false }
}
