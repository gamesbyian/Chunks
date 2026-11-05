package io.github.gamesbyian.chunks.media.inspection

import java.util.Locale

enum class TrackKind { VIDEO, AUDIO, SUBTITLE, OTHER }

/** How a fact was obtained, shown in "How determined" (MEDIA-INSPECTION semantics). */
enum class FactSource { DETECTED, CONTAINER_DEFAULT, MISSING }

data class MediaTrack(
    val kind: TrackKind,
    val codec: String?,
    val language: String?,
    val languageSource: FactSource,
    val name: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val isDefault: Boolean? = null
)

data class MediaTechnicalInfo(
    val container: String,
    val durationMs: Long?,
    val tracks: List<MediaTrack>,
    /** True when parsing stopped early (budget, truncation, malformed data): facts may be incomplete. */
    val partial: Boolean,
    val parserVersion: Int = MediaTrackInspector.VERSION
) {
    val video get() = tracks.filter { it.kind == TrackKind.VIDEO }
    val audio get() = tracks.filter { it.kind == TrackKind.AUDIO }
    val subtitles get() = tracks.filter { it.kind == TrackKind.SUBTITLE }
}

/**
 * Bounded, read-only container inspection for Matroska/WebM (EBML) and MP4/MOV (ISO BMFF):
 * duration and per-track codec, language, size and channels. Never decodes frames. Reads only
 * headers until the first cluster/sample data; untrusted lengths are range-checked and the total
 * read budget is capped. Unknown or absent facts stay unknown (an absent language is not English).
 */
object MediaTrackInspector {
    const val VERSION = 1
    const val READ_BUDGET = 4L * 1024 * 1024
    private const val MAX_ELEMENTS = 20_000

