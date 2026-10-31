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
 * Deliberately adversarial DEBUG-ONLY provider. A createDocument call returns
 * an existing document id rather than creating anything. An unsafe caller
 * opening that returned URI in "w" would target someone else's existing bytes.
 *
 * It provides no write, rename or delete implementation; this provider cannot
 * accidentally mutate a user's real filesystem.
 */
class ReusedUriDocumentsProvider : DocumentsProvider() {
    companion object {
        private const val ROOT_ID = "root"
        private const val FOREIGN_ID = "root/foreign.txt"
        fun authority(context: Context) = context.packageName + ".debug.reuseduri"
        fun treeUri(context: Context): Uri =
            DocumentsContract.buildTreeDocumentUri(authority(context), ROOT_ID)
        fun baseDir(context: Context) = File(context.filesDir, "debug-reused-uri-fixture")
        fun foreign(context: Context) = File(baseDir(context), "foreign.txt")
    }

    override fun onCreate(): Boolean = true

    private fun foreignFile(): File = foreign(requireNotNull(context))

    private fun row(cursor: MatrixCursor, id: String) {
        val file = if (id == ROOT_ID) baseDir(requireNotNull(context)) else foreignFile()
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, if (id == ROOT_ID) "Adversarial fixture" else "foreign.txt")
            .add(Document.COLUMN_MIME_TYPE,
                if (id == ROOT_ID) Document.MIME_TYPE_DIR else "text/plain")
            .add(Document.COLUMN_SIZE, if (id == ROOT_ID) null else file.length())
            .add(Document.COLUMN_FLAGS, if (id == ROOT_ID) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
    }

    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS)

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).apply {
            newRow().add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(Root.COLUMN_TITLE, "Reused URI test fixture")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY)
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        if (documentId != ROOT_ID && documentId != FOREIGN_ID) throw FileNotFoundException(documentId)
        if (documentId == FOREIGN_ID && !foreignFile().isFile) throw FileNotFoundException(documentId)
        return MatrixCursor(projection ?: columns).apply { row(this, documentId) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?,
                                     sortOrder: String?): Cursor {
        if (parentDocumentId != ROOT_ID) throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(projection ?: columns).apply {
            if (foreignFile().isFile) row(this, FOREIGN_ID)
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (parentDocumentId != ROOT_ID || !foreignFile().isFile)
            throw FileNotFoundException("Fixture precondition not met")
        // Provider lies about creation, mimicking a broken or malicious SAF provider.
        return FOREIGN_ID
    }

    override fun openDocument(documentId: String, mode: String,
                              signal: CancellationSignal?): ParcelFileDescriptor {
        if (documentId != FOREIGN_ID || mode != "r") throw FileNotFoundException("Read-only fixture")
        return ParcelFileDescriptor.open(foreignFile(), ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
