package io.github.gamesbyian.chunks.create

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * Builds BitTorrent v1 metainfo from caller-supplied file streams, without the
 * torrent engine, the network or Android (Create & Share slice 1).
 *
 * Every file is read exactly once, in order, and must deliver exactly its
 * declared size: a file that changes while being hashed fails the build rather
 * than producing metadata that no longer matches the bytes. Paths are checked
 * with the same rules [io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader]
 * applies on import. Nothing here seeds, announces or publishes anything.
 */
object TorrentMetainfoBuilder {
    const val MAX_FILES = 20_000
    private const val MIN_PIECE = 16 * 1024
    private const val MAX_PIECE = 16 * 1024 * 1024

    class Entry(
        /** Path inside the torrent: one segment for a single-file torrent's file name. */
        val path: List<String>,
        val sizeBytes: Long,
        val open: () -> InputStream
    )

    class Request(
        /** Torrent name: the single file's name, or the top folder of a multi-file torrent. */
        val name: String,
        val entries: List<Entry>,
        /** Tracker tiers; empty for a trackerless (DHT-only) torrent. */
        val trackerTiers: List<List<String>> = emptyList(),
        val private: Boolean = false,
        /** Zero chooses a size giving roughly 1,000 to 2,000 pieces. */
        val pieceLength: Int = 0,
        val comment: String? = null,
        /** Seconds since the epoch, or null to leave creation date out (deterministic output). */
        val creationDate: Long? = null
    )

    class Result(
        val torrent: ByteArray,
        val infoHashV1: String,
        val pieceLength: Int,
        val pieceCount: Int,
        val totalBytes: Long,
        /** Null for private torrents: their trackers are not shared through magnets. */
        val magnet: String?
    )

    class Cancelled : Exception("Torrent creation was cancelled")

    fun autoPieceLength(totalBytes: Long): Int {
        var piece = MIN_PIECE.toLong()
        while (piece < MAX_PIECE && totalBytes / piece > 2_000) piece *= 2
        return piece.toInt()
    }