    fun inspect(src: MediaByteSource): MediaTechnicalInfo? = runCatching {
        val head = src.read(0, 12)
        when {
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> mkv(src)
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "moov", "mdat", "free", "wide") -> mp4(src)
            else -> null
        }
    }.getOrNull()

    // ------------------------------- Matroska / WebM -------------------------------
    private class Ebml(private val src: MediaByteSource) {
        var budget = READ_BUDGET
        var elements = 0
        var partial = false
        private var bufStart = -1L
        private var buf = ByteArray(0)

        fun byteAt(o: Long): Int {
            if (o < bufStart || o >= bufStart + buf.size) {
                if (budget <= 0) { partial = true; throw IllegalStateException("budget") }
                buf = src.read(o, 64 * 1024); bufStart = o; budget -= buf.size
                if (buf.isEmpty()) { partial = true; throw IllegalStateException("eof") }
            }
            return buf[(o - bufStart).toInt()].toInt() and 0xFF
        }

        fun bytes(o: Long, n: Int): ByteArray = ByteArray(n) { byteAt(o + it).toByte() }

        /** Element ID (marker bits kept) and its encoded length. */
        fun id(o: Long): Pair<Long, Int> {
            val first = byteAt(o)
            val len = Integer.numberOfLeadingZeros(first) - 24 + 1
            if (len !in 1..4) throw IllegalStateException("bad id")
            var v = first.toLong()
            for (i in 1 until len) v = (v shl 8) or byteAt(o + i).toLong()
            return v to len
        }

        /** Data size (marker removed); null for "unknown size". */
        fun size(o: Long): Pair<Long?, Int> {
            val first = byteAt(o)
            val len = Integer.numberOfLeadingZeros(first) - 24 + 1
            if (len !in 1..8) throw IllegalStateException("bad size")
            var v = (first and (0xFF shr len)).toLong()
            var allOnes = v == (0xFF shr len).toLong()
            for (i in 1 until len) { val b = byteAt(o + i); allOnes = allOnes && b == 0xFF; v = (v shl 8) or b.toLong() }
            return (if (allOnes) null else v) to len
        }

        fun uint(o: Long, n: Long): Long { var v = 0L; for (i in 0 until minOf(n, 8L)) v = (v shl 8) or byteAt(o + i).toLong(); return v }
        fun float(o: Long, n: Long): Double? = when (n) {
            4L -> java.lang.Float.intBitsToFloat(uint(o, 4).toInt()).toDouble()
            8L -> java.lang.Double.longBitsToDouble(uint(o, 8))
            else -> null
        }
        fun str(o: Long, n: Long): String = String(bytes(o, minOf(n, 256L).toInt()), Charsets.UTF_8).trimEnd('\u0000')

        /** Iterate child elements in [start, end); unknown-size children extend to [end]. */
        fun children(start: Long, end: Long, f: (id: Long, dataStart: Long, dataEnd: Long) -> Boolean) {
            var o = start
            while (o < end) {
                if (++elements > MAX_ELEMENTS) { partial = true; return }
                val (id, il) = id(o)
                val (sz, sl) = size(o + il)
                val ds = o + il + sl
                val de = if (sz == null) end else ds + sz
                if (de > end || de < ds) { partial = true; return }
                if (!f(id, ds, de)) return
                o = de
            }
        }
    }

    private fun mkv(src: MediaByteSource): MediaTechnicalInfo {
        val e = Ebml(src)
        var doc = "matroska"; var scale = 1_000_000L; var duration: Double? = null
        val tracks = mutableListOf<MediaTrack>()
        try {
            e.children(0, src.size) { id, s, end ->
                when (id) {
                    0x1A45DFA3L -> e.children(s, end) { cid, cs, ce -> if (cid == 0x4282L) doc = e.str(cs, ce - cs).lowercase(Locale.ROOT); true }
                    0x18538067L -> e.children(s, end) { sid, ss, se ->
                        when (sid) {
                            0x1549A966L -> e.children(ss, se) { iid, ist, ie ->
                                if (iid == 0x2AD7B1L) scale = e.uint(ist, ie - ist)
                                if (iid == 0x4489L) duration = e.float(ist, ie - ist); true }
                            0x1654AE6BL -> e.children(ss, se) { tid, ts, te -> if (tid == 0xAEL) mkvTrack(e, ts, te)?.let { tracks.add(it) }; true }
                        }
                        // Stop at the first Cluster: track headers precede media data in normal files.
                        sid != 0x1F43B675L || tracks.isEmpty()
                    }
                }
                true
            }
        } catch (ex: IllegalStateException) { e.partial = true }
        val ms = duration?.takeIf { it.isFinite() && it > 0 }?.let { (it * scale / 1_000_000.0).toLong() }
        return MediaTechnicalInfo(if (doc == "webm") "WebM" else "Matroska", ms, tracks, e.partial)
    }

    private fun mkvTrack(e: Ebml, s: Long, end: Long): MediaTrack? {
        var type = 0L; var codec: String? = null; var lang: String? = null; var ietf: String? = null; var name: String? = null
        var w: Int? = null; var h: Int? = null; var ch: Int? = null; var rate: Int? = null; var def: Boolean? = null
        e.children(s, end) { id, ds, de ->
            val n = de - ds
            when (id) {
                0x83L -> type = e.uint(ds, n)
                0x86L -> codec = e.str(ds, n)
                0x22B59CL -> lang = e.str(ds, n)
                0x22B59DL -> ietf = e.str(ds, n)
                0x536EL -> name = e.str(ds, n)
                0x88L -> def = e.uint(ds, n) != 0L
                0xE0L -> e.children(ds, de) { vid, vs, ve -> when (vid) { 0xB0L -> w = e.uint(vs, ve - vs).toInt(); 0xBAL -> h = e.uint(vs, ve - vs).toInt() }; true }
                0xE1L -> e.children(ds, de) { aid, ast, ae -> when (aid) {
                    0x9FL -> ch = e.uint(ast, ae - ast).toInt(); 0xB5L -> rate = e.float(ast, ae - ast)?.toInt() }; true }
            }
            true
        }
        val kind = when (type) { 1L -> TrackKind.VIDEO; 2L -> TrackKind.AUDIO; 0x11L -> TrackKind.SUBTITLE; else -> TrackKind.OTHER }
        // Matroska's spec default language is "eng" when the element is absent: label it as a container default.
        val language = (ietf ?: lang)?.takeIf { it.isNotBlank() && it != "und" }
        val src = when { ietf != null || lang != null -> if (language == null) FactSource.MISSING else FactSource.DETECTED; else -> FactSource.CONTAINER_DEFAULT }
        return MediaTrack(kind, codec?.let(::mkvCodec), if (src == FactSource.CONTAINER_DEFAULT) "eng" else language, src, name?.take(80),
            w?.takeIf { it in 1..20_000 }, h?.takeIf { it in 1..20_000 }, ch?.takeIf { it in 1..64 }, rate?.takeIf { it in 1..1_000_000 }, def)
    }

    private fun mkvCodec(id: String): String = when {
        id == "V_MPEG4/ISO/AVC" -> "H.264"; id == "V_MPEGH/ISO/HEVC" -> "H.265"; id == "V_AV1" -> "AV1"; id.startsWith("V_VP9") -> "VP9"
        id.startsWith("V_VP8") -> "VP8"; id.startsWith("V_MPEG4") -> "MPEG-4"; id == "A_AAC" || id.startsWith("A_AAC/") -> "AAC"
        id == "A_AC3" -> "AC-3"; id == "A_EAC3" -> "E-AC-3"; id.startsWith("A_DTS") -> "DTS"; id == "A_TRUEHD" -> "TrueHD"
        id == "A_FLAC" -> "FLAC"; id == "A_OPUS" -> "Opus"; id == "A_VORBIS" -> "Vorbis"; id.startsWith("A_MPEG/L3") -> "MP3"
        id == "S_TEXT/UTF8" -> "SRT"; id == "S_TEXT/ASS" || id == "S_TEXT/SSA" -> "ASS"; id == "S_HDMV/PGS" -> "PGS"; id == "S_VOBSUB" -> "VobSub"
        id == "S_TEXT/WEBVTT" -> "WebVTT"
        else -> id.take(32)
    }

    // ------------------------------- MP4 / MOV -------------------------------
    private fun mp4(src: MediaByteSource): MediaTechnicalInfo {
        var budget = READ_BUDGET; var atoms = 0; var partial = false
        fun read(o: Long, n: Int): ByteArray { if (budget <= 0) { partial = true; return ByteArray(0) }; budget -= n; return src.read(o, n) }
        fun be(b: ByteArray, o: Int, n: Int): Long { var v = 0L; for (i in 0 until n) v = (v shl 8) or (b[o + i].toLong() and 0xFF); return v }
        fun boxes(start: Long, end: Long, f: (String, Long, Long) -> Unit) {
            var o = start
            while (o + 8 <= end) {
                if (++atoms > MAX_ELEMENTS) { partial = true; return }
                val h = read(o, 16); if (h.size < 8) { partial = true; return }
                var size = be(h, 0, 4); val type = String(h, 4, 4, Charsets.ISO_8859_1); var hl = 8
                if (size == 1L) { if (h.size < 16) { partial = true; return }; size = be(h, 8, 8); hl = 16 }
                if (size == 0L) size = end - o
                if (size < hl || o + size > end) { partial = true; return }
                f(type, o + hl, o + size); o += size
            }
        }
        var duration: Long? = null
        val tracks = mutableListOf<MediaTrack>()
        boxes(0, src.size) { t, s, e ->
            if (t != "moov") return@boxes
            boxes(s, e) { t2, s2, e2 ->
                when (t2) {
                    "mvhd" -> read(s2, 32).let { b -> if (b.size >= 20) {
                        val v1 = b[0].toInt() == 1
                        val scale = if (v1) be(b, 20, 4) else be(b, 12, 4)
                        val dur = if (v1) (if (b.size >= 32) be(b, 24, 8) else 0) else be(b, 16, 4)
                        if (scale > 0 && dur > 0) duration = dur * 1000 / scale } }
                    "trak" -> mp4Track(::read, ::be, ::boxes, s2, e2)?.let { tracks.add(it) }
                }
            }
        }
        return MediaTechnicalInfo("MP4", duration, tracks, partial)
    }

    private fun mp4Track(read: (Long, Int) -> ByteArray, be: (ByteArray, Int, Int) -> Long,
                         boxes: (Long, Long, (String, Long, Long) -> Unit) -> Unit, s: Long, e: Long): MediaTrack? {
        var handler: String? = null; var lang: String? = null; var codec: String? = null
        var w: Int? = null; var h: Int? = null; var ch: Int? = null; var rate: Int? = null
        boxes(s, e) { t, ts, te ->
            when (t) {
                "tkhd" -> read(te - 8, 8).let { b -> if (b.size == 8) { w = (be(b, 0, 4) shr 16).toInt(); h = (be(b, 4, 4) shr 16).toInt() } }
                "mdia" -> boxes(ts, te) { m, ms, me ->
                    when (m) {
                        "mdhd" -> read(ms, 32).let { b -> if (b.isNotEmpty()) {
                            val o = if (b[0].toInt() == 1) 28 else 20
                            if (b.size >= o + 2) { val p = be(b, o, 2).toInt()
                                val code = String(charArrayOf((((p shr 10) and 31) + 0x60).toChar(), (((p shr 5) and 31) + 0x60).toChar(), ((p and 31) + 0x60).toChar()))
                                lang = code.takeIf { it.all { c -> c in 'a'..'z' } && it != "und" } } } }
                        "hdlr" -> read(ms, 12).let { b -> if (b.size >= 12) handler = String(b, 8, 4, Charsets.ISO_8859_1) }
                        "minf" -> boxes(ms, me) { n, ns, ne -> if (n == "stbl") boxes(ns, ne) { st, ss, se ->
                            if (st == "stsd") read(ss, 64).let { b -> if (b.size >= 16) {
                                codec = String(b, 12, 4, Charsets.ISO_8859_1)
                                // Audio sample entry: channelcount at +16 and samplerate (16.16) at +24 of the entry body.
                                if (b.size >= 16 + 8 + 20) { ch = be(b, 16 + 8 + 8, 2).toInt(); rate = (be(b, 16 + 8 + 16, 4) shr 16).toInt() }
                            } } } }
                    }
                }
            }
        }
        val kind = when (handler) { "vide" -> TrackKind.VIDEO; "soun" -> TrackKind.AUDIO; "sbtl", "text", "subt" -> TrackKind.SUBTITLE; else -> TrackKind.OTHER }
        return MediaTrack(kind, codec?.let(::mp4Codec), lang, if (lang == null) FactSource.MISSING else FactSource.DETECTED,
            width = w?.takeIf { kind == TrackKind.VIDEO && it in 1..20_000 }, height = h?.takeIf { kind == TrackKind.VIDEO && it in 1..20_000 },
            channels = ch?.takeIf { kind == TrackKind.AUDIO && it in 1..64 }, sampleRate = rate?.takeIf { kind == TrackKind.AUDIO && it in 1..1_000_000 })
    }

    private fun mp4Codec(fourcc: String): String = when (fourcc) {
        "avc1", "avc3" -> "H.264"; "hvc1", "hev1" -> "H.265"; "av01" -> "AV1"; "vp09" -> "VP9"; "mp4a" -> "AAC"; "ac-3" -> "AC-3"
        "ec-3" -> "E-AC-3"; "Opus" -> "Opus"; "fLaC" -> "FLAC"; "tx3g" -> "Timed text"; "wvtt" -> "WebVTT"; ".mp3" -> "MP3"
        else -> fourcc.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "unknown" }
    }

    /** Compact human summary, e.g. "H.264 1920×1080 · AAC 2.0 (eng), AC-3 5.1 (jpn) · Subtitles: eng, spa". */
    fun summary(info: MediaTechnicalInfo): String {
        fun chans(c: Int?) = when (c) { null -> null; 1 -> "mono"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; else -> "${c}ch" }
        fun lang(t: MediaTrack) = when (t.languageSource) { FactSource.DETECTED -> t.language; FactSource.CONTAINER_DEFAULT -> "eng?"; FactSource.MISSING -> "unknown" }
        val v = info.video.firstOrNull()?.let { listOfNotNull(it.codec, if (it.width != null && it.height != null) "${it.width}×${it.height}" else null).joinToString(" ") }
        val a = info.audio.joinToString(", ") { t -> listOfNotNull(t.codec, chans(t.channels)).joinToString(" ") + " (${lang(t)})" }.ifEmpty { null }
        val s = info.subtitles.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Subtitles: ") { lang(it) ?: "unknown" }
        return listOfNotNull(v, a, s).joinToString(" · ").ifEmpty { "No tracks detected" }
    }
}
