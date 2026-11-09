package io.github.gamesbyian.chunks.media

import java.io.File

/**
 * "Existing and future matches" rule created by an explicit user choice: future transfers whose
 * recognized title (comparison form), media family and (when both known) year match inherit the
 * chosen identity. Only fills transfers with no assignment; never overrides a per-transfer choice.
 */
data class MediaAssignmentRule(
    val id: String,
    val normalizedTitle: String,
    val family: String,
    val year: Int?,
    val candidate: MediaCandidate,
    val createdAtMs: Long
) {
    fun matches(r: MediaRecognition): Boolean {
        if (r.kind == MediaKind.ADULT || r.kind == MediaKind.UNKNOWN) return false
        val t = r.title?.let { MediaText.withoutArticle(MediaText.normalize(it)) } ?: return false
        if (t != normalizedTitle || familyOf(r.kind) != family) return false
        // Movie remakes can share titles. Never spread a user-chosen work by
        // title alone; an unknown-year legacy movie rule fails closed.
        if (family == "movie") return year != null && r.year == year
        return year == null || r.year == null || r.year == year
    }

    companion object {
        fun familyOf(k: MediaKind): String = when {
            k.isTv -> "tv"; k == MediaKind.MOVIE || k == MediaKind.VIDEO -> "movie"
            k == MediaKind.MUSIC_ALBUM || k == MediaKind.AUDIO -> "music"; k == MediaKind.BOOK || k == MediaKind.AUDIOBOOK -> "book"
            else -> k.name.lowercase()
        }
    }
}

interface MediaRuleStore {
    fun all(): List<MediaAssignmentRule>
    fun put(rule: MediaAssignmentRule)
    fun remove(id: String)
    fun clear()
}

class InMemoryMediaRuleStore : MediaRuleStore {
    private val rules = LinkedHashMap<String, MediaAssignmentRule>()
    @Synchronized override fun all() = rules.values.toList()
    @Synchronized override fun put(rule: MediaAssignmentRule) { rules[rule.id] = rule }
    @Synchronized override fun remove(id: String) { rules.remove(id) }
    @Synchronized override fun clear() = rules.clear()
}

class FileMediaRuleStore(private val file: File, private val max: Int = 500) : MediaRuleStore {
    private val rules = LinkedHashMap<String, MediaAssignmentRule>()
    init {
        runCatching {
            if (file.isFile && file.length() < 4_000_000) Json.parse(file.readText())["rules"]?.array?.forEach { j ->
                val c = MediaCodec.candidate(j["c"]) ?: return@forEach
                val r = MediaAssignmentRule(j["id"]!!.string!!, j["t"]!!.string!!, j["f"]!!.string!!, j["y"]?.int, c, j["at"]?.long ?: 0)
                rules[r.id] = r
            }
        }
    }
    @Synchronized override fun all() = rules.values.toList()
    @Synchronized override fun put(rule: MediaAssignmentRule) { rules.remove(rule.id); rules[rule.id] = rule; while (rules.size > max) rules.remove(rules.keys.first()); save() }
    @Synchronized override fun remove(id: String) { if (rules.remove(id) != null) save() }
    @Synchronized override fun clear() { rules.clear(); save() }
    private fun save() = MediaFiles.atomicWrite(file, Json.obj("v" to Json.num(1), "rules" to Json.arr(rules.values.map { r ->
        Json.obj("id" to Json.Str(r.id), "t" to Json.Str(r.normalizedTitle), "f" to Json.Str(r.family), "y" to Json.num(r.year),
            "c" to MediaCodec.candidate(r.candidate), "at" to Json.num(r.createdAtMs)) })).encode())
}
