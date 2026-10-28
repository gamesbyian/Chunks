package io.github.gamesbyian.chunks.snapshots

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Crash-safe state ledger for an explicitly reviewed missing-only snapshot import.
 *
 * This class NEVER calls a torrent engine. Every potentially mutating Add must be
 * preceded by a successful durable IN_FLIGHT write, including on the local engine.
 * An interrupted or unacknowledged Add remains uncertain after restart. Absence in
 * a later inventory is not permission to repeat it without fresh user approval.
 *
 * Persisted content contains only v1 infohashes and SHA-256 fingerprints of the
 * archive, destination engine/profile and approved Place. No URLs, passkeys,
 * credentials, torrent definitions or historical paths are stored here.
 */
internal class SnapshotRestoreJournalV1 private constructor(
    private val store: Store,
    private val key: String,
    private var state: State
) {
    interface Store {
        fun read(key: String): String?
        /** Must durably and atomically replace a key before returning true. */
        fun commit(key: String, value: String): Boolean
    }

    enum class Phase {
        REVIEW, READY, IN_FLIGHT, ACK_UNVERIFIED, VERIFIED, REFUSED
    }

    data class Entry(
        val infoHashV1: String,
        val phase: Phase,
        /** SHA-256 of a separately verified destination Place, never an old path. */
        val placeFingerprint: String? = null
    )

    private data class State(
        val archiveFingerprint: String,
        val targetFingerprint: String,
        val entries: List<Entry>
    )

    fun entries(): List<Entry> = state.entries.toList()

    /** Only an explicitly reviewed, verified target Place can prepare an item. */
    fun prepare(infoHashV1: String, verifiedPlaceFingerprint: String) {
        checkFingerprint(verifiedPlaceFingerprint)
        change(infoHashV1, setOf(Phase.REVIEW)) {
            it.copy(phase = Phase.READY, placeFingerprint = verifiedPlaceFingerprint)
        }
    }

    /** Call synchronously BEFORE any Add. Refuse mutation if durable commit fails. */
    fun beforeAdd(infoHashV1: String, verifiedPlaceFingerprint: String) {
        checkFingerprint(verifiedPlaceFingerprint)
        change(infoHashV1, setOf(Phase.READY)) {
            require(it.placeFingerprint == verifiedPlaceFingerprint) {
                "Target Place changed; explicit review is required"
            }
            it.copy(phase = Phase.IN_FLIGHT)
        }
    }

    /** An RPC success or local Add return is not independently verified presence. */
    fun acknowledged(infoHashV1: String) {
        change(infoHashV1, setOf(Phase.IN_FLIGHT)) {
            it.copy(phase = Phase.ACK_UNVERIFIED)
        }
    }

    /**
     * Only a complete, fresh inventory of the SAME target can verify exact
     * presence. Missing results leave in-flight entries uncertain indefinitely.
     * Neither this call nor opening the journal issues a retry.
     */
    fun reconcileWithCompleteTarget(
        targetObservedHashes: Collection<String>,
        targetInventoryComplete: Boolean
    ) {
        require(targetInventoryComplete) { "Cannot reconcile against partial inventory" }
        require(targetObservedHashes.size <= 20_000) { "Target inventory is unbounded" }
        val normalized = targetObservedHashes.map { it.lowercase(Locale.ROOT) }
        require(normalized.all { TARGET_HASH.matches(it) } &&
            normalized.size == normalized.toSet().size) {
            "Invalid or duplicated target v1 identities"
        }
        val observed = normalized.toSet()
        val changed = state.entries.map {
            if (it.infoHashV1 in observed &&
                it.phase in setOf(Phase.IN_FLIGHT, Phase.ACK_UNVERIFIED)) {
                it.copy(phase = Phase.VERIFIED)
            } else it
        }
        if (changed != state.entries) save(state.copy(entries = changed))
    }

    /** No automated second Add. Both fresh absence and explicit review required. */
    fun explicitlyRetryAfterAbsence(
        infoHashV1: String,
        targetObservedHashes: Collection<String>,
        targetInventoryComplete: Boolean,
        userApproved: Boolean
    ) {
        require(userApproved) { "Explicit retry approval is required" }
        require(targetInventoryComplete) { "Partial inventory cannot prove absence" }
        require(targetObservedHashes.size <= 20_000) { "Target inventory is unbounded" }
        val normalized = targetObservedHashes.map { it.lowercase(Locale.ROOT) }
        require(normalized.all { TARGET_HASH.matches(it) } &&
            normalized.size == normalized.toSet().size &&
            infoHashV1 !in normalized) {
            "Identity present or target inventory untrustworthy"
        }
        change(infoHashV1, setOf(Phase.IN_FLIGHT, Phase.ACK_UNVERIFIED)) {
            require(it.placeFingerprint != null)
            it.copy(phase = Phase.READY)
        }
    }

    /** Refusal never modifies a target or silently deletes an existing transfer. */
    fun refuse(infoHashV1: String) {
        change(infoHashV1, setOf(Phase.REVIEW, Phase.READY)) {
            it.copy(phase = Phase.REFUSED)
        }
    }

    private fun change(
        hash: String,
        allowed: Set<Phase>,
        update: (Entry) -> Entry
    ) {
        require(V1_HASH.matches(hash)) { "Unsupported snapshot identity" }
        val index = state.entries.indexOfFirst { it.infoHashV1 == hash }
        require(index >= 0) { "Entry is not part of this reviewed batch" }
        val old = state.entries[index]
        require(old.phase in allowed) { "Unsafe import journal transition" }
        val next = state.entries.toMutableList()
        next[index] = update(old)
        save(state.copy(entries = next))
    }

    private fun save(next: State) {
        // A second UI/session may have advanced this batch. Never overwrite its
        // verified or in-flight state from an older in-memory snapshot.
        check(store.read(key) == encode(state)) {
            "Import journal changed; reopen and reconcile before any Add"
        }
        if (!store.commit(key, encode(next))) {
            throw IOException("Import journal was not committed; no Add is authorized")
        }
        state = next
    }

    companion object {
        private const val VERSION = "chunks-import-journal-v1"
        private const val MAX_ENTRIES = 512
        private const val MAX_STORED_CHARS = 55_000
        private val V1_HASH = Regex("[0-9a-f]{40}")
        private val TARGET_HASH = Regex("[0-9a-f]{40}([0-9a-f]{24})?")
        private val FINGERPRINT = Regex("[0-9a-f]{64}")

        /**
         * Reopen exactly the same archive/target batch after process death.
         * A changed roster, engine/profile, or archive fails closed. The
         * initial empty-review state is persisted before returning.
         */
        fun open(
            store: Store,
            archiveFingerprint: String,
            targetFingerprint: String,
            proposedMissingHashes: Collection<String>
        ): SnapshotRestoreJournalV1 {
            checkFingerprint(archiveFingerprint)
            checkFingerprint(targetFingerprint)
            require(proposedMissingHashes.size <= MAX_ENTRIES) { "Too many import candidates" }
            val hashes = proposedMissingHashes.toList().sorted()
            require(hashes.all { V1_HASH.matches(it) } &&
                hashes.size == hashes.toSet().size) {
                "Import candidates must be distinct canonical v1 infohashes"
            }
            val key = "chunks.restore.v1." + sha256(
                (archiveFingerprint + ":" + targetFingerprint)
                    .toByteArray(StandardCharsets.US_ASCII)
            )
            val previous = store.read(key)
            val state = if (previous == null) {
                State(archiveFingerprint, targetFingerprint,
                    hashes.map { Entry(it, Phase.REVIEW) })
            } else {
                decode(previous).also {
                    require(it.archiveFingerprint == archiveFingerprint &&
                        it.targetFingerprint == targetFingerprint &&
                        it.entries.map { entry -> entry.infoHashV1 } == hashes) {
                        "Stored import journal differs from selected archive/target"
                    }
                }
            }
            if (previous == null && !store.commit(key, encode(state))) {
                throw IOException("Could not durably initialize import journal")
            }
            return SnapshotRestoreJournalV1(store, key, state)
        }

        private fun checkFingerprint(value: String) {
            require(FINGERPRINT.matches(value)) { "Expected canonical SHA-256 fingerprint" }
        }

        private fun sha256(input: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(input)
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

        private fun encode(state: State): String {
            val body = buildString {
                append(VERSION).append('\n')
                append(state.archiveFingerprint).append('\n')
                append(state.targetFingerprint).append('\n')
                append(state.entries.size).append('\n')
                for (entry in state.entries) {
                    append(entry.infoHashV1).append('|')
                    append(entry.phase.name).append('|')
                    append(entry.placeFingerprint ?: "-").append('\n')
                }
            }
            val output = body + "sha256=" +
                sha256(body.toByteArray(StandardCharsets.UTF_8)) + "\n"
            require(output.length <= MAX_STORED_CHARS) { "Import journal too large" }
            return output
        }

        private fun decode(encoded: String): State {
            require(encoded.length in 1..MAX_STORED_CHARS && encoded.endsWith('\n')) {
                "Truncated or oversized import journal"
            }
            val tailAt = encoded.lastIndexOf("\nsha256=")
            require(tailAt >= 0) { "Import journal lacks checksum" }
            val body = encoded.substring(0, tailAt + 1)
            val receipt = encoded.substring(tailAt + "\nsha256=".length,
                encoded.length - 1)
            require(FINGERPRINT.matches(receipt) &&
                receipt == sha256(body.toByteArray(StandardCharsets.UTF_8))) {
                "Corrupt import journal"
            }
            val lines = body.dropLast(1).split('\n')
            require(lines.size >= 4 && lines[0] == VERSION) {
                "Unsupported import journal version"
            }
            checkFingerprint(lines[1])
            checkFingerprint(lines[2])
            val count = lines[3].toIntOrNull()
            require(count != null && count in 0..MAX_ENTRIES &&
                lines.size == count + 4) { "Invalid import journal entry count" }
            val entries = lines.drop(4).map { line ->
                val parts = line.split('|')
                require(parts.size == 3 && V1_HASH.matches(parts[0])) {
                    "Invalid import journal identity"
                }
                val phase = Phase.entries.singleOrNull { it.name == parts[1] }
                    ?: throw IllegalArgumentException("Unknown import phase")
                val place = if (parts[2] == "-") null else parts[2].also {
                    checkFingerprint(it)
                }
                require((phase == Phase.REVIEW && place == null) ||
                    (phase != Phase.REVIEW && phase != Phase.REFUSED && place != null) ||
                    (phase == Phase.REFUSED)) {
                    "Import phase lacks reviewed Place"
                }
                Entry(parts[0], phase, place)
            }
            require(entries.map { it.infoHashV1 } ==
                entries.map { it.infoHashV1 }.sorted() &&
                entries.map { it.infoHashV1 }.toSet().size == entries.size) {
                "Duplicate or unordered import journal identity"
            }
            return State(lines[1], lines[2], entries)
        }
    }
}
