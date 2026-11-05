package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.model.MediaNameInterpretation
import io.github.gamesbyian.chunks.model.MediaNameInterpreter
import java.util.Locale

/** One file from the authorized, already-known torrent file list. Never read from payload bytes here. */
data class MediaFileHint(val path: String, val sizeBytes: Long? = null) {
    init { require(sizeBytes == null || sizeBytes >= 0) }
}

data class MediaRecognitionInput(val name: String, val files: List<MediaFileHint> = emptyList())

/**
 * A *local proposal* about what a transfer contains. It is never a verified identity: provider
 * matching and user confirmation are separate steps. [raw] and [interpretation] keep the original
 * name and its lossless spans; [title] is a derived search/display candidate, not a rename.
 */
data class MediaRecognition(
    val raw: String,
    val recognizerVersion: Int,
    val interpretation: MediaNameInterpretation,
    val kind: MediaKind,
    val confidence: MediaConfidence,
    val title: String?,
    val year: Int? = null,
    val yearRange: IntRange? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val lastEpisode: Int? = null,
    val absoluteEpisode: Int? = null,
    val episodeTitle: String? = null,
    val issue: Int? = null,
    val volume: Int? = null,
    val artist: String? = null,
    val editions: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
    val ambiguities: Set<String> = emptySet()
) {
    /** Online lookups must never be attempted for adult or private-category proposals by default. */
    val isSensitive: Boolean get() = kind == MediaKind.ADULT
}

/**
 * Conservative, deterministic recognizer layered on the shared [MediaNameInterpreter]. It adds
 * file-composition evidence, edition/season-pack/absolute-episode tokens and title/year selection.
 * Abstains (UNKNOWN / NONE) rather than guess.
 */
object MediaRecognizer {
    const val VERSION = 1

    private enum class Bucket { VIDEO, AUDIO, AUDIOBOOK, EBOOK, DOCUMENT, COMIC, SOFTWARE, DISC, ARCHIVE, IMAGE, SIDECAR }

