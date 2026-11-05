package io.github.gamesbyian.chunks.media.inspection

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * Bounded random-access reads of one already-permitted *local* file. Never a remote payload
 * reader: rTorrent RPC is not a file reader (MEDIA-INSPECTION "Scope, identity and permissions").
 */
interface MediaByteSource {
    val size: Long
    /** Up to [length] bytes at [offset]; fewer at end of file. */
    fun read(offset: Long, length: Int): ByteArray
}

class FileMediaByteSource(private val file: File) : MediaByteSource {
    override val size: Long get() = file.length()
    override fun read(offset: Long, length: Int): ByteArray = RandomAccessFile(file, "r").use { f ->
        if (offset < 0 || offset >= f.length()) return ByteArray(0)
        val n = minOf(length.toLong(), f.length() - offset).toInt()
        val b = ByteArray(n); f.seek(offset); f.readFully(b); b
    }
}

class ByteArrayMediaByteSource(private val bytes: ByteArray) : MediaByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= bytes.size) return ByteArray(0)
        return bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
    }
}

enum class EmbeddedTagFormat { ID3V2, FLAC, MP4 }

/** Facts read from the file's own tags: labelled "Embedded tags", still not a verified catalog identity. */
data class EmbeddedMediaTags(
    val format: EmbeddedTagFormat,
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val track: Int? = null,
    val picture: ByteArray? = null,
    val pictureMime: String? = null
) {
    val hasIdentity: Boolean get() = album != null && (albumArtist ?: artist) != null
    override fun equals(other: Any?) = other is EmbeddedMediaTags && other.format == format && other.title == title &&
        other.artist == artist && other.albumArtist == albumArtist && other.album == album && other.year == year &&
        other.track == track && other.pictureMime == pictureMime && (other.picture?.contentEquals(picture) ?: (picture == null))
    override fun hashCode() = listOf(format, title, artist, albumArtist, album, year, track).hashCode()
}

/**
 * Pure-Kotlin, bounded readers for ID3v2.3/2.4, FLAC metadata blocks and MP4/M4A/M4B `ilst` atoms.
 * Untrusted input: every length is range-checked, total bytes read are capped, nesting is limited,
 * and any inconsistency ends parsing with whatever valid facts were already read. No native decoders.
 */
object EmbeddedTagReader {
    const val MAX_TAG_BYTES = 8 * 1024 * 1024
    const val MAX_PICTURE_BYTES = 6 * 1024 * 1024
    private const val MAX_ATOMS = 4_000

    fun read(source: MediaByteSource): EmbeddedMediaTags? = runCatching {
        val head = source.read(0, 12)
        when {
            head.size >= 10 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> id3(source, head)
            head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> flac(source)
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4(source)
            else -> null
        }
    }.getOrNull()

    fun read(file: File): EmbeddedMediaTags? = if (file.isFile) read(FileMediaByteSource(file)) else null

