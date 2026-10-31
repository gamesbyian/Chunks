package io.github.gamesbyian.chunks.engine

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * NON-SHIPPING native resume lifecycle research, deliberately disconnected
 * from LocalTorrentEngine until its production admission/remove paths are
 * redesigned and independently accepted.
 *
 * A save creates an immutable, uniquely named, fsynced event. It never opens
 * an old resume filename for writing, calls REPLACE_EXISTING, unlinks a stale
 * generation, or treats a matching pathname as ownership. Remove becomes two
 * durable events (intent before native remove, commit after acknowledgement).
 * Any interrupted event, duplicate sequence, mutated byte, symlink, FIFO or
 * conflicting generation makes recovery UNCERTAIN rather than replaying an
 * old torrent handle. A completed removal cannot resurrect on restart.
 *
 * The caller MUST provide an app-private, exclusively controlled state root.
 * This is not a generic Downloads/SAF writer, a payload ownership proof, a
 * transactional cross-process DB or a release fix for #568. The explicit
 * reviewed Add API must be called only after a new real user/engine review.
 */
internal object ImmutableNativeResumeV2 {
    private const val VERSION = "CHUNKS_RESUME_V2"
    private const val DIR = "immutable-resume-v2"
    private const val MAX_EVENTS = 256
    private const val MAX_RESUME_BYTES = 16 * 1024 * 1024
    private const val MAX_RECORD_BYTES = 24L * 1024 * 1024
    private const val MAX_HISTORY_BYTES = 128L * 1024 * 1024
    private val HASH = Regex("[0-9a-f]{40}([0-9a-f]{24})?")
    private val EPOCH = Regex("[0-9a-f]{32}")
    private val DIGEST = Regex("[0-9a-f]{64}")
    private val FILE = Regex("([0-9a-f]{40}(?:[0-9a-f]{24})?)\\.r2\\.([0-9]{6})\\.([0-9a-f]{32})\\.event")
    private val ZERO = "0".repeat(64)

    enum class Phase { ADMIT, SNAPSHOT, REMOVE_INTENT, REMOVE_COMMIT }

    sealed class Inspection {
        object Absent : Inspection()
        data class Active(val epoch: String, val snapshot: ByteArray?) : Inspection()
        data class Removed(val epoch: String) : Inspection()
        data class Uncertain(val reason: String) : Inspection()
    }

    private data class Event(
        val sequence: Int,
        val phase: Phase,
        val epoch: String,
        val payload: ByteArray?,
        val previous: String,
        val checksum: String,
        val storedBytes: Long = 0L
    )

