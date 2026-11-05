package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import java.io.File

/**
 * Stable presentation key: exact environment + verified infohash. The same hash in two
 * environments has two independent assignments (a per-transfer override never leaks across).
 */
data class MediaTransferKey(val value: String) {
    init { require(value.isNotBlank() && value.length <= 300 && value.none { it.isISOControl() }) }
    companion object {
        fun of(key: UnifiedTransferKey) = MediaTransferKey("${key.environment.value}|${key.infoHash}")
    }
}

/**
 * Local presentation metadata for one transfer. Choosing, clearing or resetting this NEVER renames
 * files, edits torrent metainfo, changes labels or issues an engine/RPC command.
 */
data class TransferMediaAssignment(
    val key: MediaTransferKey,
    val state: MediaAssignmentState,
    val match: MediaCandidate? = null,
    val suggestions: List<MediaCandidate> = emptyList(),
    /** User deliberately chose a bundled category tile (optionally a different category). */
    val categoryOverride: MediaKind? = null,
    /** Explicit user image (imported from the device); highest artwork precedence. */
    val userArtwork: ArtworkCandidate? = null,
    /** Provenance of [match] before the user's image was added, restored when the image is removed. */
    val stateBeforeImage: MediaAssignmentState? = null,
    val lastStatus: MediaLookupStatus? = null,
    val lastMessage: String? = null,
    val attempts: List<ProviderAttempt> = emptyList(),
    val updatedAtMs: Long = 0,
    val recognizerVersion: Int = MediaRecognizer.VERSION
) {
    /** Real art may be shown only for a confident automatic match or an explicit user choice. */
    val displayMatch: MediaCandidate? get() = match?.takeIf {
        state == MediaAssignmentState.AUTOMATIC || state == MediaAssignmentState.CHOSEN_BY_USER
    }
}

interface TransferMediaAssignmentStore {
    fun get(key: MediaTransferKey): TransferMediaAssignment?
    fun put(a: TransferMediaAssignment)
    fun putAll(list: Collection<TransferMediaAssignment>) = list.forEach(::put)
    fun remove(key: MediaTransferKey)
    fun clear()
    fun all(): List<TransferMediaAssignment>
}

/** One app-private JSON file, atomically rewritten; bounded to [maxEntries] most recent. */
class FileTransferMediaAssignmentStore(private val file: File, private val maxEntries: Int = 5_000) : TransferMediaAssignmentStore {
    private val map = LinkedHashMap<MediaTransferKey, TransferMediaAssignment>()

    init {
        runCatching {
            if (file.isFile && file.length() < 16_000_000) Json.parse(file.readText())["items"]?.array?.forEach { j ->
                decode(j)?.let { map[it.key] = it }
            }
        }
    }

    @Synchronized override fun get(key: MediaTransferKey) = map[key]
    @Synchronized override fun put(a: TransferMediaAssignment) {
        map.remove(a.key); map[a.key] = a
        while (map.size > maxEntries) map.remove(map.keys.first())
        save()
    }
    /** One atomic file rewrite for a batch (e.g. apply-to-related), not one per transfer. */
    @Synchronized override fun putAll(list: Collection<TransferMediaAssignment>) {
        if (list.isEmpty()) return
        list.forEach { map.remove(it.key); map[it.key] = it }
        while (map.size > maxEntries) map.remove(map.keys.first())
        save()
    }
    @Synchronized override fun remove(key: MediaTransferKey) { if (map.remove(key) != null) save() }
    @Synchronized override fun clear() { map.clear(); save() }
    @Synchronized override fun all() = map.values.toList()

    private fun save() = MediaFiles.atomicWrite(file, Json.obj("v" to Json.num(1), "items" to Json.arr(map.values.map(::encode))).encode())

    companion object {
        fun encode(a: TransferMediaAssignment): Json = Json.obj(
            "k" to Json.Str(a.key.value), "s" to Json.Str(a.state.name),
            "m" to a.match?.let(MediaCodec::candidate),
            "sg" to Json.arr(a.suggestions.map(MediaCodec::candidate)),
            "cat" to Json.str(a.categoryOverride?.name), "sbi" to Json.str(a.stateBeforeImage?.name),
            "ua" to a.userArtwork?.let { u -> Json.obj("u" to Json.Str(u.url), "w" to Json.num(u.width), "h" to Json.num(u.height),
                // A chosen provider image keeps its source and role (its cache key); imports omit both.
                "p" to Json.str(u.provider.takeIf { it != LOCAL_PROVIDER }), "r" to Json.str(u.role.name.takeIf { u.provider != LOCAL_PROVIDER })) }, "ls" to Json.str(a.lastStatus?.name), "lm" to Json.str(a.lastMessage),
            "at" to Json.arr(a.attempts.map { Json.obj("p" to Json.Str(it.provider), "o" to Json.Str(it.outcome), "c" to Json.Bool(it.fromCache)) }),
            "u" to Json.num(a.updatedAtMs), "rv" to Json.num(a.recognizerVersion))

        fun decode(j: Json): TransferMediaAssignment? = runCatching {
            TransferMediaAssignment(
                key = MediaTransferKey(j["k"]!!.string!!), state = MediaAssignmentState.valueOf(j["s"]!!.string!!),
                match = j["m"]?.let(MediaCodec::candidate),
                suggestions = j["sg"]?.array?.mapNotNull(MediaCodec::candidate) ?: emptyList(),
                categoryOverride = j["cat"]?.string?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() },
                userArtwork = j["ua"]?.let { u -> runCatching {
                    val provider = u["p"]?.string?.takeIf { it.matches(Regex("[a-z][a-z0-9-]{0,31}")) } ?: LOCAL_PROVIDER
                    val role = if (provider == LOCAL_PROVIDER) ArtworkRole.SQUARE
                        else u["r"]?.string?.let { r -> ArtworkRole.valueOf(r) } ?: ArtworkRole.SQUARE
                    ArtworkCandidate(provider, u["u"]!!.string!!, role, u["w"]?.int, u["h"]?.int) }.getOrNull() },
                stateBeforeImage = j["sbi"]?.string?.let { runCatching { MediaAssignmentState.valueOf(it) }.getOrNull() },
                lastStatus = j["ls"]?.string?.let { runCatching { MediaLookupStatus.valueOf(it) }.getOrNull() },
                lastMessage = j["lm"]?.string,
                attempts = j["at"]?.array?.mapNotNull { a -> runCatching {
                    ProviderAttempt(a["p"]!!.string!!, a["o"]!!.string!!, a["c"]?.bool == true) }.getOrNull() } ?: emptyList(),
                updatedAtMs = j["u"]?.long ?: 0, recognizerVersion = j["rv"]?.int ?: 1)
        }.getOrNull()
    }
}

class InMemoryTransferMediaAssignmentStore : TransferMediaAssignmentStore {
    private val map = LinkedHashMap<MediaTransferKey, TransferMediaAssignment>()
    @Synchronized override fun get(key: MediaTransferKey) = map[key]
    @Synchronized override fun put(a: TransferMediaAssignment) { map[a.key] = a }
    @Synchronized override fun remove(key: MediaTransferKey) { map.remove(key) }
    @Synchronized override fun clear() = map.clear()
    @Synchronized override fun all() = map.values.toList()
}