    private val bucketByExt: Map<String, Bucket> = buildMap {
        listOf("mkv", "mp4", "m4v", "avi", "webm", "mov", "wmv", "ts", "m2ts", "mpg", "mpeg", "flv").forEach { put(it, Bucket.VIDEO) }
        listOf("flac", "mp3", "m4a", "ogg", "opus", "wav", "aac", "alac", "ape", "wv", "dsf", "aiff").forEach { put(it, Bucket.AUDIO) }
        put("m4b", Bucket.AUDIOBOOK)
        listOf("epub", "mobi", "azw3", "azw", "fb2", "djvu").forEach { put(it, Bucket.EBOOK) }
        listOf("pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "csv", "ppt", "pptx").forEach { put(it, Bucket.DOCUMENT) }
        listOf("cbz", "cbr", "cb7", "cbt").forEach { put(it, Bucket.COMIC) }
        listOf("apk", "aab", "exe", "msi", "dmg", "pkg", "deb", "rpm", "appimage", "xapk").forEach { put(it, Bucket.SOFTWARE) }
        listOf("iso", "img", "nrg", "mdf", "vhd", "vmdk", "qcow2").forEach { put(it, Bucket.DISC) }
        listOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst").forEach { put(it, Bucket.ARCHIVE) }
        listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "tif", "tiff", "bmp", "raw", "cr2", "nef").forEach { put(it, Bucket.IMAGE) }
        listOf("nfo", "srt", "ass", "ssa", "sub", "idx", "vtt", "sfv", "md5", "sha1", "txt", "url", "cue", "log", "m3u", "m3u8", "torrent", "par2").forEach { put(it, Bucket.SIDECAR) }
    }

    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"
    private val siteTag = Regex("(?i)^\\s*(?:www\\.)?[a-z0-9-]{2,30}\\.(?:com|org|net|to|io|me|cc|tv|xyz|info|ws|se|ru|club|site|lol)(?:\\s*[-–_]+\\s*|\\s+)")
    private val leadingGroup = Regex("^\\s*[\\[【][^\\]】]{1,48}[\\]】]\\s*[-_.]?\\s*")
    private val episodeRx = Regex("(?i)${B}S(\\d{1,2})[ ._-]?E(\\d{1,3})(?:[-._ ]?E?(\\d{1,3}))?$E")
    private val altEpisodeRx = Regex("(?i)${B}(\\d{1,2})x(\\d{2,3})$E")
    private val seasonPackRx = Regex("(?i)${B}(?:S(\\d{1,2})(?:[-–]S?(\\d{1,2}))?|(?:Season|Series|Saison|Staffel|Temporada|Stagione)[ ._-]?(\\d{1,2}))$E")
    private val completeRx = Regex("(?i)${B}(?:complete(?:[ ._-]series)?|the[ ._-]complete[ ._-]series)$E")
    private val absoluteEpisodeRx = Regex("(?<=\\s)-\\s(\\d{2,4})(?:v\\d)?(?=\\s|$|\\[|\\()")
    private val yearRx = Regex("${B}\\(?((?:19|20)\\d{2})\\)?$E")
    private val yearRangeRx = Regex("${B}\\(?((?:19|20)\\d{2})\\s?[–—-]\\s?((?:19|20)\\d{2})\\)?$E")
    private val issueRx = Regex("(?i)${B}(?:#|issue[ ._-]*|no\\.?\\s?)(\\d{1,4})$E")
    private val bareIssueRx = Regex("${B}(\\d{3})$E")
    private val volumeRx = Regex("(?i)${B}(?:vol(?:ume)?\\.?[ ._-]?|v)(\\d{1,3})$E")
    private val technicalRx = Regex("(?i)${B}(?:2160p|1440p|1080[pi]|720p|576p|480p|4k|uhd|x26[45]|h[ .]?26[45]|hevc|avc|xvid|divx|av1|vp9|" +
        "blu-?ray|bdrip|brrip|bdremux|remux|dvdrip|dvd(?:5|9|r)?|hdtv|pdtv|web[ .-]?dl|web[ .-]?rip|webrip|hdrip|camrip|hdcam|telesync|" +
        "hdr10\\+?|hdr|dolby[ .-]?vision|dovi|sdr|10[ .-]?bit|8[ .-]?bit|aac(?:2\\.0|5\\.1)?|ac3|eac3|dd[p+]?(?:5\\.1|7\\.1|2\\.0)?|dts(?:-?hd)?|" +
        "truehd|atmos|flac|mp3|320kbps|24bit|16bit|lossless|amzn|dsnp|hmax|atvp|pcok|repack|proper|rerip|internal|" +
        "limited|multi|dual[ .-]?audio|subbed|dubbed|retail|p2p|epub|mobi|azw3|cbz|cbr|pdf|fitgirl|dodi|gog|codex|" +
        "plaza|skidrow|empress|elamigos|x64|x86|win64|win32)$E")
    private val editionRx = Regex("(?i)${B}(extended(?:[ ._-](?:cut|edition))?|unrated|uncut|remastered|director'?s[ ._-]cut|" +
        "theatrical(?:[ ._-]cut)?|imax|criterion|final[ ._-]cut|special[ ._-]edition|anniversary(?:[ ._-]edition)?|" +
        "ultimate(?:[ ._-](?:cut|edition))?|collector'?s[ ._-]edition|deluxe(?:[ ._-]edition)?|open[ ._-]matte|redux)$E")
    private val gamePlatformRx = Regex("(?i)${B}(?:ps[2-5]|psp|vita|xbox(?:360|[ ._-]?one|[ ._-]?series)?|nsw|switch|wii[ ._-]?u?|3ds|nds|gba|pc[ ._-]?game|gog|fitgirl|dodi|codex|plaza|skidrow|empress|elamigos)$E")
    private val audiobookRx = Regex("(?i)${B}(?:audiobook|audio[ ._-]?book|unabridged|abridged|narrated[ ._-]by|m4b)$E")
    private val adultRx = Regex("(?i)${B}(?:xxx|porn|nsfw|hentai|onlyfans|brazzers|18\\+)$E")
    private val musicHintRx = Regex("(?i)${B}(?:discography|album|ep|single|ost|soundtrack|flac|mp3|320kbps|v0|24bit|16bit|lossless|web[ .-]?flac|cd[ ._-]?rip)$E")
    private val musicTitleTokensRx = Regex("(?i)\\s*[\\[(](?:flac|mp3|320|v0|24bit|16bit|web|cd|vinyl|lossless|[0-9]{2,3}\\s?kbps)[^\\])]*[\\])]")