    private data class Chain(
        val events: List<Event>,
        val lastSnapshot: ByteArray?,
        val phase: Phase?,
        val epoch: String?,
        val storedBytes: Long
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun validateHash(hash: String) {
        require(HASH.matches(hash)) { "Canonical infohash required" }
    }

    private fun root(privateStateRoot: Path): Path {
        // The caller's root is pre-authorized app-private state, never a picker
        // path. Do not follow its final symlink even in this test-only module.
        if (!Files.isDirectory(privateStateRoot, LinkOption.NOFOLLOW_LINKS))
            throw IOException("Native resume state root is unavailable")
        return privateStateRoot.resolve(DIR)
    }

    private fun existingRoot(privateStateRoot: Path): Path? {
        val dir = root(privateStateRoot)
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            throw IOException("Immutable resume directory is not a real directory")
        return dir
    }

    private fun ensureRoot(privateStateRoot: Path): Path {
        val dir = root(privateStateRoot)
        try {
            Files.createDirectory(dir)
            FileChannel.open(privateStateRoot, StandardOpenOption.READ)
                .use { it.force(true) }
        } catch (_: FileAlreadyExistsException) {
            // Another app-owned writer may have created this real directory.
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            throw IOException("Immutable resume root was replaced")
        return dir
    }

    private fun decode(hash: String, path: Path, bytes: ByteArray): Event {
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_RECORD_BYTES)
            throw IOException("Invalid immutable resume record length")
        val text = bytes.toString(Charsets.US_ASCII)
        if (!text.endsWith("\n")) throw IOException("Incomplete immutable resume record")
        val fields = text.dropLast(1).split('\t')
        if (fields.size != 8 || fields[0] != VERSION || fields[1] != hash)
            throw IOException("Unrecognized resume record header")
        val seq = fields[2].toIntOrNull()?.takeIf { it in 1..MAX_EVENTS }
            ?: throw IOException("Invalid resume generation")
        val name = FILE.matchEntire(path.fileName.toString())
            ?: throw IOException("Unexpected resume record name")
        if (name.groupValues[1] != hash || name.groupValues[2].toIntOrNull() != seq)
            throw IOException("Resume record identity/sequence disagree")
        val phase = Phase.entries.singleOrNull { it.name == fields[3] }
            ?: throw IOException("Unknown resume lifecycle transition")
        val epoch = fields[4].takeIf { EPOCH.matches(it) }
            ?: throw IOException("Invalid resume generation epoch")
        val prior = fields[5].takeIf { DIGEST.matches(it) }
            ?: throw IOException("Invalid resume predecessor hash")
        if (!DIGEST.matches(fields[7]) ||
            sha256(fields.take(7).joinToString("\t").toByteArray(Charsets.US_ASCII)) != fields[7])
            throw IOException("Immutable resume event checksum mismatch")
        if (fields[6].length > (MAX_RESUME_BYTES * 4 / 3 + 16))
            throw IOException("Encoded resume payload exceeds limit")
        val payload = if (fields[6] == "-") null else try {
            Base64.getUrlDecoder().decode(fields[6]).also {
                if (it.isEmpty() || it.size > MAX_RESUME_BYTES)
                    throw IOException("Oversized or empty resume payload")
            }
        } catch (_: IllegalArgumentException) {
            throw IOException("Invalid encoded resume payload")
        }
        if ((phase == Phase.SNAPSHOT) != (payload != null))
            throw IOException("Resume lifecycle event has impossible payload")
        return Event(seq, phase, epoch, payload, prior, fields[7], bytes.size.toLong())
    }

    private fun load(privateStateRoot: Path, hash: String): Chain {
        validateHash(hash)
        val dir = existingRoot(privateStateRoot)
            ?: return Chain(emptyList(), null, null, null, 0L)
        val candidates = ArrayList<Path>()
        Files.newDirectoryStream(dir).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                if (!name.startsWith("$hash.r2.")) continue
                if (!FILE.matches(name)) throw IOException("Unknown native resume event")
                candidates.add(entry)
                if (candidates.size > MAX_EVENTS)
                    throw IOException("Immutable resume journal needs user attention")
            }
        }
        val events = candidates.map { file ->
            val bytes = LocalResumeFileReader.read(file.toFile(), MAX_RECORD_BYTES)
                ?: throw IOException("Unreadable or substituted immutable resume record")
            decode(hash, file, bytes)
        }.sortedBy { it.sequence }
        var prior = ZERO
        var epoch: String? = null
        var last: Phase? = null
        var snapshot: ByteArray? = null
        var consumed = 0L
        for ((index, event) in events.withIndex()) {
            // Count the exact bytes returned by the already-pinned read.
            // A second pathname stat could observe a substituted inode.
            consumed += event.storedBytes
            if (consumed > MAX_HISTORY_BYTES)
                throw IOException("Immutable resume history is over its safe cap")
            if (event.sequence != index + 1 || event.previous != prior)
                throw IOException("Duplicate, missing or displaced resume generation")
            when (event.phase) {
                Phase.ADMIT -> {
                    if (last != null && last != Phase.REMOVE_COMMIT)
                        throw IOException("Add without a completed previous removal")
                    if (epoch == event.epoch)
                        throw IOException("Reused resume lifecycle epoch")
                    epoch = event.epoch
                    snapshot = null
                }
                Phase.SNAPSHOT -> {
                    if (last !in listOf(Phase.ADMIT, Phase.SNAPSHOT) || event.epoch != epoch)
                        throw IOException("Resume snapshot without active reviewed Add")
                    snapshot = event.payload
                }
                Phase.REMOVE_INTENT -> {
                    if (last !in listOf(Phase.ADMIT, Phase.SNAPSHOT) || event.epoch != epoch)
                        throw IOException("Unreviewed removal intent")
                }
                Phase.REMOVE_COMMIT -> {
                    if (last != Phase.REMOVE_INTENT || event.epoch != epoch)
                        throw IOException("Removal completion without a durable intent")
                    snapshot = null
                }
            }
            last = event.phase
            prior = event.checksum
        }
        return Chain(events, snapshot, last, epoch, consumed)
    }

    private fun encode(hash: String, event: Event): ByteArray {
        val payload = event.payload?.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        } ?: "-"
        val first = listOf(VERSION, hash, event.sequence.toString(), event.phase.name,
            event.epoch, event.previous, payload).joinToString("\t")
        return (first + "\t" + sha256(first.toByteArray(Charsets.US_ASCII)) + "\n")
            .toByteArray(Charsets.US_ASCII)
    }

    private fun append(
        privateStateRoot: Path,
        hash: String,
        previous: Chain,
        phase: Phase,
        epoch: String,
        bytes: ByteArray? = null
    ) {
        if (previous.events.size >= MAX_EVENTS)
            throw IOException("Resume journal reached the immutable generation limit")
        if (bytes != null && (bytes.isEmpty() || bytes.size > MAX_RESUME_BYTES))
            throw IOException("Resume data exceeds safe immutable write bound")
        val seq = previous.events.size + 1
        val event = Event(seq, phase, epoch, bytes,
            previous.events.lastOrNull()?.checksum ?: ZERO, "")
        val encoded = encode(hash, event)
        if (encoded.size.toLong() > MAX_RECORD_BYTES)
            throw IOException("Immutable resume event exceeds maximum record size")
        // Count each actual fsynced record, not only decoded payload bytes.
        // The read-only loader already bounds and verifies that total.
        if (previous.storedBytes + encoded.size > MAX_HISTORY_BYTES)
            throw IOException("Immutable resume history exceeds safe storage budget")
        val dir = ensureRoot(privateStateRoot)
        for (attempt in 1..16) {
            val nonce = UUID.randomUUID().toString().replace("-", "")
            val name = "$hash.r2." + seq.toString().padStart(6, '0') +
                ".$nonce.event"
            val file = dir.resolve(name)
            val output = try {
                FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            } catch (_: FileAlreadyExistsException) { continue }
            output.use { channel ->
                val body = ByteBuffer.wrap(encoded)
                while (body.hasRemaining()) {
                    if (channel.write(body) <= 0) throw IOException("Resume write made no progress")
                }
                channel.force(true)
            }
            // A visible complete event is still not committed until its parent
            // entry has been fsynced. Never clean up an uncertain final name.
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
            // A successful write+fsync is not yet an observed restore receipt.
            // Reopen the exact new immutable generation independently through
            // the pinned bounded reader, then validate the full chain. No
            // pathname cleanup or blind retry on an uncertain result.
            val readback = load(privateStateRoot, hash)
            val latest = readback.events.lastOrNull()
            if (readback.events.size != seq || latest?.phase != phase ||
                latest.epoch != epoch || latest.sequence != seq ||
                !(latest.payload?.contentEquals(bytes ?: ByteArray(0))
                    ?: (bytes == null))) {
                throw IOException("Immutable resume event failed independent durable readback")
            }
            return
        }
        throw IOException("Could not exclusively allocate resume generation")
    }

    fun inspect(privateStateRoot: Path, hash: String): Inspection = try {
        val chain = load(privateStateRoot, hash)
        when (chain.phase) {
            null -> Inspection.Absent
            Phase.ADMIT, Phase.SNAPSHOT ->
                Inspection.Active(requireNotNull(chain.epoch), chain.lastSnapshot?.copyOf())
            Phase.REMOVE_COMMIT -> Inspection.Removed(requireNotNull(chain.epoch))
            Phase.REMOVE_INTENT -> Inspection.Uncertain("Removal may have been submitted")
        }
    } catch (_: Exception) {
        Inspection.Uncertain("Resume history cannot be proven; re-review required")
    }

    /** Explicitly reviewed Add only, including a re-Add after completed remove. */
    @Synchronized fun beginReviewed(privateStateRoot: Path, hash: String): String {
        val chain = load(privateStateRoot, hash)
        if (chain.phase != null && chain.phase != Phase.REMOVE_COMMIT)
            throw IOException("Existing or uncertain transfer cannot be silently re-admitted")
        val epoch = UUID.randomUUID().toString().replace("-", "")
        append(privateStateRoot, hash, chain, Phase.ADMIT, epoch)
        return epoch
    }

    /** Native alert bytes only; an unowned state root is never accepted. */
    @Synchronized fun save(privateStateRoot: Path, hash: String, bytes: ByteArray) {
        val chain = load(privateStateRoot, hash)
        if (chain.phase !in listOf(Phase.ADMIT, Phase.SNAPSHOT))
            throw IOException("Resume has no active reviewed generation")
        append(privateStateRoot, hash, chain, Phase.SNAPSHOT, requireNotNull(chain.epoch), bytes)
    }

    /** MUST be durably called before a non-blocking native session.remove. */
    @Synchronized fun requestRemoval(privateStateRoot: Path, hash: String) {
        val chain = load(privateStateRoot, hash)
        if (chain.phase !in listOf(Phase.ADMIT, Phase.SNAPSHOT))
            throw IOException("Cannot remove an unknown/inactive native generation")
        append(privateStateRoot, hash, chain, Phase.REMOVE_INTENT, requireNotNull(chain.epoch))
    }

    /** MUST be durably called only after native remove is acknowledged. */
    @Synchronized fun completeRemoval(privateStateRoot: Path, hash: String) {
        val chain = load(privateStateRoot, hash)
        if (chain.phase != Phase.REMOVE_INTENT)
            throw IOException("No durable removal intent to complete")
        append(privateStateRoot, hash, chain, Phase.REMOVE_COMMIT, requireNotNull(chain.epoch))
    }
}
