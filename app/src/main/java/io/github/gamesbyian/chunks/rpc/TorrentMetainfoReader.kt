package io.github.gamesbyian.chunks.rpc

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Immutable, content-verified local .torrent preview; never starts a transfer. */
data class TorrentMetainfoPreview(
    val name: String,
    val infoHashV1: String,
    val fileCount: Int,
    val sizeBytes: Long,
    val pieceLength: Long
)

/**
 * Bounded bencode reader for BitTorrent v1 metainfo. Computes infohash from
 * the ORIGINAL on-disk info dictionary bytes, not re-encoded values.
 *
 * The importer intentionally refuses v2/hybrid and noncanonical/path-unsafe
 * metainfo until those formats have separate acceptance. It never opens
 * remote URLs, creates server directories or sends RPC commands.
 */
object TorrentMetainfoReader {
    const val MAX_BYTES = 2_097_152
    private const val MAX_NODES = 100_000
    private const val MAX_DEPTH = 32

    fun preview(bytes: ByteArray): TorrentMetainfoPreview {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Torrent metadata must be at most 2 MiB" }
        val reader = Reader(bytes)
        val root = reader.readRoot()
        val info = root["info"] as? Map<*, *> ?: error("Missing torrent info dictionary")
        require(info["meta version"] == null) { "BitTorrent v2/hybrid not yet supported" }
        val name = cleanPart(info["name.utf-8"] ?: info["name"])
        val pieceLength = positive(info["piece length"])
        require(pieceLength <= 64 * 1024 * 1024L) { "Unsupported piece length" }
        val pieces = info["pieces"] as? ByteArray ?: error("Missing v1 piece hashes")
        require(pieces.size >= 20 && pieces.size % 20 == 0) { "Invalid v1 piece hashes" }

        val files = info["files"]
        val fileCount: Int
        val total: Long
        if (files == null) {
            fileCount = 1
            total = nonnegative(info["length"])
        } else {
            val list = files as? List<*> ?: error("Invalid file list")
            require(list.isNotEmpty() && list.size <= 20_000) { "Unsupported torrent file count" }
            var sum = 0L
            for (item in list) {
                val row = item as? Map<*, *> ?: error("Invalid file entry")
                val path = (row["path.utf-8"] ?: row["path"]) as? List<*>
                    ?: error("Invalid file path")
                require(path.isNotEmpty() && path.size <= 64) { "Invalid file path depth" }
                path.forEach { cleanPart(it) }
                val length = nonnegative(row["length"])
                require(sum <= Long.MAX_VALUE - length) { "Torrent size overflow" }
                sum += length
            }
            fileCount = list.size
            total = sum
        }
        // Every v1 piece has one 20-byte SHA-1 digest. A syntactically valid
        // bencoded file with a contradictory count must not be sent to a real
        // server. Divide before rounding upward so huge payloads cannot overflow.
        val expectedPieces = total / pieceLength +
            if (total % pieceLength == 0L) 0L else 1L
        require(expectedPieces == pieces.size.toLong() / 20L) {
            "Torrent piece hashes do not match payload size and piece length"
        }
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(bytes.copyOfRange(reader.infoStart, reader.infoEnd))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return TorrentMetainfoPreview(name, hash, fileCount, total, pieceLength)
    }

    private fun positive(value: Any?): Long {
        val number = value as? Long ?: error("Invalid positive torrent integer")
        require(number > 0) { "Torrent piece length must be positive" }
        return number
    }

    private fun nonnegative(value: Any?): Long {
        val number = value as? Long ?: error("Missing or invalid file size")
        require(number >= 0) { "Negative torrent file length" }
        return number
    }

    private fun cleanPart(value: Any?): String {
        val raw = value as? ByteArray ?: error("Missing torrent filename")
        require(raw.size in 1..1024) { "Invalid torrent filename length" }
        val name = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw)).toString()
        require(name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) {
            "Unsafe path inside torrent"
        }
        return name
    }

    private class Reader(private val source: ByteArray) {
        var index = 0
        var infoStart = -1
        var infoEnd = -1
        var nodes = 0
        fun readRoot(): Map<String, Any> {
            val root = parse(0, true) as? Map<String, Any> ?: error("Torrent root is not a dictionary")
            require(index == source.size) { "Extra bytes after torrent metadata" }
            require(infoStart >= 0 && infoEnd > infoStart) { "Missing info dictionary" }
            return root
        }
        private fun parse(depth: Int, root: Boolean = false): Any {
            require(++nodes <= MAX_NODES && depth <= MAX_DEPTH && index < source.size) {
                "Torrent bencode nesting or element limit exceeded"
            }
            return when (source[index].toInt().toChar()) {
                'i' -> {
                    index++
                    val start = index
                    while (index < source.size && source[index].toInt().toChar() != 'e') index++
                    require(index < source.size) { "Unterminated torrent integer" }
                    val raw = source.copyOfRange(start, index).toString(Charsets.US_ASCII)
                    require(Regex("(0|-?[1-9][0-9]*)").matches(raw) && raw != "-0") {
                        "Noncanonical torrent integer"
                    }
                    index++
                    raw.toLongOrNull() ?: error("Torrent integer outside Long range")
                }
                'l' -> {
                    index++
                    val values = ArrayList<Any>()
                    while (index < source.size && source[index].toInt().toChar() != 'e') {
                        require(values.size < MAX_NODES) { "Too many torrent elements" }
                        values += parse(depth + 1)
                    }
                    require(index < source.size) { "Unterminated torrent list" }
                    index++
                    values
                }
                'd' -> {
                    index++
                    val values = LinkedHashMap<String, Any>()
                    while (index < source.size && source[index].toInt().toChar() != 'e') {
                        val keyBytes = readBytes()
                        val key = keyBytes.toString(Charsets.UTF_8)
                        require(key !in values) { "Duplicate torrent dictionary key" }
                        val valueStart = index
                        val value = parse(depth + 1)
                        if (root && key == "info") {
                            infoStart = valueStart
                            infoEnd = index
                        }
                        values[key] = value
                    }
                    require(index < source.size) { "Unterminated torrent dictionary" }
                    index++
                    values
                }
                in '0'..'9' -> readBytes()
                else -> error("Invalid bencode value")
            }
        }
        private fun readBytes(): ByteArray {
            val start = index
            while (index < source.size && source[index].toInt().toChar() in '0'..'9') index++
            require(index < source.size && source[index].toInt().toChar() == ':') {
                "Malformed torrent byte string"
            }
            val text = source.copyOfRange(start, index).toString(Charsets.US_ASCII)
            require(text.isNotEmpty() && (text == "0" || !text.startsWith("0"))) {
                "Noncanonical byte-string length"
            }
            val length = text.toIntOrNull() ?: error("Torrent byte-string length overflow")
            index++
            require(length >= 0 && length <= source.size - index) { "Truncated torrent byte string" }
            val bytes = source.copyOfRange(index, index + length)
            index += length
            return bytes
        }
    }
}