    private data class Marker(val start: Int, val end: Int, val what: String)

    fun recognize(input: MediaRecognitionInput): MediaRecognition {
        val raw = input.name
        val interpretation = MediaNameInterpreter.interpret(raw)
        val evidence = mutableListOf<String>()
        val ambiguities = interpretation.ambiguities.toMutableSet()

        val leaf = raw.substring(maxOf(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1).trim()
        val extSpan = interpretation.spans.lastOrNull { it.kind.name == "EXTENSION" }
        val nameExt = leaf.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.length in 2..8 && it in bucketByExt }
        var stem = if (nameExt != null) leaf.substring(0, leaf.length - nameExt.length - 1) else if (extSpan != null) leaf.removeSuffix(extSpan.literal) else leaf
        // Site tags first (they contain dots), then dot/underscore separators when dots dominate spaces.
        stem = stem.replace(siteTag, "")
        val dotted = stem.count { it == '.' } >= 2 && stem.count { it == '.' } > stem.count { it == ' ' }
        // Keep decimal dots ("DD5.1", "2.0"); every other dot/underscore is a word separator.
        var working = if (dotted) stem.replace('_', ' ').replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ") else stem.replace('_', ' ')
        working = working.replace(siteTag, "").replace(leadingGroup, "").replace(leadingGroup, "")
        stem = working

        // --- composition evidence ---
        val composition = composition(input.files, nameExt)
        val dominant = composition.first
        if (composition.second != null) evidence.add(composition.second!!)

        val episodeMatches = (episodeRx.findAll(stem) + altEpisodeRx.findAll(stem)).toList()
        val markers = mutableListOf<Marker>()
        var season: Int? = null; var episode: Int? = null; var lastEpisode: Int? = null
        if (episodeMatches.size == 1) {
            val m = episodeMatches.single()
            season = m.groupValues[1].toIntOrNull(); episode = m.groupValues[2].toIntOrNull()
            lastEpisode = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            if (lastEpisode != null && episode != null && lastEpisode <= episode) lastEpisode = null
            markers.add(Marker(m.range.first, m.range.last + 1, "episode"))
            evidence.add("Episode marker ${m.value.trim()}")
        } else if (episodeMatches.size > 1) {
            ambiguities.add("multiple-episode-markers")
            markers.add(Marker(episodeMatches.minOf { it.range.first }, stem.length, "episode"))
        }
        var seasonPack = false
        var seasonRangeEnd: Int? = null
        if (season == null) {
            seasonPackRx.find(stem)?.let { m ->
                val s = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull()
                if (s != null) {
                    season = s; seasonPack = true
                    seasonRangeEnd = m.groupValues[2].toIntOrNull()
                    markers.add(Marker(m.range.first, m.range.last + 1, "season"))
                    evidence.add("Season marker ${m.value.trim()}")
                }
            }
        }
        completeRx.find(stem)?.let { markers.add(Marker(it.range.first, it.range.last + 1, "complete")) }
        var absolute: Int? = null
        if (season == null) absoluteEpisodeRx.find(stem)?.let { m ->
            absolute = m.groupValues[1].toIntOrNull()
            markers.add(Marker(m.range.first, m.range.last + 1, "absolute"))
            ambiguities.add("absolute-episode-number")
            evidence.add("Absolute episode number ${m.groupValues[1]}")
        }

        // Year range suppresses its endpoints as release years.
        val range = yearRangeRx.find(stem)
        var yearRange: IntRange? = null
        if (range != null) {
            val a = range.groupValues[1].toInt(); val b = range.groupValues[2].toInt()
            if (b >= a) { yearRange = a..b; markers.add(Marker(range.range.first, range.range.last + 1, "year-range")) }
        }
        val years = yearRx.findAll(stem).filter { y -> range == null || y.range.last < range.range.first || y.range.first > range.range.last }.toList()
        // A leading number is usually the title ("1917", "2012"); choose the last non-leading year.
        val yearMatch = years.lastOrNull { it.range.first > 0 && stem.substring(0, it.range.first).isNotBlank() }
        val year = yearMatch?.groupValues?.get(1)?.toInt()
        if (yearMatch != null) {
            markers.add(Marker(yearMatch.range.first, yearMatch.range.last + 1, "year"))
            evidence.add("Release year $year")
            if (years.count { it.range.first > 0 } > 1) ambiguities.add("multiple-release-years")
        }

        val editions = editionRx.findAll(stem).map { m ->
            markers.add(Marker(m.range.first, m.range.last + 1, "edition"))
            normalizeEdition(m.groupValues[1])
        }.distinct().toList()
        technicalRx.findAll(stem).forEach { markers.add(Marker(it.range.first, it.range.last + 1, "technical")) }
        // Bracketed technical groups like "[1080p]" or "(FLAC)" also stop the title.
        Regex("[\\[(][^\\])]*[\\])]").findAll(stem).forEach { m ->
            if (technicalRx.containsMatchIn(m.value) || Regex("(?i)[0-9a-f]{8}").matches(m.value.trim('[', ']', '(', ')'))) {
                markers.add(Marker(m.range.first, m.range.last + 1, "technical"))
            }
        }

        val adult = adultRx.containsMatchIn(stem) || input.files.any { adultRx.containsMatchIn(it.path) }
        val gameHint = gamePlatformRx.containsMatchIn(stem)
        val audiobookHint = audiobookRx.containsMatchIn(stem) || dominant == Bucket.AUDIOBOOK
        audiobookRx.findAll(stem).forEach { markers.add(Marker(it.range.first, it.range.last + 1, "audiobook")) }

        // --- kind ---
        val hasVideoTokens = markers.any { it.what == "technical" } && Regex("(?i)${B}(?:2160p|1080[pi]|720p|480p|x26[45]|hevc|blu-?ray|web[ .-]?dl|webrip|hdtv|remux|bdrip|dvdrip)$E").containsMatchIn(stem)
        val effectiveBucket = dominant ?: nameExt?.let { bucketByExt[it] }?.takeIf { it != Bucket.SIDECAR }
        var kind = when {
            adult -> MediaKind.ADULT
            effectiveBucket == Bucket.VIDEO || (effectiveBucket == null && (hasVideoTokens || episodeMatches.isNotEmpty() || seasonPack)) -> when {
                episode != null -> MediaKind.TV_EPISODE
                seasonPack || episodeMatches.size > 1 || composition.third >= 2 -> MediaKind.TV_SEASON
                absolute != null -> MediaKind.TV_EPISODE
                year != null -> MediaKind.MOVIE
                else -> MediaKind.VIDEO
            }
            audiobookHint && (effectiveBucket == Bucket.AUDIO || effectiveBucket == Bucket.AUDIOBOOK || effectiveBucket == null) -> MediaKind.AUDIOBOOK
            effectiveBucket == Bucket.AUDIO -> MediaKind.MUSIC_ALBUM
            effectiveBucket == null && musicHintRx.containsMatchIn(stem) && Regex("(?i)${B}(?:flac|mp3|320kbps|v0|24bit|16bit|lossless)$E").containsMatchIn(stem) -> MediaKind.MUSIC_ALBUM
            effectiveBucket == Bucket.EBOOK -> MediaKind.BOOK
            effectiveBucket == Bucket.COMIC -> MediaKind.COMIC
            effectiveBucket == Bucket.DOCUMENT -> MediaKind.DOCUMENT
            gameHint -> MediaKind.GAME
            effectiveBucket == Bucket.SOFTWARE -> MediaKind.SOFTWARE
            effectiveBucket == Bucket.DISC -> MediaKind.DISC_IMAGE
            effectiveBucket == Bucket.IMAGE -> MediaKind.IMAGE
            effectiveBucket == Bucket.ARCHIVE -> MediaKind.ARCHIVE
            else -> MediaKind.UNKNOWN
        }
        if (kind == MediaKind.TV_SEASON && episodeMatches.size > 1) ambiguities.add("episode-list-not-verified")
        if (seasonRangeEnd != null) ambiguities.add("multi-season-pack")

        // Comics: issue/volume markers; a bare 3-digit number is an issue only inside comic composition.
        var issue: Int? = null; var volume: Int? = null
        if (kind == MediaKind.COMIC) {
            val im = issueRx.find(stem) ?: bareIssueRx.findAll(stem).lastOrNull()?.takeIf { yearMatch == null || it.range.first != yearMatch.range.first }
            if (im != null) { issue = im.groupValues[1].toInt(); markers.add(Marker(im.range.first, im.range.last + 1, "issue")); evidence.add("Issue #$issue") }
            volumeRx.find(stem)?.let { v -> volume = v.groupValues[1].toInt(); markers.add(Marker(v.range.first, v.range.last + 1, "volume")) }
        }

        // --- title ---
        val cutoff = markers.filter { it.start > 0 }.minOfOrNull { it.start } ?: stem.length
        var title = clean(stem.substring(0, cutoff))
        if (title.isEmpty() && yearMatch == null && years.isNotEmpty()) title = years.first().groupValues[1]
        var artist: String? = null
        if (kind == MediaKind.MUSIC_ALBUM || kind == MediaKind.AUDIOBOOK || kind == MediaKind.BOOK) {
            val musicTitle = clean(stem.substring(0, cutoff).replace(musicTitleTokensRx, ""))
            val parts = musicTitle.split(Regex("\\s[-–—]\\s"), limit = 2)
            if (parts.size == 2 && parts.all { it.isNotBlank() }) {
                artist = clean(parts[0]); title = clean(parts[1])
                evidence.add("Creator – title separator")
            } else title = musicTitle
        }
        val episodeTitle = episodeMatches.singleOrNull()?.let { m ->
            val after = stem.substring(m.range.last + 1)
            val stop = markers.filter { it.start > m.range.last }.minOfOrNull { it.start - m.range.last - 1 } ?: after.length
            clean(after.substring(0, stop.coerceIn(0, after.length))).takeIf { it.length in 2..80 }
        }

        // --- confidence ---
        val confidence = when {
            title.isEmpty() -> MediaConfidence.NONE
            kind == MediaKind.UNKNOWN -> MediaConfidence.NONE
            kind == MediaKind.ADULT -> MediaConfidence.LOW
            kind == MediaKind.TV_EPISODE && season != null -> MediaConfidence.HIGH
            kind == MediaKind.TV_SEASON && season != null -> MediaConfidence.MEDIUM
            kind == MediaKind.MOVIE && year != null && (effectiveBucket == Bucket.VIDEO || hasVideoTokens) -> MediaConfidence.MEDIUM
            (kind == MediaKind.MUSIC_ALBUM || kind == MediaKind.BOOK) && artist != null -> MediaConfidence.MEDIUM
            kind == MediaKind.BOOK || kind == MediaKind.COMIC || kind == MediaKind.AUDIOBOOK -> MediaConfidence.LOW
            else -> MediaConfidence.LOW
        }
        if (ambiguities.isNotEmpty() && confidence == MediaConfidence.HIGH && "multiple-episode-markers" in ambiguities) {
            return finish(raw, interpretation, kind, MediaConfidence.MEDIUM, title, year, yearRange, season, episode, lastEpisode,
                absolute, episodeTitle, issue, volume, artist, editions, evidence, ambiguities)
        }
        return finish(raw, interpretation, kind, confidence, title, year, yearRange, season, episode, lastEpisode,
            absolute, episodeTitle, issue, volume, artist, editions, evidence, ambiguities)
    }

