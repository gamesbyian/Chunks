package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import java.util.Locale

/**
 * A real, explicitly confirmed *single-definition* local restore transaction.
 * The durable journal is written IN_FLIGHT before the first libtorrent Add;
 * a lost response or process death is never permission to repeat the Add.
 *
 * This intentionally accepts no historical directory from the snapshot.
 * The caller must select and independently verify a current writable Place.
 * Payload bytes, prior per-file priorities and existing sessions are not moved.
 */
internal object SnapshotRestoreOnceV1 {
    /**
     * Each single-item journal needs a distinct archive/target key. Using the
     * full archive fingerprint alone would pin the first item's singleton
     * roster and prevent restoring *any other* item in the same snapshot.
     * Bind the exact source archive and exact infohash into the derived scope.
     */
    fun itemArchiveFingerprint(archiveFingerprint: String, infoHashV1: String): String {
        require(archiveFingerprint.matches(Regex("[a-f0-9]{64}")) &&
            infoHashV1.matches(Regex("[a-f0-9]{40}"))) {
            "Unsupported restore journal identity"
        }
        val bytes = ("chunks.restore.item.v1:" + archiveFingerprint + ":" + infoHashV1)
            .toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    enum class Outcome {
        VERIFIED_PRESENT_STOPPED,
        REFUSED,
        STALE_TARGET,
        UNCERTAIN,
        JOURNAL_BLOCKED
    }

    interface LocalTarget {
        /** Must be one successful COMPLETE engine inventory; null means unavailable. */
        fun completeHashes(): List<String>?
        /** Independently observed stopped transfer's actual save directory. */
        fun observedStoppedPlace(infoHashV1: String): File?
        /** An attempted native Add. False is uncertain, never proof of no mutation. */
        fun submitStopped(originalTorrent: ByteArray, chosenPlace: File): Boolean
    }

    fun localEngine(engine: LocalTorrentEngine): LocalTarget = object : LocalTarget {
        override fun completeHashes(): List<String>? =
            try { engine.completeIdentityInventory() } catch (_: Exception) { null }

        override fun observedStoppedPlace(infoHashV1: String): File? =
            try { engine.status(infoHashV1)?.takeIf { it.stopped }?.savePath?.let(::File) }
            catch (_: Exception) { null }

        override fun submitStopped(originalTorrent: ByteArray, chosenPlace: File): Boolean =
            try {
                engine.addStopped(originalTorrent, chosenPlace) is LocalAddResult.AddedStopped
            } catch (_: Exception) { false }
    }

    /**
     * At most ONE native Add can occur from a given REVIEW/READY journal state.
     * Call only after the user explicitly confirms both the original torrent
     * identity and the independently selected destination Place.
     */
    fun execute(
        journal: SnapshotRestoreJournalV1,
        reviewed: SnapshotVerifiedTorrentDocumentV1.Prepared,
        chosenPlace: File,
        verifiedPlaceFingerprint: String,
        frozenHashes: List<String>,
        userApproved: Boolean,
        target: LocalTarget
    ): Outcome {
        if (!userApproved) return Outcome.REFUSED
        if (!chosenPlace.isAbsolute || !chosenPlace.isDirectory ||
            java.nio.file.Files.isSymbolicLink(chosenPlace.toPath()))
            return Outcome.REFUSED
        val hash = reviewed.infoHashV1
        val preview = try { TorrentMetainfoReader.preview(reviewed.bytes) }
            catch (_: Exception) { return Outcome.REFUSED }
        if (preview.infoHashV1 != hash || preview.name != reviewed.originalName)
            return Outcome.REFUSED
        val frozen = canonicalInventory(frozenHashes) ?: return Outcome.STALE_TARGET
        if (hash in frozen) return Outcome.STALE_TARGET
        val observed = canonicalInventory(try { target.completeHashes() }
            catch (_: Exception) { null } ?: return Outcome.STALE_TARGET)
            ?: return Outcome.STALE_TARGET
        if (observed != frozen || hash in observed) return Outcome.STALE_TARGET

        // Do not rewrite an IN_FLIGHT/ACK_UNVERIFIED record even if a later
        // inventory appears absent. The user can use the journal's separate
        // explicit reconciliation/retry protocol after a complete new review.
        try {
            val entry = journal.entries().singleOrNull { it.infoHashV1 == hash }
                ?: return Outcome.JOURNAL_BLOCKED
            when (entry.phase) {
                SnapshotRestoreJournalV1.Phase.REVIEW ->
                    journal.prepare(hash, verifiedPlaceFingerprint)
                SnapshotRestoreJournalV1.Phase.READY ->
                    if (entry.placeFingerprint != verifiedPlaceFingerprint)
                        return Outcome.JOURNAL_BLOCKED
                else -> return Outcome.JOURNAL_BLOCKED
            }
            // Durably commit before making *any* native mutation.
            journal.beforeAdd(hash, verifiedPlaceFingerprint)
        } catch (_: Exception) {
            return Outcome.JOURNAL_BLOCKED
        }

        val acknowledged = try {
            target.submitStopped(reviewed.bytes.copyOf(), chosenPlace)
        } catch (_: Exception) { false }
        if (acknowledged) {
            // A successful command return is still not an independently
            // observed postcondition; journal separately records that fact.
            try { journal.acknowledged(hash) } catch (_: Exception) { }
        }
        val after = canonicalInventory(try { target.completeHashes() }
            catch (_: Exception) { null } ?: return Outcome.UNCERTAIN)
            ?: return Outcome.UNCERTAIN
        if (hash !in after) return Outcome.UNCERTAIN
        // Verifying identity and stopped state without the actual *new* Place
        // could claim success after a concurrent Add or native path drift.
        val verifiedPlace = try { target.observedStoppedPlace(hash)?.canonicalPath }
            catch (_: Exception) { null }
        val expectedPlace = try { chosenPlace.canonicalPath }
            catch (_: Exception) { null }
        if (verifiedPlace == null || verifiedPlace != expectedPlace)
            return Outcome.UNCERTAIN
        // Presence does not establish downloaded payload or completed files.
        try { journal.reconcileWithCompleteTarget(after, true) }
        catch (_: Exception) { return Outcome.UNCERTAIN }
        return if (journal.entries().singleOrNull { it.infoHashV1 == hash }?.phase ==
            SnapshotRestoreJournalV1.Phase.VERIFIED) Outcome.VERIFIED_PRESENT_STOPPED
        else Outcome.UNCERTAIN
    }

    private fun canonicalInventory(values: List<String>): Set<String>? {
        if (values.size > 20_000) return null
        val canonical = values.map { it.lowercase(Locale.ROOT) }
        if (canonical.size != canonical.toSet().size ||
            canonical.any { !TARGET_HASH.matches(it) }) return null
        return canonical.toSet()
    }

    private val TARGET_HASH = Regex("[0-9a-f]{40}([0-9a-f]{24})?")
}
