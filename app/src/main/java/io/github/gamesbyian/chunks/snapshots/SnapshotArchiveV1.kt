package io.github.gamesbyian.chunks.snapshots

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The bounded transport container for portable .chunksnap archives.
 *
 * This owns ZIP members, order, checksums, traversal and decompression
 * guards, NOT the higher-level schema/metadata trust decision. Consumers
 * MUST separately validate the manifest, exact torrent metainfo identity
 * and intended destination BEFORE applying any transfer mutations.
 *
 * No archive entry is ever extracted into the filesystem by this type.
 */
internal object SnapshotArchiveV1 {
    private const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
    private const val MAX_TOTAL_UNCOMPRESSED = 64 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 512 * 1024
    private const val MAX_TORRENT_BYTES = 2 * 1024 * 1024
    private const val MAX_TORRENTS = 512
    private const val CHECKSUM_NAME = "checksums.json"
    private const val MANIFEST_NAME = "manifest.json"
    private val torrentPath = Regex("torrents/[a-f0-9]{40}\\.torrent")
    private val identity = Regex("[a-f0-9]{40}")

    data class Contents(
        val manifestJson: ByteArray,
        /** Keys are lower-case v1 infohash candidates, not proof of metainfo identity. */
        val metainfoByHash: Map<String, ByteArray>
    )

    private fun text(bytes: ByteArray): String {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Archive metadata is not valid UTF-8")
        }
    }

    private fun checkManifest(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_MANIFEST_BYTES) {
            "Missing or oversized snapshot manifest"
        }
        val body = text(bytes).trim()
        // Canonical JSON parsing/schema validation is deliberately a distinct
        // gate at the importer, not claimed by this container transport.
        require(body.startsWith("{") && body.endsWith("}")) {
            "Snapshot manifest must be JSON-object-shaped"
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    /** Deterministic checksum receipt covers manifest AND every metainfo file. */
    private fun receipt(members: Map<String, ByteArray>): ByteArray {
        val files = members.entries.sortedBy { it.key }.joinToString(",") { (name, bytes) ->
            """{"name":"$name","bytes":${bytes.size},"sha256":"${digest(bytes)}"}"""
        }
        return """{"format":"chunksnap-v1","files":[$files]}""".toByteArray(StandardCharsets.UTF_8)
    }

    fun encode(manifestJson: ByteArray, metainfoByHash: Map<String, ByteArray>): ByteArray {
        checkManifest(manifestJson)
        require(metainfoByHash.size <= MAX_TORRENTS) { "Too many torrent definitions" }
        val members = linkedMapOf(MANIFEST_NAME to manifestJson.copyOf())
        for ((originalHash, bytes) in metainfoByHash.entries.sortedBy { it.key }) {
            val hash = originalHash.lowercase(Locale.ROOT)
            require(identity.matches(hash) && originalHash == hash) {
                "Noncanonical torrent identity"
            }
            require(bytes.isNotEmpty() && bytes.size <= MAX_TORRENT_BYTES) {
                "Torrent metadata is empty or too large"
            }
            require(members.put("torrents/$hash.torrent", bytes.copyOf()) == null) {
                "Duplicate metainfo identity"
            }
        }
        require(members.values.sumOf { it.size.toLong() } <= MAX_TOTAL_UNCOMPRESSED) {
            "Snapshot payload is too large"
        }
        val checksums = receipt(members)
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                for ((path, bytes) in members + (CHECKSUM_NAME to checksums)) {
                    val entry = ZipEntry(path).apply { time = 0L }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray().also {
                require(it.size <= MAX_ARCHIVE_BYTES) { "Compressed snapshot is too large" }
            }
        }
    }

    /**
     * The ZIP entry reader must make forward progress. Even a malicious or
     * broken provider that repeatedly returns zero cannot spin on the UI/IO
     * coroutine indefinitely. Does not depend on the ZIP stream's length
     * claim, and never materializes more than the remaining byte budget.
     */
    internal fun readBoundedMember(
        input: java.io.InputStream,
        individualLimit: Int,
        remainingTotal: Long
    ): ByteArray {
        require(individualLimit in 1..MAX_TORRENT_BYTES &&
            remainingTotal in 0L..MAX_TOTAL_UNCOMPRESSED) {
            "Invalid snapshot member read budget"
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var noProgress = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                require(++noProgress <= 16) {
                    "Snapshot ZIP member provider made no progress"
                }
                continue
            }
            noProgress = 0
            require(output.size().toLong() + read <= individualLimit &&
                output.size().toLong() + read <= remainingTotal) {
                "Snapshot member decompression limit exceeded"
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    fun decode(archive: ByteArray): Contents {
        require(archive.isNotEmpty() && archive.size <= MAX_ARCHIVE_BYTES) {
            "Snapshot archive is empty or too large"
        }
        val members = linkedMapOf<String, ByteArray>()
        var expandedBytes = 0L
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory && (name == MANIFEST_NAME ||
                    name == CHECKSUM_NAME || torrentPath.matches(name))) {
                    "Unexpected or unsafe snapshot member"
                }
                require(name !in members && members.size < MAX_TORRENTS + 2) {
                    "Duplicate or excessive snapshot members"
                }
                val individualLimit = when (name) {
                    MANIFEST_NAME -> MAX_MANIFEST_BYTES
                    CHECKSUM_NAME -> MAX_MANIFEST_BYTES
                    else -> MAX_TORRENT_BYTES
                }
                val bytes = readBoundedMember(zip, individualLimit,
                    MAX_TOTAL_UNCOMPRESSED - expandedBytes)
                expandedBytes += bytes.size.toLong()
                members[name] = bytes
                zip.closeEntry()
            }
        }
        val manifest = members[MANIFEST_NAME] ?: throw IllegalArgumentException("Missing manifest")
        checkManifest(manifest)
        val actualReceipt = members.remove(CHECKSUM_NAME)
            ?: throw IllegalArgumentException("Missing snapshot checksums")
        require(actualReceipt.contentEquals(receipt(members))) {
            "Snapshot archive checksum or membership mismatch"
        }
        val torrents = linkedMapOf<String, ByteArray>()
        for ((name, bytes) in members) {
            if (name == MANIFEST_NAME) continue
            require(bytes.isNotEmpty()) { "Empty torrent metadata" }
            val hash = name.removePrefix("torrents/").removeSuffix(".torrent")
            require(identity.matches(hash) && torrents.put(hash, bytes) == null) {
                "Untrusted torrent entry"
            }
        }
        return Contents(manifest.copyOf(), torrents.mapValues { it.value.copyOf() })
    }
}
