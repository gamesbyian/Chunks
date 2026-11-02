package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Real Android/libtorrent session exercise, not a mock.
 *
 * A user who removes a torrent and immediately opens the same .torrent again
 * must receive a safe refusal until the old native session joins its disk I/O.
 * A later clean session must permit an explicit re-Add, preserve original
 * payload bytes, and restore a different job independently.
 *
 * All test paths are isolated beneath app-private filesDir; this test never
 * touches public Downloads, user torrent data or a network peer.
 */
@RunWith(AndroidJUnit4::class)
class LocalRemoveReAddSafetyInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test fun sameHashRequiresNewNativeSessionAndDoesNotDisruptOtherTransfer() {
        val root = File(context.filesDir, "remove-readd-native-" + System.nanoTime())
        assertTrue(root.mkdir())
        val state = File(root, "state").apply { assertTrue(mkdir()) }
        val downloads = File(root, "downloads").apply { assertTrue(mkdir()) }
        val removableRoot = File(downloads, "Remove and re-add example").apply { assertTrue(mkdir()) }
        val otherRoot = File(downloads, "Unrelated active example").apply { assertTrue(mkdir()) }
        val removedBytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val otherBytes = ByteArray(96 * 1024) { (it % 239).toByte() }
        val removedFile = File(removableRoot, "removed-job.bin").apply { writeBytes(removedBytes) }
        val otherFile = File(otherRoot, "another-job.bin").apply { writeBytes(otherBytes) }
        val foreign = File(downloads, "not-a-torrent-payload").apply {
            writeText("must survive any torrent retirement")
        }
        val foreignDigest = digest(foreign.readBytes())
        val removedTorrent = TorrentBuilder().path(removableRoot)
            .pieceSize(16 * 1024).generate().entry().bencode()
        val otherTorrent = TorrentBuilder().path(otherRoot)
            .pieceSize(16 * 1024).generate().entry().bencode()
        val config = LocalEngineConfig(
            listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true,
            stateDirectory = state
        )
        var first: LocalTorrentEngine? = null
        var restarted: LocalTorrentEngine? = null
        try {
            val initial = LocalTorrentEngine(config).also { it.start() }
            first = initial
            val removed = initial.addStopped(removedTorrent, downloads)
            val kept = initial.addStopped(otherTorrent, downloads)
            assertTrue("Initial native Add failed: $removed", removed is LocalAddResult.AddedStopped)
            assertTrue("Second native Add failed: $kept", kept is LocalAddResult.AddedStopped)
            val oldHash = (removed as LocalAddResult.AddedStopped).status.infoHash
            val otherHash = (kept as LocalAddResult.AddedStopped).status.infoHash
            assertFalse("Test requires independent hashes", oldHash.equals(otherHash, true))
            assertTrue("Session-only Remove must be acknowledged", initial.removeKeepingData(oldHash))
            assertEquals(null, initial.status(oldHash))

            val immediate = initial.addStopped(removedTorrent, downloads)
            assertTrue("Old native I/O tombstone MUST refuse immediate same-hash Add: $immediate",
                immediate is LocalAddResult.Refused)
            assertNotNull("A refused re-Add must not disturb the unrelated live job",
                initial.status(otherHash))
            assertArrayEquals(digest(removedBytes), digest(removedFile.readBytes()))
            assertArrayEquals(digest(otherBytes), digest(otherFile.readBytes()))
            assertArrayEquals(foreignDigest, digest(foreign.readBytes()))

            // close() synchronously stops the native session and joins its
            // old work before path claims can be reused. The unrelated job
            // must be persisted, not silently erased to allow this re-Add.
            initial.close()
            first = null
            val newSession = LocalTorrentEngine(config).also { it.start() }
            restarted = newSession
            assertTrue("A clean restart should not report corrupt state: ${newSession.restoreProblems}",
                newSession.restoreProblems.isEmpty())
            assertEquals("A removed job must not resurrect from old resume data",
                null, newSession.status(oldHash))
            val restoredOther = newSession.status(otherHash)
            assertNotNull("Unrelated job was lost across the required restart", restoredOther)
            assertTrue("Restored job must not start sharing without fresh user consent",
                restoredOther!!.stopped)

            val readded = newSession.addStopped(removedTorrent, downloads)
            assertTrue("Explicit re-Add after native shutdown must be allowed: $readded",
                readded is LocalAddResult.AddedStopped)
            val readdedStatus = (readded as LocalAddResult.AddedStopped).status
            assertEquals(oldHash, readdedStatus.infoHash)
            assertTrue("Re-added job must remain stopped pending an explicit Start",
                readdedStatus.stopped)
            assertNotNull(newSession.status(otherHash))
            assertArrayEquals("Remove/re-Add must preserve the exact existing download",
                digest(removedBytes), digest(removedFile.readBytes()))
            assertArrayEquals("Unrelated torrent bytes were changed", digest(otherBytes),
                digest(otherFile.readBytes()))
            assertArrayEquals("Foreign sibling file was touched", foreignDigest,
                digest(foreign.readBytes()))
        } finally {
            runCatching { restarted?.close() }
            runCatching { first?.close() }
            // Only our unique app-private fixture root may be removed.
            root.deleteRecursively()
        }
    }
}
