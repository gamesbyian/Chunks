package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android private-filesystem witness, intentionally independent of
 * BitTorrent traffic. End-to-end Add/SAF authorization is a separate gate.
 */
@RunWith(AndroidJUnit4::class)
class SourceTorrentArchiveInstrumentedTest {
    private lateinit var sandbox: File
    private val bytes = ("d4:infod6:lengthi5e4:name8:test.txt" +
        "12:piece lengthi16384e6:pieces20:01234567890123456789ee").toByteArray()
    private val expectedHash by lazy { TorrentMetainfoReader.preview(bytes).infoHashV1 }
    private fun proof(newRegistration: Boolean = true) =
        SourceAddVerification("phone", expectedHash, newRegistration)

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        sandbox = File(context.cacheDir, "chunks-source-archive-" + UUID.randomUUID())
        assertTrue("Private fixture parent must be created", sandbox.mkdir())
    }

    @After fun cleanup() {
        if (::sandbox.isInitialized) sandbox.deleteRecursively()
    }

    @Test fun archiveCopiesVerifiedMetainfoIntoPrivateFilesWithoutTouchingSourceOrPayload() {
        val original = File(sandbox, "supplied-source.torrent").apply { writeBytes(bytes) }
        val payloadCanary = File(sandbox, "payload-untouched.bin").apply {
            writeBytes(byteArrayOf(0x12, 0x34, 0x56))
        }
        val privateFiles = File(sandbox, "app-private").apply { assertTrue(mkdir()) }
        val archive = SourceTorrentArchive(privateFiles)
        val first = archive.apply(OriginalTorrentHandling.ARCHIVE,
            OriginalTorrentOrigin.ANDROID_DOCUMENT, bytes, proof())
        assertTrue("Android filesystem must produce durable verified archive, not merely an RPC ACK: $first",
            first is SourceArchiveOutcome.ArchivedCopy)
        val created = first as SourceArchiveOutcome.ArchivedCopy
        assertFalse(created.alreadyPresent)
        val copy = File(privateFiles, SourceTorrentArchive.DIRECTORY + "/" + created.relativeName)
        assertArrayEquals(bytes, copy.readBytes())
        assertEquals(expectedHash, TorrentMetainfoReader.preview(copy.readBytes()).infoHashV1)
        assertArrayEquals(bytes, original.readBytes())
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56), payloadCanary.readBytes())

        // Reinstantiate to exercise on-disk dedup, rather than relying on
        // in-process object identity or an unverified cache map.
        val reopened = SourceTorrentArchive(privateFiles)
        assertEquals(SourceArchiveOutcome.ArchivedCopy(created.relativeName, true),
            reopened.apply(OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, bytes, proof()))
        assertEquals(1, requireNotNull(copy.parentFile?.listFiles()).size)
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test fun deleteIsNeverImplicitAndUnverifiedAddCannotArchive() {
        val source = File(sandbox, "original.torrent").apply { writeBytes(bytes) }
        val privateFiles = File(sandbox, "app-private").apply { assertTrue(mkdir()) }
        val archive = SourceTorrentArchive(privateFiles)
        assertEquals(SourceArchiveOutcome.UnverifiedAdd, archive.apply(
            OriginalTorrentHandling.ARCHIVE, OriginalTorrentOrigin.ANDROID_DOCUMENT,
            bytes, proof(newRegistration = false)))
        assertEquals(SourceArchiveOutcome.OriginalDeletionRequiresApproval, archive.apply(
            OriginalTorrentHandling.DELETE, OriginalTorrentOrigin.ANDROID_DOCUMENT,
            bytes, proof()))
        assertArrayEquals(bytes, source.readBytes())
        assertFalse(File(privateFiles, SourceTorrentArchive.DIRECTORY).exists())
    }

    @Test fun repeatedArchiveRefusesUnexpectedSiblingWithoutTouchingUserSource() {
        val original = File(sandbox, "original.torrent").apply { writeBytes(bytes) }
        val privateFiles = File(sandbox, "app-private").apply { assertTrue(mkdir()) }
        val first = SourceTorrentArchive(privateFiles).apply(
            OriginalTorrentHandling.ARCHIVE,
            OriginalTorrentOrigin.ANDROID_DOCUMENT, bytes, proof())
        assertTrue("Initial archive must be independently durable: $first",
            first is SourceArchiveOutcome.ArchivedCopy)
        val dir = File(privateFiles, SourceTorrentArchive.DIRECTORY)
        val stray = File(dir, "stray-source.txt").apply { writeText("leave intact") }
        assertEquals(SourceArchiveOutcome.CorruptOrUnsafeArchive,
            SourceTorrentArchive(privateFiles).apply(
                OriginalTorrentHandling.ARCHIVE,
                OriginalTorrentOrigin.ANDROID_DOCUMENT, bytes, proof()))
        assertEquals("leave intact", stray.readText())
        assertArrayEquals(bytes, original.readBytes())
    }

}