    fun recognize(name: String, files: List<MediaFileHint> = emptyList()) = recognize(MediaRecognitionInput(name, files))

    /**
     * Refine a proposal with the file's own embedded tags (album/artist/year), which are stronger
     * evidence than filename parsing for music and audiobooks. Still a local proposal, never a
     * verified catalog identity; the raw name and its spans are untouched.
     */
    fun withEmbeddedTags(r: MediaRecognition, tags: io.github.gamesbyian.chunks.media.inspection.EmbeddedMediaTags): MediaRecognition {
        if (r.kind == MediaKind.ADULT || !tags.hasIdentity) return r
        val audiobook = r.kind == MediaKind.AUDIOBOOK || tags.format == io.github.gamesbyian.chunks.media.inspection.EmbeddedTagFormat.MP4 &&
            Regex("(?i)audiobook|unabridged|abridged").containsMatchIn(tags.album ?: "")
        val kind = when {
            audiobook -> MediaKind.AUDIOBOOK
            r.kind == MediaKind.MUSIC_ALBUM || r.kind == MediaKind.AUDIO || r.kind == MediaKind.UNKNOWN -> MediaKind.MUSIC_ALBUM
            else -> return r // embedded audio tags never reclassify video, books or software
        }
        val album = if (kind == MediaKind.AUDIOBOOK) tags.album!!.replace(Regex("(?i)\\s*[\\[(](?:un)?abridged[\\])]"), "").trim() else tags.album!!
        return r.copy(kind = kind, title = album, artist = tags.albumArtist ?: tags.artist, year = tags.year ?: r.year,
            confidence = MediaConfidence.HIGH, evidence = r.evidence + "Embedded ${tags.format.name} tags")
    }

