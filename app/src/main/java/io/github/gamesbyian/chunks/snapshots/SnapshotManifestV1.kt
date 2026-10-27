package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale

/**
 * Strict, side-effect-free v1 inventory contract on top of SnapshotArchiveV1.
 *
 * The archive checksum proves transport integrity only. This parser separately
 * rejects duplicate JSON keys, unknown fields, unsupported formats and lying
 * infohash/file labels, BEFORE the importer considers an Add mutation.
 *
 * Only v1 torrent metainfo has a currently accepted identity verifier. v2 and
 * hybrid torrents must be retained as inventory-only by a future exporter
 * until an independently tested v2 verifier exists.
 */
internal object SnapshotManifestV1 {
    private val v1Hash = Regex("[0-9a-f]{40}")
    private const val MAX_ITEMS = 512
    private const val MAX_MANIFEST_BYTES = 512 * 1024
    private const val MAX_NAME = 1024
    private const val MAX_DIRECTORY_HINT = 4096
    private const val MAX_MAGNET = 8192

    enum class Source(val wire: String) {
        LOCAL("local"), RTORRENT("rtorrent")
    }

    enum class Recovery(val wire: String) {
        METAINFO("complete-metainfo"),
        MAGNET("magnet-fallback"),
        INVENTORY("inventory-only"),
        FAILED("failed")
    }

    data class Item(
        val infoHashV1: String,
        val originalName: String,
        val recovery: Recovery,
        val magnet: String? = null,
        /** Historical, untrusted display hint. Never submit as a destination. */
        val sourceDirectoryHint: String? = null
    )

    data class Manifest(
        val producer: String,
        val createdAtUtc: String,
        val source: Source,
        val items: List<Item>
    )

    data class Verified(
        val manifest: Manifest,
        val metainfoByHash: Map<String, ByteArray>,
        /** Contains full tracker metadata or tracker-bearing magnet context. */
        val requiresSensitiveExportWarning: Boolean
    ) {
        val restorableCount: Int get() = manifest.items.count {
            it.recovery == Recovery.METAINFO || it.recovery == Recovery.MAGNET
        }
        val inventoryOnlyCount: Int get() = manifest.items.count {
            it.recovery == Recovery.INVENTORY || it.recovery == Recovery.FAILED
        }
    }

