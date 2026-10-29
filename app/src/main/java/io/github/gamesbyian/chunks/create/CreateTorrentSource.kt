package io.github.gamesbyian.chunks.create

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/**
 * The files a user picked for Create & Share, read through Android's document
 * providers only (no storage permission, no path guessing). Listing is
 * read-only; bytes are read later, once, by [TorrentMetainfoBuilder].
 */
internal class CreateTorrentSource(val name: String, val entries: List<TorrentMetainfoBuilder.Entry>) {
    val totalBytes: Long get() = entries.sumOf { it.sizeBytes }

    companion object {
        /** One picked file becomes a single-file torrent named after it. */
        fun fromDocument(context: Context, uri: Uri): CreateTorrentSource {
            val resolver = context.contentResolver
            var name: String? = null
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            val fileName = requireNotNull(name?.takeIf { it.isNotBlank() }) { "This file has no name the provider will share" }
            require(size >= 0) { "This file's provider does not report its size" }
            return CreateTorrentSource(fileName, listOf(TorrentMetainfoBuilder.Entry(listOf(fileName), size) {
                requireNotNull(resolver.openInputStream(uri)) { "The file could not be opened" }
            }))
        }

        /**
         * A picked folder becomes a multi-file torrent named after the folder,
         * with every file below it in a stable (name-sorted) order.
         */
        fun fromTree(context: Context, treeUri: Uri, isCancelled: () -> Boolean = { false }): CreateTorrentSource {
            val resolver = context.contentResolver
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootName = displayName(context, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))
                ?: "Shared folder"
            val entries = ArrayList<TorrentMetainfoBuilder.Entry>()
            fun walk(documentId: String, path: List<String>) {
                require(path.size < 64) { "Folders are nested too deeply" }
                val rows = ArrayList<Triple<String, String, Pair<String?, Long>>>()
                resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE),
                    null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        rows += Triple(c.getString(0), c.getString(1) ?: "",
                            c.getString(2) to (if (c.isNull(3)) -1L else c.getLong(3)))
                    }
                } ?: error("This folder could not be listed")
                for ((id, childName, typeAndSize) in rows.sortedBy { it.second }) {
                    if (isCancelled()) throw TorrentMetainfoBuilder.Cancelled()
                    val (type, size) = typeAndSize
                    if (type == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, path + childName)
                    else {
                        require(size >= 0) { "The provider does not report the size of ${(path + childName).joinToString("/")}" }
                        require(entries.size < TorrentMetainfoBuilder.MAX_FILES) { "More than ${TorrentMetainfoBuilder.MAX_FILES} files" }
                        val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        entries += TorrentMetainfoBuilder.Entry(path + childName, size) {
                            requireNotNull(resolver.openInputStream(document)) { "${(path + childName).joinToString("/")} could not be opened" }
                        }
                    }
                }
            }
            walk(rootId, emptyList())
            require(entries.isNotEmpty()) { "This folder has no files" }
            return CreateTorrentSource(rootName, entries)
        }

        private fun displayName(context: Context, uri: Uri): String? =
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }
}
