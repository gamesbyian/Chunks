package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.LocalEngineHolder
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots

/**
 * Supplies an exact transfer's already-known file list as *local classification evidence*.
 *
 * A multi-file torrent is usually named after its folder ("Hackers (1995)"), which alone
 * cannot distinguish a film from a photo set or archive. Its file composition (".mkv",
 * episode-numbered files, ".flac", ...) can. File paths only influence the local
 * [MediaRecognizer] kind/confidence: provider queries are built from the transfer name's
 * own title/year/season/episode ([MediaQuerySanitizer.fromRecognition]), so paths and
 * filenames are never transmitted. Reading never starts an engine, network or disk I/O.
 */
fun interface MediaFileEvidenceSource {
    /**
     * Call off the main thread and only for a currently authorized exact key. Null means
     * "not known yet" (no engine, unknown job, magnet metadata pending) and is retried later.
     */
    fun files(key: MediaTransferKey): List<MediaFileHint>?

    companion object {
        val NONE = MediaFileEvidenceSource { null }
    }
}

/**
 * The local engine's own in-memory torrent file list for exact `local|<infohash>` keys.
 * Remote rTorrent keys return null: their file lists need an authenticated RPC read that
 * belongs to the inventory host, never to this media component.
 */
internal object LocalEngineMediaFileEvidence : MediaFileEvidenceSource {
    /** Composition needs a representative sample, not every file of a 50,000-file archive. */
    const val MAX_FILES = 4_096
    private val prefix = EnvironmentSnapshots.LOCAL.value + "|"
    private val hash = Regex("[0-9a-f]{40}(?:[0-9a-f]{24})?")

    override fun files(key: MediaTransferKey): List<MediaFileHint>? {
        if (!key.value.startsWith(prefix)) return null
        val infoHash = key.value.substring(prefix.length).takeIf { hash.matches(it) } ?: return null
        // current() never starts the engine or its listening socket.
        val engine = LocalEngineHolder.current() ?: return null
        return runCatching { engine.files(infoHash, MAX_FILES) }.getOrNull()
            ?.map { MediaFileHint(it.path, it.sizeBytes) }
    }
}

/**
 * Bounded memo of file-informed recognition per exact transfer and original name. A torrent's
 * file list is fixed by its infohash, so one successful read per process suffices; a renamed
 * original (different name) is recomputed. In memory only: nothing about files is persisted.
 */
internal class MediaFileEvidenceCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private class Entry(val name: String, val recognition: MediaRecognition)

    private val entries = object : LinkedHashMap<MediaTransferKey, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaTransferKey, Entry>) = size > maxEntries
    }

    @Synchronized
    fun get(key: MediaTransferKey, name: String): MediaRecognition? =
        entries[key]?.takeIf { it.name == name }?.recognition

    @Synchronized
    fun put(key: MediaTransferKey, name: String, recognition: MediaRecognition) {
        entries[key] = Entry(name, recognition)
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        /** Matches the bounded Similar Transfers inventory; one recognition is a few hundred bytes. */
        const val DEFAULT_MAX_ENTRIES = 5_000
    }
}
