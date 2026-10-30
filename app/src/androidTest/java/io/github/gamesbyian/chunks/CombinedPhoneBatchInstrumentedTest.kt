package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.model.CommandEvidence
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.LocalInventoryFrame
import java.io.File
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Emulator evidence that combined-view Start/Stop drives the real native
 * engine under the shared phone mutation fence, and that the fence then
 * blocks another phone command until a post-command list is accepted.
 */
@RunWith(AndroidJUnit4::class)
class CombinedPhoneBatchInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val work by lazy { File(requireNotNull(LocalEngineHolder.appDownloads(context)), "combined-batch-test").apply { deleteRecursively(); mkdirs() } }
    private var hash: String? = null

    @After fun tearDown() {
        hash?.let { LocalEngineHolder.current()?.removeKeepingData(it) }
        work.deleteRecursively()
    }

    @Test fun startThenStopAreReadbackVerifiedAndFenceAwaitsFreshList() = runBlocking {
        val root = File(work, "Combined fixture").apply { mkdirs() }
        File(root, "data.bin").writeBytes(ByteArray(48_000).also { Random(578).nextBytes(it) })
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
        val engine = LocalEngineHolder.get(context)
        val added = (engine.addStopped(torrent, work) as LocalAddResult.AddedStopped).status
        hash = added.infoHash
        val fence = PhoneMutationFence.revision

        fun live() = EnvironmentSnapshots.phone(LocalInventoryFrame(engine.list(), stale = false),
            confirmed = true, locked = false, observedAtEpochMillis = 1)
        fun acceptFreshRead() = assertTrue(fence.acceptObservation(fence.snapshot(), engine.list()))

        // An earlier test in this process may have left the shared fence awaiting a read.
        fence.acceptObservation(fence.snapshot(), engine.list())
        assertFalse("fence must be free before this test", fence.blocksMutations())
        val key = live().transfers.single { it.key.infoHash == added.infoHash }.key
        val started = runCombinedLifecycle(setOf(key), true, listOf(live()), null, null)
        assertEquals(CommandEvidence.VERIFIED, started.items.single().evidence)
        assertFalse(engine.status(added.infoHash)!!.stopped)
        // Until a list read after the command is accepted, no further phone command may run.
        assertTrue(fence.blocksMutations())
        val blocked = runCombinedLifecycle(setOf(key), false, listOf(live()), null, null)
        assertEquals("waiting for a fresh phone list", blocked.items.single().reason)
        assertFalse(engine.status(added.infoHash)!!.stopped)

        acceptFreshRead()
        val stopped = runCombinedLifecycle(setOf(key), false, listOf(live()), null, null)
        assertEquals(CommandEvidence.VERIFIED, stopped.items.single().evidence)
        assertTrue(engine.status(added.infoHash)!!.stopped)
        acceptFreshRead()
        assertFalse(fence.blocksMutations())
    }
}
