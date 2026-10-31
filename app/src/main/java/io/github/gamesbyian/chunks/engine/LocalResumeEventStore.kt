package io.github.gamesbyian.chunks.engine

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Append-only, crash-durable private resume records. Existing directory entries
 * are NEVER opened for writing, replaced, renamed or unlinked. In particular,
 * a foreign hardlink at <hash>.resume or <hash>.resume.tmp is never touched.
 *
 * REMOVE_INTENT is synced BEFORE asynchronous native remove. An interrupted
 * removal cannot restore a retired torrent; a completed remove permits a new
 * same-hash generation. Interrupted/torn records block restoration, never
 * authorize rollback to an earlier snapshot. This store does NOT confer native
 * payload-file ownership. Full-disk failure refuses a mutation.
 *
 * Historical event files are deliberately retained. Safe bounded compaction
 * requires its own inode-identity and crash-recovery protocol, not a pathname
 * delete; do not implement blind retention cleanup.
 */
internal class LocalResumeEventStore private constructor(
    private val directory: File,
    private val states: MutableMap<String, State>,
    private val failures: MutableList<String>
) {
    private enum class Phase { EMPTY, SAVED, REMOVE_INTENT, REMOVED, CORRUPT }
    private data class State(
        val phase: Phase,
        val sequence: Long = 0L,
        val previousSha: String = ZERO,
        val payload: ByteArray? = null
    )
    private data class Record(
        val key: String,
        val sequence: Long,
        val kind: String,
        val previousSha: String,
        val payload: ByteArray,
        val digest: String
    )

    companion object {
        private const val MAGIC = "CHUNKS-RESUME-V2"
        private const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        private const val MAX_RECORD_BYTES = MAX_PAYLOAD_BYTES + 512L
        private const val MAX_SEQUENCE = 999999999999L
        private val ZERO = "0".repeat(64)
        private val HASH = Regex("(?:[a-f0-9]{40}|[a-f0-9]{64})")
        private val EVENT = Regex("^([a-f0-9]{40}|[a-f0-9]{64})\\.resume-event\\.([0-9]{12})$")
        private val LEGACY = Regex("^([a-f0-9]{40}|[a-f0-9]{64})\\.resume$")

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun format(key: String, sequence: Long, kind: String, previousSha: String, payload: ByteArray): ByteArray {
            val header = "$MAGIC\n$key\n$sequence\n$kind\n$previousSha\n${digest(payload)}\n${payload.size}\n"
                .toByteArray(Charsets.US_ASCII)
            return header + payload
        }

        private fun parse(raw: ByteArray, key: String, sequence: Long): Record? = try {
            var cursor = 0
            fun line(): String {
                val end = raw.indexOfFirstFrom(cursor) { it == 10.toByte() }
                if (end < 0 || end - cursor > 128) throw IOException("Bad record header")
                val text = raw.copyOfRange(cursor, end).toString(Charsets.US_ASCII)
                cursor = end + 1
                return text
            }
            if (line() != MAGIC || line() != key || line() != sequence.toString()) return null
            val kind = line()
            if (kind !in setOf("SAVE", "REMOVE_INTENT", "REMOVED")) return null
            val prev = line()
            val sha = line()
            if (!prev.matches(Regex("[0-9a-f]{64}")) || !sha.matches(Regex("[0-9a-f]{64}"))) return null
            val size = line().toIntOrNull() ?: return null
            if (size !in 0..MAX_PAYLOAD_BYTES || size != raw.size - cursor) return null
            if (kind != "SAVE" && size != 0 || kind == "SAVE" && size == 0) return null
            val bytes = raw.copyOfRange(cursor, raw.size)
            if (sha != digest(bytes)) return null
            Record(key, sequence, kind, prev, bytes, digest(raw))
        } catch (_: Exception) { null }

        private inline fun ByteArray.indexOfFirstFrom(from: Int, predicate: (Byte) -> Boolean): Int {
            for (i in from until size) if (predicate(this[i])) return i
            return -1
        }

        private fun syncParent(directory: File) {
            val android = try { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q }
                catch (_: NoClassDefFoundError) { false }
            if (android) {
                val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or
                    OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
                try {
                    if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode))
                        throw IOException("Not an owned state directory")
                    Os.fsync(fd)
                } finally { Os.close(fd) }
            } else {
                FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
            }
        }

        fun open(directory: File, inventory: List<File>): LocalResumeEventStore {
            require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(directory.toPath()))
            val states = LinkedHashMap<String, State>()
            val failures = ArrayList<String>()
            val groups = LinkedHashMap<String, MutableList<Pair<Long, File>>>()
            val legacies = LinkedHashMap<String, File>()
            val corrupt = HashSet<String>()
            for (file in inventory) {
                val name = file.name
                val event = EVENT.matchEntire(name)
                val legacy = LEGACY.matchEntire(name)
                when {
                    event != null -> {
                        val key = event.groupValues[1]
                        val seq = event.groupValues[2].toLongOrNull()
                        if (seq == null || seq == 0L) corrupt += key
                        else groups.getOrPut(key) { ArrayList() } += seq to file
                    }
                    legacy != null -> legacies[legacy.groupValues[1]] = file
                    else -> {
                        // Fail closed on malformed event names for an otherwise
                        // valid hash, rather than silently restoring older bytes.
                        val key = name.substringBefore(".resume-event.")
                        if (HASH.matches(key) && name.startsWith("$key.resume-event.")) corrupt += key
                    }
                }
            }
            for (key in (groups.keys + legacies.keys + corrupt).toSortedSet()) {
                var state = State(Phase.EMPTY)
                var broken = key in corrupt
                for ((seq, file) in groups[key].orEmpty().sortedBy { it.first }) {
                    if (broken) break
                    if (seq != state.sequence + 1L) { broken = true; break }
                    val raw = LocalResumeFileReader.read(file, MAX_RECORD_BYTES)
                    val record = raw?.let { parse(it, key, seq) }
                    if (record == null || record.previousSha != state.previousSha) {
                        broken = true
                        break
                    }
                    val next = when (record.kind) {
                        "SAVE" -> if (state.phase in setOf(Phase.EMPTY, Phase.SAVED, Phase.REMOVED))
                            Phase.SAVED else null
                        "REMOVE_INTENT" -> if (state.phase == Phase.SAVED) Phase.REMOVE_INTENT else null
                        "REMOVED" -> if (state.phase == Phase.REMOVE_INTENT) Phase.REMOVED else null
                        else -> null
                    }
                    if (next == null) { broken = true; break }
                    state = State(next, seq, record.digest, record.payload.takeIf { next == Phase.SAVED })
                }
                if (broken) {
                    failures += "$key.resume-event (unsafe/incomplete history)"
                    states[key] = State(Phase.CORRUPT)
                } else if (state.phase == Phase.EMPTY && legacies.containsKey(key)) {
                    val bytes = LocalResumeFileReader.read(legacies.getValue(key), MAX_PAYLOAD_BYTES.toLong())
                    if (bytes == null) {
                        failures += "$key.resume (invalid legacy snapshot)"
                        states[key] = State(Phase.CORRUPT)
                    } else states[key] = State(Phase.SAVED, payload = bytes)
                } else states[key] = state
            }
            return LocalResumeEventStore(directory, states, failures)
        }
    }

    @Synchronized fun canRegister(key: String): Boolean {
        require(HASH.matches(key))
        return states[key]?.phase in setOf(null, Phase.EMPTY, Phase.REMOVED)
    }

    @Synchronized fun restorable(): List<Pair<String, ByteArray>> =
        states.entries.mapNotNull { (key, state) ->
            if (state.phase == Phase.SAVED) state.payload?.let { key to it } else null
        }

    @Synchronized fun problems(): List<String> = failures.toList()

    @Synchronized private fun append(key: String, kind: String, payload: ByteArray) {
        require(HASH.matches(key))
        if (payload.size > MAX_PAYLOAD_BYTES) throw IOException("Resume record exceeds safety limit")
        val previous = states[key] ?: State(Phase.EMPTY)
        val nextPhase = when (kind) {
            "SAVE" -> if (previous.phase in setOf(Phase.EMPTY, Phase.SAVED, Phase.REMOVED) && payload.isNotEmpty())
                Phase.SAVED else null
            "REMOVE_INTENT" -> if (previous.phase == Phase.SAVED && payload.isEmpty())
                Phase.REMOVE_INTENT else null
            "REMOVED" -> if (previous.phase == Phase.REMOVE_INTENT && payload.isEmpty())
                Phase.REMOVED else null
            else -> null
        } ?: throw IOException("Unsafe resume event transition: $kind after ${previous.phase}")
        if (previous.sequence >= MAX_SEQUENCE) throw IOException("Resume generation exhausted")
        val seq = previous.sequence + 1
        val file = File(directory, "$key.resume-event.${seq.toString().padStart(12, '0')}")
        val raw = format(key, seq, kind, previous.previousSha, payload)
        // CREATE_NEW opens a single fresh descriptor, never the name of an
        // existing regular file, FIFO, symlink, or same-UID foreign hardlink.
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(raw)
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) throw IOException("Short resume journal write")
            }
            channel.force(true)
        }
        // If directory sync fails the entry is indeterminate. Never delete or
        // re-use its name; the next startup will inspect and fail closed.
        syncParent(directory)
        states[key] = State(nextPhase, seq, digest(raw), payload.takeIf { nextPhase == Phase.SAVED })
    }

    @Synchronized fun save(key: String, bytes: ByteArray) = append(key, "SAVE", bytes)
    @Synchronized fun removeIntent(key: String) = append(key, "REMOVE_INTENT", ByteArray(0))
    @Synchronized fun removeCommit(key: String) = append(key, "REMOVED", ByteArray(0))
}
