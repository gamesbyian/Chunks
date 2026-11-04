package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalDeleteDataDispatch
import io.github.gamesbyian.chunks.engine.LocalDeleteDataReadback
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
 * A removed torrent may be re-added in the same session ONLY after libtorrent
 * reports its old handle invalid (all old native tasks released). Never infer
 * safety from an elapsed time alone; a refusal before that remains correct.
 * An independent clean-restart path must also preserve unrelated jobs/bytes.
 *
 * All test paths are isolated beneath app-private filesDir; this test never
 * touches public Downloads, user torrent data or a network peer.
 */
@RunWith(AndroidJUnit4::class)
class LocalRemoveReAddSafetyInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test fun sameHashReAddsOnlyAfterNativeRetirementAndDoesNotDisruptOtherTransfer() {
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
            val removed = initial.addStoppedChecked(removedTorrent, downloads)
            val kept = initial.addStoppedChecked(otherTorrent, downloads)
            assertTrue("Initial native Add failed: $removed", removed is LocalAddResult.AddedStopped)
            assertTrue("Second native Add failed: $kept", kept is LocalAddResult.AddedStopped)
            val oldHash = (removed as LocalAddResult.AddedStopped).status.infoHash
            val otherHash = (kept as LocalAddResult.AddedStopped).status.infoHash
            assertFalse("Test requires independent hashes", oldHash.equals(otherHash, true))
            assertTrue("Session-only Remove must be acknowledged", initial.removeKeepingData(oldHash))
            assertEquals(null, initial.status(oldHash))

            // Native Remove is asynchronous. A first Add may be refused if
            // the old handle still owns disk work, or succeed if the native
            // handle has *already* become invalid. Both are safe outcomes.
            // The new engine must eventually release only this hash's claim,
            // with NO restart and NO interruption to the other torrent.
            var attempted: LocalAddResult = initial.addStoppedChecked(removedTorrent, downloads)
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
            var rejectedWhileDraining = 0
            while (attempted is LocalAddResult.Refused && System.nanoTime() < deadline) {
                rejectedWhileDraining++
                assertNotNull("A refused Add disturbed the unrelated session job",
                    initial.status(otherHash))
                assertArrayEquals(digest(removedBytes), digest(removedFile.readBytes()))
                Thread.sleep(100)
                attempted = initial.addStoppedChecked(removedTorrent, downloads)
            }
            assertTrue("Drained old native handle must permit same-session re-Add: $attempted",
                attempted is LocalAddResult.AddedStopped)
            val sameSession = (attempted as LocalAddResult.AddedStopped).status
            assertEquals(oldHash, sameSession.infoHash)
            assertTrue("Re-added transfer must remain stopped", sameSession.stopped)
            assertNotNull(initial.status(otherHash))
            assertArrayEquals(digest(removedBytes), digest(removedFile.readBytes()))
            assertArrayEquals(digest(otherBytes), digest(otherFile.readBytes()))
            assertArrayEquals(foreignDigest, digest(foreign.readBytes()))
            println("CHUNKS_NATIVE_RETIREMENT drained=true refusalsBeforeDrain=$rejectedWhileDraining")
            // Retire the newly re-added job again, retaining original data.
            // The restart path then independently proves unrelated-job
            // persistence and a fresh same-hash Add remains safe.
            assertTrue(initial.removeKeepingData(oldHash))
            assertEquals(null, initial.status(oldHash))
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

            val readded = newSession.addStoppedChecked(removedTorrent, downloads)
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

            // Now exercise the DISTINCT production delete-data API in the same
            // real installed native session, with the same pre-existing bytes.
            // A per-hash successful dispatch is insufficient: require exact
            // TORRENT_DELETED alert plus independently absent payload.
            val delete = newSession.removeWithDataIfMatching(
                readdedStatus.infoHash, readdedStatus.name,
                readdedStatus.savePath, readdedStatus.nativeReviewToken)
            assertEquals("Actual delete-files request was refused",
                LocalDeleteDataDispatch.REQUESTED, delete.dispatch)
            assertEquals(null, newSession.status(oldHash))
            val deleteDeadline = System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
            var deletion = delete.payloadReadback()
            while (deletion == LocalDeleteDataReadback.UNAVAILABLE &&
                System.nanoTime() < deleteDeadline) {
                Thread.sleep(100)
                deletion = delete.payloadReadback()
            }
            assertEquals("Missing positive native alert AND physical payload absence",
                LocalDeleteDataReadback.ABSENT, deletion)
            assertFalse("Pre-existing transfer data must actually be removed",
                removedFile.exists())
            assertNotNull("Remove and data must not retire a different session",
                newSession.status(otherHash))
            assertArrayEquals("Delete and data touched another active torrent",
                digest(otherBytes), digest(otherFile.readBytes()))
            assertArrayEquals("Delete and data touched unrelated sibling file",
                foreignDigest, digest(foreign.readBytes()))
        } finally {
            runCatching { restarted?.close() }
            runCatching { first?.close() }
            // Only our unique app-private fixture root may be removed.
            root.deleteRecursively()
        }
    }
}
