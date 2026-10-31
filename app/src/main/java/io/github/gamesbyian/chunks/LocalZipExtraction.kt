package io.github.gamesbyian.chunks

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.gamesbyian.chunks.model.LibraryCopyFile
import io.github.gamesbyian.chunks.model.ZipExtractionResult
import io.github.gamesbyian.chunks.model.ZipExtractionStatus
import io.github.gamesbyian.chunks.model.ZipExtractionTarget
import java.io.File
import java.io.OutputStream
import java.io.InputStream

/**
 * Read-only compatibility facade for legacy SAF extraction journals.
 *
 * DocumentsContract.createDocument() cannot establish exclusive creation:
 * a provider can return an existing URI. The former implementation then
 * opened that URI with truncating "w" or deleted/renamed provider substitutes
 * based on names alone. Until a separately reviewed ownership-bound adapter
 * exists, all mutation entry points in this class refuse *before* provider I/O.
 *
 * Production extraction/copy already refuse at LocalZipExtraction; this
 * defence-in-depth boundary protects future and direct model callers too.
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

    // No exclusive create or object-identity proof is available through this
    // generic SAF interface. Refuse without querying or mutating the provider.
    override fun createDirectory(parent: Uri, name: String): Uri? = null
    override fun createFile(parent: Uri, name: String): Uri? = null

    override fun openNewOutput(file: Uri): OutputStream =
        throw java.io.IOException("SAF destination ownership is not proven; write refused")

    override fun openExistingInput(file: Uri): InputStream? = resolver.openInputStream(file)

    override fun rename(node: Uri, parent: Uri, newName: String): Uri? = null

    // Never call DocumentsContract.deleteDocument without an ownership-bound
    // node and deletion authorization. Even a supposedly empty returned URI
    // might alias a foreign directory, and deleteDocument may be recursive.
    override fun delete(node: Uri): Boolean = false

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

    // SAF does not provide a general exclusive-create or ownership-bound
    // deletion guarantee. Refuse before taking grants, staging or touching a
    // destination until those boundaries can be independently verified.
    private fun publicationUnavailable() = ZipExtractionResult(
        ZipExtractionStatus.BLOCKED, null, null, 0, 0,
        "Temporarily unavailable while destination file safety is being improved"
    )

    fun extract(
        context: Context,
        sourceUri: Uri,
        treeUri: Uri,
        archiveLabel: String,
        allowUnknownFreeSpace: Boolean,
        cancelled: () -> Boolean
    ): ZipExtractionResult = publicationUnavailable()

    fun copyFinished(
        context: Context,
        sourceRoot: File,
        files: List<LibraryCopyFile>,
        label: String,
        treeUri: Uri,
        allowUnknownFreeSpace: Boolean,
        cancelled: () -> Boolean
    ): ZipExtractionResult = publicationUnavailable()

    /** An interrupted operation's outcome; [copy] tells a library copy from an extraction. */
    class Reconciled(val result: ZipExtractionResult, val copy: Boolean)

    /** Report unfinished journals without resolving, modifying or retrying them. */
    fun reconcilePending(context: Context): List<Reconciled> {
        // Legacy journals contain names, not proof that the current nodes are
        // ours. Do not infer publication or delete files, journals or grants.
        return listOf("zip-extractions" to false, "library-copies" to true).flatMap { (name, copy) ->
            File(context.filesDir, name).listFiles().orEmpty().mapNotNull { log ->
                if (!journalName.matches(log.name) || !log.isFile ||
                    log.name.removeSuffix(".log") in active) return@mapNotNull null
                Reconciled(ZipExtractionResult(ZipExtractionStatus.NEEDS_ATTENTION,
                    log.name.removeSuffix(".log"), null, 0, 0,
                    "An interrupted operation needs review; its destination and journal were left unchanged"), copy)
            }
        }
    }

    fun destinationLabel(context: Context, treeUri: Uri): String = try {
        SafZipExtractionTarget(context.contentResolver, treeUri).displayName()
    } catch (_: Exception) { "Selected folder" }

    fun availableBytes(context: Context, treeUri: Uri): Long? = try {
        SafZipExtractionTarget(context.contentResolver, treeUri).availableBytes()
    } catch (_: Exception) { null }
}
