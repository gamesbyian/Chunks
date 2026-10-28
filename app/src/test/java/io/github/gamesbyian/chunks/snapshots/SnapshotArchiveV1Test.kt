package io.github.gamesbyian.chunks.snapshots

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class SnapshotArchiveV1Test {
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private val manifest = """{"schemaVersion":1,"items":[]}""".toByteArray()

    @Test fun canonicalBoundedArchiveRoundTripsAndIsDeterministic() {
        val sample = "d4:infod4:name4:testee".toByteArray()
        val archive = SnapshotArchiveV1.encode(manifest, mapOf(hash to sample))
        assertArrayEquals(archive, SnapshotArchiveV1.encode(manifest, mapOf(hash to sample)))
        val decoded = SnapshotArchiveV1.decode(archive)
        assertArrayEquals(manifest, decoded.manifestJson)
        assertEquals(setOf(hash), decoded.metainfoByHash.keys)
        assertArrayEquals(sample, decoded.metainfoByHash.getValue(hash))
    }

    @Test fun rejectTamperedPayloadEvenWhenZipCrcWasRecomputed() {
        val archive = SnapshotArchiveV1.encode(manifest, mapOf(hash to "original".toByteArray()))
        val members = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                members[entry.name] = input.readBytes()
                input.closeEntry()
            }
        }
        members["torrents/$hash.torrent"] = "tampered".toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in members) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        expectRejected { SnapshotArchiveV1.decode(output.toByteArray()) }
    }

    @Test fun rejectZipTraversalAndUnexpectedMembersBeforeAnyFilesystemWrite() {
        for (name in listOf("../manifest.json", "/torrents/$hash.torrent",
            "torrents/../../escape.torrent", "payload/movie.mkv")) {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("unsafe".toByteArray())
                zip.closeEntry()
            }
            expectRejected { SnapshotArchiveV1.decode(output.toByteArray()) }
        }
    }

    @Test fun rejectDuplicateNamesAndMissingChecksums() {
        val duplicate = ByteArrayOutputStream()
        ZipOutputStream(duplicate).use { zip ->
            for (name in listOf("manifest.json", "Manifest.json")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(manifest)
                zip.closeEntry()
            }
        }
        // Java's ZIP writer correctly forbids duplicate entry names, so make
        // an adversarial ZIP by rewriting both copies of the second name in
        // its local and central-directory headers to the exact first name.
        val duplicateBytes = duplicate.toByteArray()
        val from = "Manifest.json".toByteArray()
        val to = "manifest.json".toByteArray()
        var replacements = 0
        for (offset in 0..duplicateBytes.size - from.size) {
            if (from.indices.all { i -> duplicateBytes[offset + i] == from[i] }) {
                to.copyInto(duplicateBytes, offset)
                replacements++
            }
        }
        assertEquals("Local header and central-directory copy", 2, replacements)
        expectRejected { SnapshotArchiveV1.decode(duplicateBytes) }
        val missing = ByteArrayOutputStream()
        ZipOutputStream(missing).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
        }
        expectRejected { SnapshotArchiveV1.decode(missing.toByteArray()) }
    }

    @Test fun rejectCompressedBombAndOverlargeInput() {
        val bomb = ByteArrayOutputStream()
        ZipOutputStream(bomb).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(ByteArray(512 * 1024 + 1) { '{'.code.toByte() })
            zip.closeEntry()
        }
        expectRejected { SnapshotArchiveV1.decode(bomb.toByteArray()) }
        expectRejected { SnapshotArchiveV1.encode(manifest,
            mapOf(hash to ByteArray(2 * 1024 * 1024 + 1))) }
    }

    @Test fun boundedArchiveMemberReaderNeverSpinsOnZeroProgress() {
        val stuck = object : java.io.InputStream() {
            var attempts = 0
            override fun read(): Int = -1
            override fun read(bytes: ByteArray, offset: Int, len: Int): Int {
                attempts++
                return 0
            }
        }
        expectRejected {
            SnapshotArchiveV1.readBoundedMember(stuck, 1024, 1024L)
        }
        assertEquals("16 tolerated empty polls then fail closed", 17, stuck.attempts)
        val original = "a verified snapshot ZIP member".toByteArray()
        assertArrayEquals(original, SnapshotArchiveV1.readBoundedMember(
            ByteArrayInputStream(original), original.size, original.size.toLong()))
        expectRejected {
            SnapshotArchiveV1.readBoundedMember(
                ByteArrayInputStream(original), 10, original.size.toLong())
        }
        expectRejected {
            SnapshotArchiveV1.readBoundedMember(
                ByteArrayInputStream(original), original.size, 5L)
        }
    }

    @Test fun rejectNoncanonicalHashesAndMalformedUtf8() {
        expectRejected { SnapshotArchiveV1.encode(manifest, mapOf(hash.uppercase() to byteArrayOf(1))) }
        expectRejected { SnapshotArchiveV1.encode(byteArrayOf(0xc3.toByte(), 0x28), emptyMap()) }
    }

    private fun expectRejected(block: () -> Unit) {
        try {
            block()
            fail("Unsafe or corrupted archive must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected non-destructive refusal.
        }
    }
}