    private fun finish(raw: String, interpretation: MediaNameInterpretation, kind: MediaKind, confidence: MediaConfidence,
                       title: String, year: Int?, yearRange: IntRange?, season: Int?, episode: Int?, lastEpisode: Int?,
                       absolute: Int?, episodeTitle: String?, issue: Int?, volume: Int?, artist: String?,
                       editions: List<String>, evidence: List<String>, ambiguities: Set<String>) =
        MediaRecognition(raw, VERSION, interpretation, kind, confidence, title.ifEmpty { null }, year, yearRange,
            season, episode, lastEpisode, absolute, episodeTitle, issue, volume, artist?.ifEmpty { null }, editions,
            evidence, ambiguities)

    /** Returns dominant bucket, an evidence sentence, and the count of distinct episode-marked video files. */
    private fun composition(files: List<MediaFileHint>, nameExt: String?): Triple<Bucket?, String?, Int> {
        if (files.isEmpty()) return Triple(null, null, 0)
        val classified = files.map { f ->
            val ext = f.path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
            Triple(f, ext, bucketByExt[ext])
        }
        val primary = classified.filter { it.third != null && it.third != Bucket.SIDECAR }
        if (primary.isEmpty()) return Triple(null, null, 0)
        val allSized = primary.all { it.first.sizeBytes != null }
        fun weight(t: Triple<MediaFileHint, String, Bucket?>): Double = if (allSized) t.first.sizeBytes!!.toDouble() else 1.0
        val total = primary.sumOf { weight(it) }.takeIf { it > 0 } ?: primary.size.toDouble()
        val byBucket = primary.groupBy { it.third!! }.mapValues { (_, v) -> v.sumOf { weight(it) } }
        // Images that are merely covers/scans next to audio/video/books do not dominate by count.
        val (bucket, share) = byBucket.maxByOrNull { it.value }!!.let { it.key to it.value / total }
        val episodeFiles = primary.filter { it.third == Bucket.VIDEO }
            .mapNotNull { episodeRx.find(it.first.path.substringAfterLast('/'))?.let { m -> m.groupValues[1] + "x" + m.groupValues[2] } }
            .distinct().size
        if (share < 0.6) return Triple(null, "Mixed file types; not classified", episodeFiles)
        val count = primary.count { it.third == bucket }
        val exts = primary.filter { it.third == bucket }.map { it.second }.distinct().take(3).joinToString("/") { ".$it" }
        return Triple(bucket, "Mostly $exts files ($count of ${files.size})", episodeFiles)
    }

    private fun clean(s: String): String = s.replace(Regex("[\\[\\](){}]"), " ")
        .replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '.', ',', ':', ';', '+', '~', '_')
        .replace(Regex("\\s+"), " ").trim()

    private fun normalizeEdition(token: String): String {
        val t = token.lowercase(Locale.ROOT).replace(Regex("[ ._-]+"), " ")
        return when {
            t.startsWith("extended") -> "Extended"
            t == "unrated" -> "Unrated"
            t == "uncut" -> "Uncut"
            t == "remastered" -> "Remastered"
            t.startsWith("director") || t == "dc" -> "Director's Cut"
            t.startsWith("theatrical") -> "Theatrical"
            t == "imax" -> "IMAX"
            t == "criterion" -> "Criterion"
            t == "final cut" -> "Final Cut"
            t.startsWith("special") -> "Special Edition"
            t.startsWith("anniversary") -> "Anniversary Edition"
            t.startsWith("ultimate") -> "Ultimate Edition"
            t.startsWith("collector") -> "Collector's Edition"
            t.startsWith("deluxe") -> "Deluxe Edition"
            t == "open matte" -> "Open Matte"
            t == "redux" -> "Redux"
            else -> token
        }
    }
}
