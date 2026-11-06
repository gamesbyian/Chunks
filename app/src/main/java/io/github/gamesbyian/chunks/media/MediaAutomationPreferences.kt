package io.github.gamesbyian.chunks.media

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Explicit opt-outs are independent of the evictable assignment/artwork caches. Call off main. */
interface MediaAutomationPreferences {
    fun isExcluded(key: MediaTransferKey): Boolean
    fun setExcluded(key: MediaTransferKey, excluded: Boolean)
}

class InMemoryMediaAutomationPreferences : MediaAutomationPreferences {
    private val excluded = mutableSetOf<MediaTransferKey>()
    @Synchronized override fun isExcluded(key: MediaTransferKey) = key in excluded
    @Synchronized override fun setExcluded(key: MediaTransferKey, excluded: Boolean) {
        if (excluded) this.excluded.add(key) else this.excluded.remove(key)
    }
}

/** No automatic eviction: forgetting an opt-out must never silently restart provider requests. */
class FileMediaAutomationPreferences(private val file: File) : MediaAutomationPreferences {
    private val excluded = linkedSetOf<MediaTransferKey>()
    private var readable = true
    init {
        try {
            if (file.exists()) {
                require(file.isFile && file.length() <= 1_000_000)
                val json = Json.parse(file.readText())
                require(json["v"]?.int == 1)
                val items = requireNotNull(json["excluded"]?.array)
                items.forEach { excluded.add(MediaTransferKey(requireNotNull(it.string))) }
            }
        } catch (_: Exception) { readable = false }
    }
    @Synchronized override fun isExcluded(key: MediaTransferKey) = !readable || key in excluded
    @Synchronized override fun setExcluded(key: MediaTransferKey, excluded: Boolean) {
        var temporary: File? = null
        try {
            check(readable) { "Automatic lookup preferences need recovery" }
            val next = this.excluded.toMutableSet().apply { if (excluded) add(key) else remove(key) }
            val text = Json.obj("v" to Json.num(1), "excluded" to Json.arr(next.map { Json.Str(it.value) })).encode()
            require(text.toByteArray(Charsets.UTF_8).size <= 1_000_000)
            val parent = requireNotNull(file.absoluteFile.parentFile)
            check(parent.isDirectory || parent.mkdirs())
            val candidate = File.createTempFile("automation-", ".tmp", parent)
            temporary = candidate
            FileOutputStream(candidate).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
            check(candidate.readText() == text)
            Files.move(candidate.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            check(file.readText() == text)
            this.excluded.clear(); this.excluded.addAll(next)
        } catch (failure: Exception) {
            // Includes failures before temp creation: the reported pause must actually stop lookup.
            readable = false
            throw failure
        } finally { temporary?.delete() }
    }
}
