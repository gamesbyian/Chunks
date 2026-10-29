package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/** The Add-review option on the real native engine: policy + first step while still stopped. */
@RunWith(AndroidJUnit4::class)
class FinishInOrderAfterAddInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File
    private var engine: LocalTorrentEngine? = null
    private var hash: String? = null

    @Before fun setUp() { work = File(context.filesDir, "fio-add-test").apply { deleteRecursively(); mkdirs() } }
    @After fun tearDown() {
        hash?.let { FinishInOrderStore(context).remove(it) }
        engine?.close(); work.deleteRecursively()
    }

    @Test fun addReviewOptionPrioritisesTheFirstEpisodeBeforeStart() = runBlocking {
        val season = File(work, "Season 1").apply { mkdirs() }
        for (n in 3 downTo 1) File(season, "Public S01E0$n.mkv").writeBytes(ByteArray(32 * 1024) { (n + it).toByte() })
        val bytes = TorrentBuilder().path(season).pieceSize(16 * 1024).generate().entry().bencode()
        val target = File(work, "target").apply { mkdirs() }
        val local = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0")).also { it.start(); engine = it }
        val added = local.addStopped(bytes, target) as LocalAddResult.AddedStopped
        hash = added.status.infoHash
        val note = enableFinishInOrderAfterAdd(context, local, added.status.infoHash)
        assertTrue(note, note.startsWith("Finish in Order is on for 3 files"))
        val files = requireNotNull(local.files(added.status.infoHash)).associateBy { it.path.substringAfterLast('/') }
        assertEquals(7, files.getValue("Public S01E01.mkv").priority)
        assertEquals(4, files.getValue("Public S01E02.mkv").priority)
        assertEquals(1, files.getValue("Public S01E03.mkv").priority)
        assertTrue("still stopped", requireNotNull(local.status(added.status.infoHash)).stopped)
        assertNotNull(FinishInOrderStore(context).get(added.status.infoHash))
    }

    @Test fun magnetRequestIsSetUpOnTheFirstTickAfterTheFileListExists() {
        val season = File(work, "Season 2").apply { mkdirs() }
        for (n in 1..3) File(season, "Public S02E0$n.mkv").writeBytes(ByteArray(32 * 1024) { (n * 7 + it).toByte() })
        val bytes = TorrentBuilder().path(season).pieceSize(16 * 1024).generate().entry().bencode()
        val target = File(work, "target2").apply { mkdirs() }
        val shared = LocalEngineHolder.get(context)
        val added = shared.addStopped(bytes, target) as LocalAddResult.AddedStopped
        hash = added.status.infoHash
        try {
            val store = FinishInOrderStore(context)
            assertTrue(store.requestAfterMetadata(added.status.infoHash))
            FinishInOrderController.tick(context, shared.list())
            assertNotNull("policy created once metadata is known", store.get(added.status.infoHash))
            assertTrue(store.afterMetadataRequests().isEmpty())
            val first = requireNotNull(shared.files(added.status.infoHash))
                .single { it.path.endsWith("Public S02E01.mkv") }
            assertEquals(7, first.priority)
        } finally {
            shared.removeKeepingData(added.status.infoHash)
        }
    }
}
