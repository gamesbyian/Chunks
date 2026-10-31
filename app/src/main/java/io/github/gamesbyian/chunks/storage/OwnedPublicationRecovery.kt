package io.github.gamesbyian.chunks.storage

/**
 * Experimental, read-only decision model for #809. It never authorizes a file open,
 * mutation, deletion, retry or adoption. A native/SAF adapter must prove exclusive
 * creation and stable physical identity separately before any publisher can use it.
 *
 * Names and journal intentions are untrusted labels. A verified publication needs
 * a journaled pre-publication creation identity, a separate post-publication
 * observation of the same physical object in the *same scope*, and a digest/size
 * witness of bytes read back from the destination after publication.
 */
internal object OwnedPublicationRecovery {
    enum class Verdict { NEEDS_ATTENTION, VERIFIED_PUBLISHED }

    data class ObjectIdentity(
        /** Identity of the selected SAF tree/volume, NOT its display name. */
        val scope: String,
        /** Stable object identity, NOT a pathname or user-facing display name. */
        val objectId: String
    ) {
        init { require(scope.isNotBlank() && objectId.isNotBlank()) }
    }

    data class Digest(val size: Long, val sha256: String) {
        init { require(size >= 0 && sha256.matches(Regex("[0-9a-f]{64}"))) }
    }

    /** Only an adapter with exclusive-create proof may issue a Created record. */
    data class Created(
        val operationId: String,
        val identity: ObjectIdentity,
        val creationExclusive: Boolean,
        val outputHandleBound: Boolean
    )

    /** Destination-byte evidence collected AFTER publishing, independently of the source. */
    data class PublishedObservation(
        val identity: ObjectIdentity,
        val digest: Digest,
        /** Verified by the adapter at the physical publication boundary. */
        val nonReplacingPublish: Boolean,
        val identityVerifiedByBoundHandle: Boolean
    )

    data class JournalSnapshot(
        val operationId: String,
        val created: Created?,
        /** Full digest of source bytes established before copy/extraction. */
        val expected: Digest?,
        /** Full read-back of staged bytes before attempting publication. */
        val stagedReadback: Digest?,
        val publishIntentName: String?,
        /** Durable success is optional; a crash can occur after physical publication. */
        val publishedRecord: Boolean = false
    )

    /**
     * Reconciliation is side-effect free. It may recognize a confirmed publication
     * but cannot make a missing journal line or a matching name into ownership.
     */
    fun evaluate(
        journal: JournalSnapshot,
        observed: PublishedObservation?
    ): Verdict {
        val created = journal.created ?: return Verdict.NEEDS_ATTENTION
        val expected = journal.expected ?: return Verdict.NEEDS_ATTENTION
        val staged = journal.stagedReadback ?: return Verdict.NEEDS_ATTENTION
        val published = observed ?: return Verdict.NEEDS_ATTENTION
        if (journal.operationId.isBlank() || created.operationId != journal.operationId ||
            journal.publishIntentName.isNullOrBlank()) return Verdict.NEEDS_ATTENTION
        if (!created.creationExclusive || !created.outputHandleBound ||
            !published.nonReplacingPublish || !published.identityVerifiedByBoundHandle)
            return Verdict.NEEDS_ATTENTION
        if (created.identity != published.identity || staged != expected ||
            published.digest != expected) return Verdict.NEEDS_ATTENTION
        return Verdict.VERIFIED_PUBLISHED
    }
}