    /** Export-side entry point. No filesystem or network writes. */
    fun encode(manifest: Manifest, metainfoByHash: Map<String, ByteArray>): ByteArray {
        verify(manifest, metainfoByHash)
        val itemsJson = manifest.items.sortedBy { it.infoHashV1 }.joinToString(",") { item ->
            buildString {
                append("{\"infoHashV1\":").append(quote(item.infoHashV1))
                append(",\"originalName\":").append(quote(item.originalName))
                append(",\"recovery\":").append(quote(item.recovery.wire))
                item.magnet?.let { append(",\"magnet\":").append(quote(it)) }
                item.sourceDirectoryHint?.let {
                    append(",\"sourceDirectoryHint\":").append(quote(it))
                }
                append('}')
            }
        }
        val json = "{\"schemaVersion\":1,\"producer\":" + quote(manifest.producer) +
            ",\"createdAtUtc\":" + quote(manifest.createdAtUtc) +
            ",\"source\":" + quote(manifest.source.wire) +
            ",\"items\":[" + itemsJson + "]}"
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES) { "Snapshot manifest too large" }
        return SnapshotArchiveV1.encode(bytes, metainfoByHash)
    }

    /** Import-side trust boundary. Call this, not raw transport decode, before preview. */
    fun decode(archive: ByteArray): Verified {
        val contents = SnapshotArchiveV1.decode(archive)
        val json = strictUtf8(contents.manifestJson)
        val root = Json(json).parse() as? Map<*, *>
            ?: throw IllegalArgumentException("Snapshot manifest must be an object")
        fields(root, setOf("schemaVersion", "producer", "createdAtUtc", "source", "items"))
        require(root["schemaVersion"] == 1L) { "Unsupported snapshot schema version" }
        val source = Source.entries.singleOrNull { it.wire == root["source"] }
            ?: throw IllegalArgumentException("Unsupported snapshot source kind")
        val entries = root["items"] as? List<*>
            ?: throw IllegalArgumentException("Missing snapshot items")
        require(entries.size <= MAX_ITEMS) { "Too many snapshot items" }
        val items = entries.map { entry ->
            val obj = entry as? Map<*, *>
                ?: throw IllegalArgumentException("Invalid snapshot item")
            fields(obj, setOf("infoHashV1", "originalName", "recovery",
                "magnet", "sourceDirectoryHint"))
            val recovery = Recovery.entries.singleOrNull { it.wire == obj["recovery"] }
                ?: throw IllegalArgumentException("Unknown snapshot recovery category")
            Item(
                string(obj, "infoHashV1"),
                string(obj, "originalName"),
                recovery,
                optionalString(obj, "magnet"),
                optionalString(obj, "sourceDirectoryHint")
            )
        }
        val manifest = Manifest(
            string(root, "producer"),
            string(root, "createdAtUtc"),
            source,
            items
        )
        return verify(manifest, contents.metainfoByHash)
    }

    private fun verify(manifest: Manifest, metainfo: Map<String, ByteArray>): Verified {
        require(manifest.producer == "chunks-android") { "Unknown snapshot producer" }
        require(manifest.createdAtUtc.length in 20..40 &&
            manifest.createdAtUtc.endsWith('Z') &&
            runCatching { Instant.parse(manifest.createdAtUtc) }.isSuccess) {
            "Invalid UTC creation timestamp"
        }
        require(manifest.items.size <= MAX_ITEMS) { "Too many snapshot entries" }
        val seen = HashSet<String>()
        val required = HashSet<String>()
        for (item in manifest.items) {
            require(v1Hash.matches(item.infoHashV1) && seen.add(item.infoHashV1)) {
                "Missing, noncanonical or duplicate snapshot infohash"
            }
            safeText(item.originalName, MAX_NAME, "original torrent name")
            require(item.originalName != "." && item.originalName != "..") {
                "Unsafe original torrent name"
            }
            item.sourceDirectoryHint?.let {
                safeText(it, MAX_DIRECTORY_HINT, "historical destination hint")
            }
            when (item.recovery) {
                Recovery.METAINFO -> {
                    require(item.magnet == null) {
                        "Complete metainfo entry must not claim a second recovery method"
                    }
                    required += item.infoHashV1
                }
                Recovery.MAGNET -> {
                    val magnet = item.magnet
                        ?: throw IllegalArgumentException("Missing fallback magnet")
                    safeText(magnet, MAX_MAGNET, "fallback magnet")
                    require(magnet.startsWith("magnet:?", ignoreCase = true)) {
                        "Invalid fallback magnet"
                    }
                    val xt = magnet.substringAfter('?').split('&')
                        .filter { it.substringBefore('=').equals("xt", ignoreCase = true) }
                        .map { it.substringAfter('=', missingDelimiterValue = "") }
                    require(xt.size == 1 &&
                        xt.single().equals("urn:btih:" + item.infoHashV1, ignoreCase = true)) {
                        "Fallback magnet identity does not match snapshot"
                    }
                }
                Recovery.INVENTORY, Recovery.FAILED -> require(item.magnet == null) {
                    "Inventory-only item must not claim a usable magnet"
                }
            }
        }
        require(metainfo.keys == required) {
            "Snapshot metainfo membership differs from declared complete entries"
        }
        for (hash in required) {
            val bytes = metainfo.getValue(hash)
            val preview = try {
                TorrentMetainfoReader.preview(bytes)
            } catch (error: Exception) {
                throw IllegalArgumentException("Unsupported or unsafe torrent metadata for " +
                    hash, error)
            }
            require(preview.infoHashV1 == hash) {
                "Snapshot torrent metainfo identity mismatch for " + hash
            }
            val item = manifest.items.first { it.infoHashV1 == hash }
            require(preview.name == item.originalName) {
                "Snapshot original name differs from verified torrent metainfo"
            }
        }
        return Verified(
            manifest,
            metainfo.mapValues { it.value.copyOf() },
            required.isNotEmpty() || manifest.items.any { it.magnet?.contains("&tr=", true) == true }
        )
    }

    private fun fields(obj: Map<*, *>, allowed: Set<String>) {
        require(obj.keys.all { it is String && it in allowed } && obj.keys.containsAll(
            when {
                allowed.contains("schemaVersion") ->
                    setOf("schemaVersion", "producer", "createdAtUtc", "source", "items")
                else -> setOf("infoHashV1", "originalName", "recovery")
            })) { "Missing or unexpected snapshot manifest fields" }
    }

    private fun string(obj: Map<*, *>, key: String): String =
        obj[key] as? String ?: throw IllegalArgumentException("Invalid snapshot field " + key)

    private fun optionalString(obj: Map<*, *>, key: String): String? {
        if (!obj.containsKey(key)) return null
        return string(obj, key) // Explicit null is not a valid optional value.
    }

    private fun safeText(value: String, maxLength: Int, name: String) {
        require(value.isNotEmpty() && value.length <= maxLength &&
            value.none { it.code < 32 || it.code == 127 ||
                it.isSurrogate() }) { "Unsafe or oversized " + name }
    }

    private fun strictUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("Snapshot manifest is not UTF-8")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 32) append("\\u" +
                    char.code.toString(16).padStart(4, '0')) else append(char)
            }
        }
        append('"')
    }

    /** Bounded strict JSON reader, independent of Android's mockable org.json. */
    private class Json(private val source: String) {
        private var at = 0
        private var nodes = 0
        fun parse(): Any {
            val value = read(0)
            whitespace()
            require(at == source.length) { "Trailing snapshot JSON content" }
            return value
        }
        private fun whitespace() {
            while (at < source.length && source[at] in " \t\r\n") at++
        }
        private fun read(depth: Int): Any {
            whitespace()
            require(depth <= 8 && ++nodes <= 4096 && at < source.length) {
                "Snapshot JSON nesting or size limit"
            }
            return when (source[at]) {
                '{' -> {
                    at++
                    val map = linkedMapOf<String, Any>()
                    whitespace()
                    if (consume('}')) return map
                    while (true) {
                        whitespace()
                        val key = readString()
                        require(key !in map) { "Duplicate snapshot JSON property" }
                        whitespace()
                        require(consume(':')) { "Missing JSON property separator" }
                        map[key] = read(depth + 1)
                        whitespace()
                        if (consume('}')) break
                        require(consume(',')) { "Missing JSON object separator" }
                    }
                    map
                }
                '[' -> {
                    at++
                    val list = ArrayList<Any>()
                    whitespace()
                    if (consume(']')) return list
                    while (true) {
                        require(list.size <= MAX_ITEMS) { "Excess snapshot JSON items" }
                        list += read(depth + 1)
                        whitespace()
                        if (consume(']')) break
                        require(consume(',')) { "Missing JSON array separator" }
                    }
                    list
                }
                '"' -> readString()
                't' -> { literal("true"); true }
                'f' -> { literal("false"); false }
                'n' -> { literal("null"); Null }
                else -> {
                    val start = at
                    if (source[at] == '-') at++
                    require(at < source.length && source[at] in '0'..'9') {
                        "Invalid snapshot JSON number"
                    }
                    if (source[at] == '0') at++ else {
                        while (at < source.length && source[at] in '0'..'9') at++
                    }
                    // Only integer schema versions are presently part of this contract.
                    source.substring(start, at).toLongOrNull()
                        ?: throw IllegalArgumentException("Invalid JSON integer")
                }
            }
        }
        private fun readString(): String {
            require(consume('"')) { "Expected snapshot JSON string" }
            val result = StringBuilder()
            while (at < source.length) {
                val char = source[at++]
                if (char == '"') {
                    val text = result.toString()
                    require(text.none { it.isSurrogate() }) { "Unpaired JSON surrogate" }
                    return text
                }
                if (char == '\\') {
                    require(at < source.length) { "Truncated JSON escape" }
                    val escape = source[at++]
                    val decoded = when (escape) {
                        '"' -> '"'
                        '\\' -> '\\'
                        '/' -> '/'
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            require(at + 4 <= source.length) { "Truncated JSON unicode escape" }
                            val hex = source.substring(at, at + 4)
                            require(hex.all { it in "0123456789abcdefABCDEF" }) {
                                "Invalid JSON unicode escape"
                            }
                            at += 4
                            val code = hex.toInt(16).toChar()
                            if (code.isHighSurrogate()) {
                                require(at + 6 <= source.length &&
                                    source.substring(at, at + 2) == "\\u") {
                                    "Unpaired JSON high surrogate"
                                }
                                at += 2
                                val lowText = source.substring(at, at + 4)
                                require(lowText.all { it in "0123456789abcdefABCDEF" }) {
                                    "Invalid JSON low surrogate"
                                }
                                at += 4
                                val low = lowText.toInt(16).toChar()
                                require(low.isLowSurrogate()) { "Unpaired JSON high surrogate" }
                                result.append(code)
                                low
                            } else {
                                require(!code.isLowSurrogate()) { "Unpaired JSON low surrogate" }
                                code
                            }
                        }
                        else -> throw IllegalArgumentException("Unsupported JSON escape")
                    }
                    result.append(decoded)
                } else {
                    require(char.code >= 32) { "Unescaped JSON control character" }
                    result.append(char)
                }
                require(result.length <= 16384) { "Snapshot JSON string is too large" }
            }
            throw IllegalArgumentException("Unterminated snapshot JSON string")
        }
        private fun literal(text: String) {
            require(source.regionMatches(at, text, 0, text.length)) {
                "Invalid snapshot JSON literal"
            }
            at += text.length
        }
        private fun consume(c: Char): Boolean {
            if (at >= source.length || source[at] != c) return false
            at++
            return true
        }
        private object Null
    }
}