    fun build(
        request: Request,
        /** Bytes hashed so far, after each piece. */
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Result {
        val name = segment(request.name)
        val entries = request.entries
        require(entries.isNotEmpty() && entries.size <= MAX_FILES) { "Choose between 1 and $MAX_FILES files" }
        val single = entries.size == 1 && entries[0].path.size == 1 && entries[0].path[0] == name
        val seenFiles = HashSet<List<String>>()
        val seenParents = HashSet<List<String>>()
        var total = 0L
        for (entry in entries) {
            require(entry.path.isNotEmpty() && entry.path.size <= 64) { "Invalid file path depth" }
            val path = entry.path.map(::segment)
            require(path !in seenFiles && path !in seenParents) { "Duplicate or conflicting file path: ${path.joinToString("/")}" }
            for (i in 1 until path.size) {
                require(path.take(i) !in seenFiles) { "File path conflicts with a folder: ${path.joinToString("/")}" }
                seenParents += path.take(i)
            }
            seenFiles += path
            require(entry.sizeBytes >= 0) { "Negative file size" }
            require(total <= Long.MAX_VALUE - entry.sizeBytes) { "Total size overflow" }
            total += entry.sizeBytes
        }
        require(total > 0) { "The chosen files are empty" }
        val trackers = request.trackerTiers.map { tier -> tier.map(::tracker) }.filter { it.isNotEmpty() }
        val pieceLength = if (request.pieceLength == 0) autoPieceLength(total) else request.pieceLength
        require(pieceLength in MIN_PIECE..MAX_PIECE && pieceLength and (pieceLength - 1) == 0) {
            "Piece size must be a power of two between 16 KiB and 16 MiB"
        }

        val pieces = hashPieces(entries, pieceLength, total, onProgress, isCancelled)
        val info = linkedMapOf<String, Any>()
        if (single) info["length"] = total
        else info["files"] = entries.map { mapOf("length" to it.sizeBytes, "path" to it.path) }
        info["name"] = name
        info["piece length"] = pieceLength.toLong()
        info["pieces"] = pieces
        if (request.private) info["private"] = 1L
        val infoBytes = Bencode.encode(info)

        val root = linkedMapOf<String, Any>()
        if (trackers.isNotEmpty()) {
            root["announce"] = trackers[0][0]
            if (trackers.size > 1 || trackers[0].size > 1) root["announce-list"] = trackers
        }
        request.comment?.trim()?.takeIf { it.isNotEmpty() }?.let {
            require(it.length <= 1024 && it.none { c -> c.code < 32 && c != '\n' }) { "Invalid comment" }
            root["comment"] = it
        }
        root["created by"] = "Chunks"
        request.creationDate?.let { require(it > 0) { "Invalid creation date" }; root["creation date"] = it }
        root["info"] = Bencode.Raw(infoBytes)
        val torrent = Bencode.encode(root)
        val hash = MessageDigest.getInstance("SHA-1").digest(infoBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val magnet = if (request.private) null else buildString {
            append("magnet:?xt=urn:btih:").append(hash)
            append("&dn=").append(encode(name))
            append("&xl=").append(total)
            for (url in trackers.flatten().distinct()) append("&tr=").append(encode(url))
        }
        return Result(torrent, hash, pieceLength, pieces.size / 20, total, magnet)
    }

    private fun hashPieces(
        entries: List<Entry>, pieceLength: Int, total: Long,
        onProgress: (Long) -> Unit, isCancelled: () -> Boolean
    ): ByteArray {
        val digests = ByteArrayOutputStream()
        val sha1 = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(64 * 1024)
        var inPiece = 0
        var hashed = 0L
        for (entry in entries) {
            var remaining = entry.sizeBytes
            entry.open().use { input ->
                while (remaining > 0) {
                    if (isCancelled()) throw Cancelled()
                    val want = minOf(buffer.size.toLong(), remaining, (pieceLength - inPiece).toLong()).toInt()
                    val read = input.read(buffer, 0, want)
                    check(read > 0) { "${entry.path.joinToString("/")} is shorter than expected; it may have changed" }
                    sha1.update(buffer, 0, read)
                    inPiece += read; remaining -= read; hashed += read
                    if (inPiece == pieceLength) {
                        digests.write(sha1.digest()); inPiece = 0
                        onProgress(hashed)
                    }
                }
                check(input.read() == -1) { "${entry.path.joinToString("/")} is longer than expected; it may have changed" }
            }
        }
        if (inPiece > 0) { digests.write(sha1.digest()); onProgress(hashed) }
        check(hashed == total)
        return digests.toByteArray()
    }

    private fun segment(value: String): String {
        require(value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 1024 &&
            value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) {
            "Unsafe or empty name: $value"
        }
        return value
    }

    /** Tracker URLs only; a URL with embedded credentials is refused, never silently kept. */
    private fun tracker(value: String): String {
        val url = value.trim()
        val uri = try { URI(url) } catch (_: Exception) { throw IllegalArgumentException("Invalid tracker URL") }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(url.length <= 2048 && (scheme == "http" || scheme == "https" || scheme == "udp") &&
            !uri.isOpaque && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
            (scheme != "udp" || uri.port in 1..65535)) { "Invalid tracker URL" }
        return url
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** Minimal canonical bencode writer: dictionary keys sorted by raw bytes. */
internal object Bencode {
    /** Already-encoded bencode, inserted verbatim (the info dictionary). */
    class Raw(val bytes: ByteArray)

    fun encode(value: Any): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, value: Any) {
        when (value) {
            is Raw -> out.write(value.bytes)
            is Long -> out.write("i${value}e".toByteArray())
            is Int -> out.write("i${value}e".toByteArray())
            is String -> bytes(out, value.toByteArray(Charsets.UTF_8))
            is ByteArray -> bytes(out, value)
            is List<*> -> { out.write('l'.code); value.forEach { write(out, requireNotNull(it)) }; out.write('e'.code) }
            is Map<*, *> -> {
                out.write('d'.code)
                value.entries.map { (it.key as String).toByteArray(Charsets.UTF_8) to requireNotNull(it.value) }
                    .sortedWith { a, b -> compareBytes(a.first, b.first) }
                    .forEach { (key, item) -> bytes(out, key); write(out, item) }
                out.write('e'.code)
            }
            else -> error("Cannot bencode ${value::class}")
        }
    }

    private fun bytes(out: ByteArrayOutputStream, value: ByteArray) {
        out.write("${value.size}:".toByteArray()); out.write(value)
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 255) - (b[i].toInt() and 255)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