    // ---------------- ID3v2 ----------------
    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)
    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun id3(src: MediaByteSource, head: ByteArray): EmbeddedMediaTags? {
        val major = head[3].toInt()
        if (major !in 3..4) return null
        val flags = head[5].toInt()
        val size = syncsafe(head, 6)
        if (size <= 0 || size > MAX_TAG_BYTES) return null
        if (flags and 0x80 != 0 && major == 3) return EmbeddedMediaTags(EmbeddedTagFormat.ID3V2) // whole-tag unsynchronisation: skip safely
        val tag = src.read(10, size)
        var p = 0
        if (flags and 0x40 != 0 && tag.size >= 4) {
            val ext = if (major == 4) syncsafe(tag, 0) else (be32(tag, 0).toInt() + 4)
            if (ext !in 4..tag.size) return null
            p = ext
        }
        val t = mutableMapOf<String, String>()
        var pic: ByteArray? = null; var picMime: String? = null; var picType = -1
        while (p + 10 <= tag.size) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val len = if (major == 4) syncsafe(tag, p + 4) else be32(tag, p + 4).toInt()
            val fflags = tag[p + 9].toInt()
            if (len <= 0 || p + 10 + len > tag.size) break
            val body = tag.copyOfRange(p + 10, p + 10 + len)
            p += 10 + len
            // Compressed/encrypted/unsynchronised frames are skipped rather than mis-read.
            if ((major == 3 && fflags and 0xE0 != 0) || (major == 4 && fflags and 0x0F != 0)) continue
            when {
                id.startsWith("T") && id != "TXXX" -> id3Text(body)?.let { if (id !in t) t[id] = it }
                id == "APIC" && body.size > 4 -> id3Picture(body)?.let { (mime, type, data) ->
                    // Prefer the front cover (type 3); otherwise keep the first picture.
                    if (pic == null || (type == 3 && picType != 3)) { pic = data; picMime = mime; picType = type }
                }
            }
        }
        return EmbeddedMediaTags(EmbeddedTagFormat.ID3V2, title = t["TIT2"], artist = t["TPE1"], albumArtist = t["TPE2"],
            album = t["TALB"], year = year(t["TDRC"] ?: t["TYER"]), track = t["TRCK"]?.substringBefore('/')?.trim()?.toIntOrNull(),
            picture = pic, pictureMime = picMime)
    }

    private fun charset(enc: Int): Charset? = when (enc) {
        0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> null
    }

    private fun id3Text(body: ByteArray): String? {
        if (body.isEmpty()) return null
        val cs = charset(body[0].toInt()) ?: return null
        return clean(String(body, 1, body.size - 1, cs).split('\u0000').firstOrNull { it.isNotBlank() })
    }

    /** Index just past a terminator for [enc] starting at [from]; -1 if none. */
    private fun terminator(b: ByteArray, from: Int, enc: Int): Int {
        if (enc == 1 || enc == 2) {
            var i = from
            while (i + 1 < b.size) { if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i + 2; i += 2 }
            return -1
        }
        for (i in from until b.size) if (b[i].toInt() == 0) return i + 1
        return -1
    }

    private fun id3Picture(b: ByteArray): Triple<String, Int, ByteArray>? {
        val enc = b[0].toInt()
        val mimeEnd = terminator(b, 1, 0).takeIf { it > 0 } ?: return null
        val mime = String(b, 1, mimeEnd - 2, Charsets.ISO_8859_1).lowercase()
        if (mimeEnd >= b.size) return null
        val type = b[mimeEnd].toInt() and 0xFF
        val descEnd = terminator(b, mimeEnd + 1, enc).takeIf { it > 0 } ?: return null
        val data = b.copyOfRange(descEnd, b.size)
        if (data.isEmpty() || data.size > MAX_PICTURE_BYTES || mime == "-->") return null
        return Triple(mime.ifEmpty { "image/jpeg" }, type, data)
    }

    // ---------------- FLAC ----------------
    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun flac(src: MediaByteSource): EmbeddedMediaTags {
        var off = 4L
        var budget = MAX_TAG_BYTES.toLong()
        val c = mutableMapOf<String, String>()
        var pic: ByteArray? = null; var picMime: String? = null; var picType = -1
        repeat(64) {
            val h = src.read(off, 4)
            if (h.size < 4) return@repeat
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            off += 4
            if (type == 4 || type == 6) {
                if (len > budget) return finishFlac(c, pic, picMime)
                budget -= len
                val b = src.read(off, len)
                if (b.size < len) return finishFlac(c, pic, picMime)
                if (type == 4) vorbisComments(b, c)
                else flacPicture(b)?.let { (mime, t, data) -> if (pic == null || (t == 3 && picType != 3)) { pic = data; picMime = mime; picType = t } }
            }
            if (type == 127) return finishFlac(c, pic, picMime)
            off += len
            if (last) return finishFlac(c, pic, picMime)
        }
        return finishFlac(c, pic, picMime)
    }

    private fun finishFlac(c: Map<String, String>, pic: ByteArray?, mime: String?) = EmbeddedMediaTags(EmbeddedTagFormat.FLAC,
        title = c["TITLE"], artist = c["ARTIST"], albumArtist = c["ALBUMARTIST"] ?: c["ALBUM ARTIST"], album = c["ALBUM"],
        year = year(c["DATE"] ?: c["YEAR"]), track = c["TRACKNUMBER"]?.substringBefore('/')?.toIntOrNull(), picture = pic, pictureMime = mime)

    private fun vorbisComments(b: ByteArray, out: MutableMap<String, String>) {
        if (b.size < 8) return
        var p = 4 + le32(b, 0).toInt().also { if (it < 0 || it > b.size - 8) return }
        if (p + 4 > b.size) return
        val n = le32(b, p).toInt(); p += 4
        repeat(minOf(maxOf(n, 0), 500)) {
            if (p + 4 > b.size) return
            val l = le32(b, p).toInt(); p += 4
            if (l < 0 || p + l > b.size) return
            val kv = String(b, p, l, Charsets.UTF_8); p += l
            val k = kv.substringBefore('=', "").uppercase(); val v = clean(kv.substringAfter('=', ""))
            if (k.isNotEmpty() && v != null && k !in out) out[k] = v
        }
    }

    private fun flacPicture(b: ByteArray): Triple<String, Int, ByteArray>? {
        var p = 0
        fun need(n: Int) = p + n <= b.size
        if (!need(8)) return null
        val type = be32(b, p).toInt(); p += 4
        val ml = be32(b, p).toInt(); p += 4
        if (ml < 0 || !need(ml + 4)) return null
        val mime = String(b, p, ml, Charsets.ISO_8859_1).lowercase(); p += ml
        val dl = be32(b, p).toInt(); p += 4
        if (dl < 0 || !need(dl + 20)) return null
        p += dl + 16
        val len = be32(b, p).toInt(); p += 4
        if (len <= 0 || len > MAX_PICTURE_BYTES || !need(len) || mime == "-->") return null
        return Triple(mime, type, b.copyOfRange(p, p + len))
    }

    // ---------------- MP4 / M4A / M4B ----------------
    private fun mp4(src: MediaByteSource): EmbeddedMediaTags? {
        var atoms = 0
        fun children(start: Long, end: Long, f: (type: String, bodyStart: Long, bodyEnd: Long) -> Unit) {
            var off = start
            while (off + 8 <= end && atoms++ < MAX_ATOMS) {
                val h = src.read(off, 16)
                if (h.size < 8) return
                var size = be32(h, 0)
                val type = String(h, 4, 4, Charsets.ISO_8859_1)
                var header = 8
                if (size == 1L) { if (h.size < 16) return; size = (be32(h, 8) shl 32) or be32(h, 12); header = 16 }
                if (size == 0L) size = end - off
                if (size < header || off + size > end) return
                f(type, off + header, off + size)
                off += size
            }
        }
        val items = mutableMapOf<String, ByteArray>()
        children(0, src.size) { t, s, e ->
            if (t != "moov") return@children
            children(s, e) { t2, s2, e2 ->
                if (t2 != "udta") return@children
                children(s2, e2) { t3, s3, e3 ->
                    // 'meta' is a full box: 4 bytes of version/flags before its children.
                    if (t3 != "meta") return@children
                    children(s3 + 4, e3) { t4, s4, e4 ->
                        if (t4 != "ilst") return@children
                        children(s4, e4) { key, s5, e5 ->
                            children(s5, e5) { d, s6, e6 ->
                                val len = e6 - s6 - 8
                                if (d == "data" && len in 0..MAX_PICTURE_BYTES && key !in items) items[key] = src.read(s6 + 8, len.toInt())
                            }
                        }
                    }
                }
            }
        }
        if (items.isEmpty()) return EmbeddedMediaTags(EmbeddedTagFormat.MP4)
        fun text(k: String) = items[k]?.let { clean(String(it, Charsets.UTF_8)) }
        val cover = items["covr"]?.takeIf { it.isNotEmpty() }
        val trkn = items["trkn"]?.takeIf { it.size >= 4 }?.let { ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF) }?.takeIf { it > 0 }
        return EmbeddedMediaTags(EmbeddedTagFormat.MP4, title = text("©nam"), artist = text("©ART"), albumArtist = text("aART"),
            album = text("©alb"), year = year(text("©day")), track = trkn, picture = cover,
            pictureMime = cover?.let { if (it.size > 3 && it[0] == 0x89.toByte()) "image/png" else "image/jpeg" })
    }

    private fun year(s: String?): Int? = s?.let { Regex("(1[89]|20)\\d{2}").find(it)?.value?.toIntOrNull() }

    /** Tag text is untrusted UI input: strip controls, trim, cap length. */
    private fun clean(s: String?): String? = s?.filter { !it.isISOControl() && it != '﻿' }?.trim()?.take(300)?.ifEmpty { null }
}
