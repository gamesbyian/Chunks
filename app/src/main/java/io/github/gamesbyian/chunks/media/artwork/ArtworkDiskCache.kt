package io.github.gamesbyian.chunks.media.artwork

import io.github.gamesbyian.chunks.media.Json
import java.io.File

/**
 * App-private, bounded LRU store of *processed* artwork derivatives (never original payload files).
 * Files are named by digest only, so the directory listing leaks no titles. Pinned keys (user-chosen
 * or imported art) are exempt from eviction. Also holds a small negative cache for failed URLs.
 */
class ArtworkDiskCache(
    private val dir: File,
    private val maxBytes: Long = 48L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val negativeFile = File(dir, "negative.json")
    private val pinsFile = File(dir, "pins.json")
    private val negative = HashMap<String, Long>()
    private val pinned = HashSet<String>()

    private var loaded = false

    /** Disk is touched only on first real use (never during construction or composition). */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        dir.mkdirs()
        runCatching {
            if (negativeFile.isFile && negativeFile.length() < 1_000_000) Json.parse(negativeFile.readText()).obj.forEach { (k, v) ->
                v.long?.let { negative[k] = it }
            }
        }
        runCatching {
            if (pinsFile.isFile && pinsFile.length() < 1_000_000) Json.parse(pinsFile.readText()).array.forEach { j ->
                j.string?.takeIf { it.matches(Regex("[0-9a-f]{16,64}")) }?.let { pinned.add(it) }
            }
        }
    }

    private fun file(key: String): File {
        require(key.matches(Regex("[0-9a-f]{16,64}"))) { "Cache keys are digests" }
        return File(dir, "$key.img")
    }

    @Synchronized fun get(key: String): ByteArray? {
        ensureLoaded()
        val f = file(key)
        if (!f.isFile) return null
        f.setLastModified(clock())
        return runCatching { f.readBytes() }.getOrNull()
    }

    @Synchronized fun contains(key: String): Boolean { ensureLoaded(); return file(key).isFile }

    /**
     * Fast fail-closed check for a previously encoded derivative. A zero-length,
     * truncated or non-JPEG cache file must not suppress offscreen poster retry.
     * Chunks writes every processed derivative with JPEG SOI/EOI markers; this
     * check reads only five bytes, without decoding thousands of offscreen images.
     * A successful check is plausibility, not proof the middle of the image decodes:
     * loadOne() remains the authoritative full decoder on display.
     */
    @Synchronized fun hasPlausibleProcessedJpeg(key: String): Boolean {
        ensureLoaded()
        val f = file(key)
        if (!f.isFile || f.length() < 64L) return false
        return runCatching {
            java.io.RandomAccessFile(f, "r").use { input ->
                val header = ByteArray(3)
                input.readFully(header)
                input.seek(input.length() - 2L)
                val tail = ByteArray(2)
                input.readFully(tail)
                header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() &&
                    header[2] == 0xFF.toByte() &&
                    tail[0] == 0xFF.toByte() && tail[1] == 0xD9.toByte()
            }
        }.getOrDefault(false)
    }

    @Synchronized fun put(key: String, bytes: ByteArray) {
        ensureLoaded()
        val f = file(key)
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        f.setLastModified(clock())
        negative.remove(key)
        trim()
    }

    /** Pinned (user-chosen/imported) derivatives survive eviction and restarts until unpinned. */
    @Synchronized fun pin(key: String) { ensureLoaded(); if (pinned.add(key)) savePins() }
    @Synchronized fun unpin(key: String) { ensureLoaded(); if (pinned.remove(key)) savePins() }
    @Synchronized fun isPinned(key: String): Boolean { ensureLoaded(); return key in pinned }

    @Synchronized fun remove(key: String) { ensureLoaded(); file(key).delete(); if (pinned.remove(key)) savePins() }

    @Synchronized fun markFailed(key: String, ttlMs: Long) {
        ensureLoaded()
        negative[key] = clock() + ttlMs
        if (negative.size > 2_000) negative.entries.sortedBy { it.value }.take(500).forEach { negative.remove(it.key) }
        saveNegative()
    }

    @Synchronized fun isNegative(key: String): Boolean {
        ensureLoaded()
        val until = negative[key] ?: return false
        if (clock() >= until) { negative.remove(key); return false }
        return true
    }

    @Synchronized fun sizeBytes(): Long { ensureLoaded(); return images().sumOf { it.length() } }

    @Synchronized fun clear() {
        ensureLoaded()
        // Explicit user purge: assignments are cleared together, so pinned derivatives go too.
        images().forEach { it.delete() }
        pinned.clear(); negative.clear(); saveNegative(); savePins()
    }

    private fun images() = dir.listFiles { f -> f.isFile && f.name.endsWith(".img") }?.toList() ?: emptyList()

    private fun trim() {
        var total = images().sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in images().sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (f.nameWithoutExtension in pinned) continue
            total -= f.length(); f.delete()
        }
    }

    private fun savePins() {
        val tmp = File(dir, "pins.json.tmp")
        tmp.writeText(Json.Arr(pinned.sorted().map { Json.Str(it) }).encode())
        if (!tmp.renameTo(pinsFile)) { pinsFile.delete(); tmp.renameTo(pinsFile) }
    }

    private fun saveNegative() {
        val tmp = File(dir, "negative.json.tmp")
        tmp.writeText(Json.Obj(negative.mapValues { Json.Num(it.value.toString()) }).encode())
        if (!tmp.renameTo(negativeFile)) { negativeFile.delete(); tmp.renameTo(negativeFile) }
    }
}
