package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.model.MediaMetadataCacheKey
import io.github.gamesbyian.chunks.model.MediaMetadataCacheReceipt
import java.io.File
import java.security.MessageDigest

/** A cached provider answer for one sanitized query: receipt + (for VERIFIED) normalized candidates. */
data class CachedLookup(val receipt: MediaMetadataCacheReceipt, val candidates: List<MediaCandidate>)

interface MediaQueryCache {
    fun get(key: MediaMetadataCacheKey): CachedLookup?
    fun put(entry: CachedLookup)
    fun clear()
    val size: Int
}

object MediaQueryKeys {
    /** The query text itself is never a cache filename or key: only a digest of its canonical form. */
    fun of(provider: String, query: MediaLookupQuery): MediaMetadataCacheKey {
        val digest = MessageDigest.getInstance("SHA-256").digest(query.canonical.toByteArray(Charsets.UTF_8))
        return MediaMetadataCacheKey(provider, "query", digest.take(16).joinToString("") { "%02x".format(it) })
    }
}

/** Bounded LRU (by observation time) cache; optionally persisted to one app-private JSON file. */
open class InMemoryMediaQueryCache(private val maxEntries: Int = 600) : MediaQueryCache {
    protected val entries = LinkedHashMap<MediaMetadataCacheKey, CachedLookup>()
    @Synchronized override fun get(key: MediaMetadataCacheKey) = entries[key]
    @Synchronized override fun put(entry: CachedLookup) {
        entries.remove(entry.receipt.key)
        entries[entry.receipt.key] = entry
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
        persist()
    }
    @Synchronized override fun clear() { entries.clear(); persist() }
    override val size: Int @Synchronized get() = entries.size
    protected open fun persist() {}
}

class FileMediaQueryCache(private val file: File, maxEntries: Int = 600) : InMemoryMediaQueryCache(maxEntries) {
    init {
        runCatching {
            if (file.isFile && file.length() < 4_000_000) {
                Json.parse(file.readText())["entries"]?.array?.forEach { e ->
                    val r = MediaCodec.receipt(e["r"]) ?: return@forEach
                    entries[r.key] = CachedLookup(r, e["c"]?.array?.mapNotNull { MediaCodec.candidate(it) } ?: emptyList())
                }
            }
        }
    }

    override fun persist() {
        val json = Json.obj("v" to Json.num(1), "entries" to Json.arr(entries.values.map {
            Json.obj("r" to MediaCodec.receipt(it.receipt), "c" to Json.arr(it.candidates.map(MediaCodec::candidate)))
        }))
        MediaFiles.atomicWrite(file, json.encode())
    }
}

internal object MediaFiles {
    /** Write-then-rename within app-private storage, so a crash never leaves a truncated store. */
    fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
