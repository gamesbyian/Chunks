package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceTorrentArchiveTest {
    @get:Rule val files = TemporaryFolder()
    private val raw = ("d4:infod6:lengthi5e4:name8:test.txt" +
        "12:piece lengthi16384e6:pieces20:01234567890123456789ee").toByteArray()
    private val hash get() = TorrentMetainfoReader.preview(raw).infoHashV1
    private fun proof(verified: Boolean = true, environment: String = "phone") =
        SourceAddVerification(environment, hash, verified)
    private fun privateDir() = files.newFolder("chunks-private")

    @Test fun keepDoesNotChangeOriginalEvenWithoutAnyAddOutcome() {
        val root = privateDir()
        val original = files.newFile("source.torrent").apply { writeBytes(raw) }
        val result = SourceTorrentArchive(root).apply(
            OriginalTorrentHandling.KEEP, OriginalTorrentOrigin.ANDROID_DOCUMENT,
            raw, null)
        assertEquals(SourceArchiveOutcome.OriginalKept, result)
        assertArrayEquals(raw, original.readBytes())
        assertFalse(File(root, SourceTorrentArchive.DIRECTORY).exists())
    }

    @Test fun archiveRejectsAnythingOtherThanVerifiedNewExactSourceAndDocument() {
        val root = privateDir()
        val archive = SourceTorrentArchive(root)
        val negative = listOf(
            archive.apply(OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, raw, null),
            archive.apply(OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, raw, proof(verified = false)),
            archive.apply(OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, raw, proof(environment = "")))
        assertTrue(negative.all { it == SourceArchiveOutcome.UnverifiedAdd })
        assertEquals(SourceArchiveOutcome.NotEligible,
            archive.apply(OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.MAGNET, raw, proof()))
        assertEquals(SourceArchiveOutcome.NotEligible,
            archive.apply(OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.FETCHED_HTTPS, raw, proof()))
        assertEquals(SourceArchiveOutcome.IdentityMismatch,
            archive.apply(OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, raw, proof().copy(
                    expectedInfoHashV1 = "f".repeat(40))))
        assertEquals(SourceArchiveOutcome.IdentityMismatch,
            archive.apply(OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, "bad metadata".toByteArray(), proof()))
        assertFalse(File(root, SourceTorrentArchive.DIRECTORY).exists())
    }

    @Test fun validArchiveIsDurableExactByteCopyAndDeduplicatesAfterReopening() {
        val root = privateDir()
        val original = files.newFile("user-provided.torrent").apply { writeBytes(raw) }
        val first = SourceTorrentArchive(root).apply(
            OriginalTorrentHandling.ARCHIVE,
            OriginalTorrentOrigin.ANDROID_DOCUMENT, raw, proof())
        assertTrue(first.toString(), first is SourceArchiveOutcome.ArchivedCopy)
        val stored = first as SourceArchiveOutcome.ArchivedCopy
        assertFalse("First commit must be distinguished from an existing copy",
            stored.alreadyPresent)
        assertTrue(stored.relativeName.startsWith(hash + "-"))
        assertTrue(stored.relativeName.endsWith(".torrent"))
        assertFalse("Private URI/filename must not leak into archive identity",
            stored.relativeName.contains("user-provided"))
        val target = File(File(root, SourceTorrentArchive.DIRECTORY), stored.relativeName)
        assertArrayEquals(raw, target.readBytes())
        assertArrayEquals(raw, original.readBytes())
        assertEquals(hash, TorrentMetainfoReader.preview(target.readBytes()).infoHashV1)
        val second = SourceTorrentArchive(root).apply(
            OriginalTorrentHandling.ARCHIVE,
            OriginalTorrentOrigin.ANDROID_DOCUMENT, raw, proof())
        assertEquals(SourceArchiveOutcome.ArchivedCopy(stored.relativeName, true), second)
        assertEquals(1, requireNotNull(target.parentFile.listFiles()).size)
    }

    @Test fun wrongOrCorruptedAlreadyArchivedIdentityRefusesToOverwrite() {
        val root = privateDir()
        val archiver = SourceTorrentArchive(root)
        val result = archiver.apply(OriginalTorrentHandling.ARCHIVE,
            OriginalTorrentOrigin.ANDROID_DOCUMENT, raw, proof())
            as SourceArchiveOutcome.ArchivedCopy
        val target = File(File(root, SourceTorrentArchive.DIRECTORY), result.relativeName)
        val corrupt = "DO NOT OVERWRITE THIS FILE".toByteArray()
        target.writeBytes(corrupt)
        assertEquals(SourceArchiveOutcome.CorruptOrUnsafeArchive,
            archiver.apply(OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, raw, proof()))
        assertArrayEquals(corrupt, target.readBytes())
    }

    @Test fun deleteCanOnlyRequestSeparateApprovalAndNeverTouchesOriginal() {
        val root = privateDir()
        val original = files.newFile("delete-only-by-provider.torrent").apply { writeBytes(raw) }
        val archiver = SourceTorrentArchive(root)
        assertEquals(SourceArchiveOutcome.UnverifiedAdd,
            archiver.apply(OriginalTorrentHandling.DELETE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, raw, proof(verified = false)))
        assertEquals(SourceArchiveOutcome.OriginalDeletionRequiresApproval,
            archiver.apply(OriginalTorrentHandling.DELETE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, raw, proof()))
        assertArrayEquals(raw, original.readBytes())
        assertFalse(File(root, SourceTorrentArchive.DIRECTORY).exists())
    }

    @Test fun symlinkOrUnexpectedArchiveContentFailsClosedWithoutPayloadOrOriginalEffects() {
        val root = privateDir()
        val outside = files.newFolder("outside")
        val archivePath = File(root, SourceTorrentArchive.DIRECTORY).toPath()
        Files.createSymbolicLink(archivePath, outside.toPath())
        assertEquals(SourceArchiveOutcome.CorruptOrUnsafeArchive,
            SourceTorrentArchive(root).apply(OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, raw, proof()))
        assertEquals(0, requireNotNull(outside.listFiles()).size)
        Files.delete(archivePath)
        Files.createDirectory(archivePath)
        File(archivePath.toFile(), "unrelated.txt").writeText("canary")
        assertEquals(SourceArchiveOutcome.CorruptOrUnsafeArchive,
            SourceTorrentArchive(root).apply(OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, raw, proof()))
        assertEquals("canary", File(archivePath.toFile(), "unrelated.txt").readText())
    }

    @Test fun boundedCatalogRefusesBeyondMaximumNumberOfCopies() {
        val root = privateDir()
        val directory = File(root, SourceTorrentArchive.DIRECTORY).apply { mkdirs() }
        repeat(SourceTorrentArchive.MAX_ARCHIVED_FILES) { i ->
            File(directory, hash + "-" + i.toString(16).padStart(64, '0') + ".torrent")
                .writeBytes(byteArrayOf())
        }
        assertEquals(SourceArchiveOutcome.ArchiveFull,
            SourceTorrentArchive(root).apply(OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, raw, proof()))
        assertEquals(SourceTorrentArchive.MAX_ARCHIVED_FILES,
            requireNotNull(directory.listFiles()).size)
    }

    @Test fun oversizedOrUnparseableInputNeverWrites() {
        val root = privateDir()
        val huge = ByteArray(TorrentMetainfoReader.MAX_BYTES + 1)
        assertEquals(SourceArchiveOutcome.IdentityMismatch,
            SourceTorrentArchive(root).apply(OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, huge, proof()))
        assertFalse(File(root, SourceTorrentArchive.DIRECTORY).exists())
    }
}
