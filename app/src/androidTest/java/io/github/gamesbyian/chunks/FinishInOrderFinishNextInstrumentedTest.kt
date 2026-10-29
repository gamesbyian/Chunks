package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner
import io.github.gamesbyian.chunks.engine.FinishInOrderRecord
import io.github.gamesbyian.chunks.engine.LocalAddResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/** One-tap "Finish this next" on the real panel and the real phone engine (#415). */
@RunWith(AndroidJUnit4::class)
class FinishInOrderFinishNextInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File
    private var hash: String? = null

    @Before fun setUp() { work = File(context.filesDir, "fio-next-test").apply { deleteRecursively(); mkdirs() } }
    @After fun tearDown() {
        hash?.let { FinishInOrderStore(context).remove(it); LocalEngineHolder.current()?.removeKeepingData(it) }
        work.deleteRecursively()
    }

    @Test fun finishThisNextMovesTheChosenEpisodeToHighPriority() {
        val season = File(work, "Season 3").apply { mkdirs() }
        for (n in 1..3) File(season, "Public S03E0$n.mkv").writeBytes(ByteArray(32 * 1024) { (n * 11 + it).toByte() })
        val bytes = TorrentBuilder().path(season).pieceSize(16 * 1024).generate().entry().bencode()
        val engine = LocalEngineHolder.get(context)
        val added = engine.addStopped(bytes, File(work, "target").apply { mkdirs() }) as LocalAddResult.AddedStopped
        val infoHash = added.status.infoHash.also { hash = it }
        val files = requireNotNull(engine.files(infoHash))
        val order = requireNotNull(FinishInOrderPlanner.suggest(files)).order
        val store = FinishInOrderStore(context)
        store.save(infoHash, FinishInOrderRecord(FinishInOrderPlanner.policyFor(
            FinishInOrderPlanner.Mode.BALANCED, files, order)))
        runBlocking { FinishInOrderController.stepNow(context, infoHash) }

        rule.setContent { ChunksTheme { FinishInOrderPanel(infoHash, files, busy = false) {} } }
        rule.waitUntil(15_000) {
            rule.onAllNodesWithContentDescription("Finish Public S03E03.mkv next").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Finish Public S03E03.mkv next").performClick()
        rule.waitUntil(15_000) {
            engine.files(infoHash)?.single { it.path.endsWith("S03E03.mkv") }?.priority == 7
        }
        val byName = requireNotNull(engine.files(infoHash)).associate { it.path.substringAfterLast('/') to it.priority }
        assertEquals(7, byName["Public S03E03.mkv"])
        assertEquals(4, byName["Public S03E01.mkv"])
        assertEquals(1, byName["Public S03E02.mkv"])
        assertEquals("Public S03E03.mkv", store.get(infoHash)!!.policy.order.first().path.substringAfterLast('/'))
    }
}
