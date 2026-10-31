package io.github.gamesbyian.chunks.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * An SAF metadata-only adapter for #809. No open-for-write, create, rename or
 * delete operations are exposed. A returned URI can only be rejected or marked
 * an unproven candidate, NEVER adopted as a newly owned file.
 *
 * Ordinary DocumentsProvider query results are not an atomic object identity
 * or creation proof. Providers may paginate, alias IDs or change entries after
 * observation; even this adapter's positive result has no mutation authority.
 */
internal class SafReadOnlyIdentityInspector(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val parentUri: Uri
) {
    private val treeId: String? = try {
        if (treeUri.scheme != "content" || treeUri.authority.isNullOrBlank() ||
            parentUri.scheme != "content" || treeUri.authority != parentUri.authority ||
            !DocumentsContract.isTreeUri(treeUri)) null
        else DocumentsContract.getTreeDocumentId(treeUri).takeIf { it.isNotBlank() }
            ?.takeIf { it == DocumentsContract.getTreeDocumentId(parentUri) }
    } catch (_: RuntimeException) { null }

    private val parentId: String? = try {
        if (treeId == null) null else DocumentsContract.getDocumentId(parentUri)
            .takeIf { it.isNotBlank() }
    } catch (_: RuntimeException) { null }

    private val scope: String? = if (treeId != null && parentId != null)
        (treeUri.authority ?: "") + "\u0000" + treeId + "\u0000" + parentId else null

    fun snapshot(): SafCreationCandidate.Inventory? {
        val root = scope ?: return null
        val id = parentId ?: return null
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id)
        } catch (_: RuntimeException) { return null }
        return try {
            resolver.query(childrenUri, arrayOf(Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nodes = ArrayList<SafCreationCandidate.Child>()
                // Large/incomplete inventories cannot serve even as a heuristic.
                if (cursor.count > 10_000) return null
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0) ?: return null
                    val name = cursor.getString(1) ?: return null
                    if (childId.isBlank() || name.isBlank()) return null
                    nodes += SafCreationCandidate.Child(childId, name)
                }
                SafCreationCandidate.Inventory(root, nodes, complete = true)
            }
        } catch (_: RuntimeException) { null } catch (_: java.io.IOException) { null }
    }

    fun inspectReturned(before: SafCreationCandidate.Inventory?, returnedUri: Uri?,
                        requestedName: String): SafCreationCandidate.Result {
        if (scope == null || returnedUri?.scheme != "content" ||
            returnedUri.authority != treeUri.authority)
            return SafCreationCandidate.Result.REJECT
        val returnedId = try {
            if (DocumentsContract.getTreeDocumentId(returnedUri) != treeId) return SafCreationCandidate.Result.REJECT
            DocumentsContract.getDocumentId(returnedUri)
        } catch (_: RuntimeException) { return SafCreationCandidate.Result.REJECT }
        return SafCreationCandidate.inspect(before, snapshot(), scope, returnedId, requestedName)
    }
}
