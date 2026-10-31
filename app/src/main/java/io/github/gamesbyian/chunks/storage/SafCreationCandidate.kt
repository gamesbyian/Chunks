package io.github.gamesbyian.chunks.storage

/**
 * Read-only SAF creation inspection, not an authority to write.
 * DocumentsContract does not promise CREATE_NEW or RENAME_NOREPLACE. Even
 * consistent before/after URI inventories cannot prove exclusive creation.
 */
internal object SafCreationCandidate {
    data class Child(val documentId: String, val displayName: String)
    data class Inventory(val scope: String, val children: List<Child>, val complete: Boolean)
    enum class Result { REJECT, UNPROVEN_CANDIDATE }

    fun inspect(before: Inventory?, after: Inventory?, returnedScope: String?,
                returnedDocumentId: String?, requestedName: String): Result {
        if (before == null || after == null || !before.complete || !after.complete ||
            before.scope.isBlank() || before.scope != after.scope ||
            before.scope != returnedScope || returnedDocumentId.isNullOrBlank() ||
            requestedName.isBlank() || '/' in requestedName || '\\' in requestedName)
            return Result.REJECT
        // An existing URI may be renamed or re-labeled by the provider. Never
        // accept it as a new file or open it using a truncating write mode.
        if (before.children.any { it.documentId == returnedDocumentId }) return Result.REJECT
        val matched = after.children.filter { it.documentId == returnedDocumentId }
        if (matched.size != 1 || matched[0].displayName != requestedName) return Result.REJECT
        if (after.children.count { it.displayName == requestedName } != 1) return Result.REJECT
        if (after.children.any { it.documentId.isBlank() } ||
            after.children.map { it.documentId }.toSet().size != after.children.size)
            return Result.REJECT
        // Never return an ownership/proven-write verdict. A malicious or merely
        // non-atomic provider may still have substituted this object after the
        // observed inventory, or may alias it to existing bytes by another ID.
        return Result.UNPROVEN_CANDIDATE
    }
}
