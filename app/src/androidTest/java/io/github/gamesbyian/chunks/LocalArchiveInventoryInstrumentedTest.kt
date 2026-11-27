package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.util.Random
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

@RunWith(AndroidJUnit4::class)
class LocalArchiveInventoryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualCompleteRarSetCanBeInspectedWhileOtherTorrentPiecesRemainCorrupt() {
        val root = File(context.cacheDir, "rar-early-" + System.nanoTime()).apply { assertTrue(mkdir()) }
        val seed = File(root, "Archive fixture").apply { assertTrue(mkdir()) }
        val names = listOf("Verified.part1.rar", "Verified.part2.rar")
        val archives = names.associateWith { name ->
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("rar-volume-review/$name").use { it.readBytes() }
        }
        archives.forEach { (name, bytes) -> File(seed, name).writeBytes(bytes) }
        val pieceSize = 16 * 1024
        val unrelated = ByteArray(6 * pieceSize).also { Random(741L).nextBytes(it) }
        File(seed, "Unrelated.bin").writeBytes(unrelated)
        val torrent = TorrentBuilder().path(seed).pieceSize(pieceSize).generate().entry().bencode()
        val destination = File(root, "destination").apply { assertTrue(mkdir()) }
        val payload = File(destination, seed.name).apply { assertTrue(mkdir()) }
        archives.forEach { (name, bytes) -> File(payload, name).writeBytes(bytes) }
        // Corrupt only the middle of the unrelated file. Even if the native
        // file order changes, both of its boundary pieces remain identical.
        val corrupt = unrelated.copyOf().also {
            for (i in 2 * pieceSize until 4 * pieceSize) it[i] = (it[i].toInt() xor 0x55).toByte()
        }
        File(payload, "Unrelated.bin").writeBytes(corrupt)
        val engine = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0"))
        try {
            engine.start()
            val hash = (engine.addStoppedChecked(torrent, destination) as LocalAddResult.AddedStopped).status.infoHash
            assertTrue(engine.beginStoppedVerification(hash))
            val deadline = System.nanoTime() + 30_000_000_000L
            var observed: LocalArchiveInventory.Review? = null
            while (System.nanoTime() < deadline) {
                val state = engine.status(hash)
                if (state != null && state.stopped && !state.stopsAfterCheck &&
                    state.state != LocalTransferState.CHECKING &&
                    state.progressPpm in 1 until 1_000_000) {
                    val current = LocalArchiveInventory.review(engine, hash)
                    if (current?.candidates?.singleOrNull()?.allListedComplete == true &&
                        !current.allFilesVerified) {
                        observed = current
                        break
                    }
                }
                Thread.sleep(100)
            }
            val review = requireNotNull(observed) { "No completed-volume/incomplete-torrent native witness" }
            val group = review.candidates.single()
            assertEquals(names, group.volumes.map { it.file.path.substringAfterLast('/') })
            assertEquals(2, group.listedCompleteCount)
            assertFalse(review.allFilesVerified)
            assertTrue(review.torrentProgressPpm in 1 until 1_000_000)
            assertNull(LocalFileInspection.review(engine, hash))
            assertFalse(engine.flushCompletedForLibraryRead(hash, 100))
            assertTrue(LocalArchiveInventory.canInspectHeaders(group))
            assertEquals(io.github.gamesbyian.chunks.model.RarVolumeStructure.TERMINAL_CHAIN,
                LocalArchiveInventory.inspectHeaders(engine, review, group))
            val after = requireNotNull(engine.status(hash))
            assertTrue(after.stopped)
            assertTrue(after.progressPpm in 1 until 1_000_000)
            assertEquals(0L, after.payloadDownloadedBytes)
            assertEquals(review.nativeToken, after.nativeReviewToken)
            archives.forEach { (name, bytes) -> assertArrayEquals(bytes, File(payload, name).readBytes()) }
            assertArrayEquals(corrupt, File(payload, "Unrelated.bin").readBytes())
        } finally {
            engine.close()
            root.deleteRecursively()
        }
    }

    @Test fun canonicalDetailsInspectsNativeVerifiedVolumeHeadersAndKeepsFilenameGaps() {
        val root = File(context.cacheDir, "rar-inventory-" + System.nanoTime()).apply { assertTrue(mkdir()) }
        val folder = File(root, "Source").apply { assertTrue(mkdir()) }
        // These name-only byte fixtures are independently verified by libtorrent.
        // Keep them alongside a real compressed set to distinguish the observations.
        val contents = listOf("Extra.rar", "Extra.r00", "Film.part01.rar", "Film.part03.rar", "Unrelated.bin")
            .mapIndexed { index, name ->
                File(folder, name) to ByteArray(32_768).also { Random(100L + index).nextBytes(it) }
            }.toMap() + listOf("Verified.part1.rar", "Verified.part2.rar").associate { name ->
                File(folder, name) to InstrumentationRegistry.getInstrumentation().context.assets
                    .open("rar-volume-review/$name").use { it.readBytes() }
            }
        contents.forEach { (file, bytes) -> file.writeBytes(bytes) }
        val engine = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0"))
        try {
            engine.start()
            val torrent = TorrentBuilder().path(folder).pieceSize(16 * 1024).generate().entry().bencode()
            val hash = (engine.addStopped(torrent, root) as LocalAddResult.AddedStopped).status.infoHash
            engine.resume(hash)
            val deadline = System.nanoTime() + 30_000_000_000L
            while (engine.status(hash)?.state != LocalTransferState.SEEDING) {
                if (System.nanoTime() >= deadline) fail("Native RAR inventory fixture did not seed")
                Thread.sleep(100)
            }
            val review = requireNotNull(LocalArchiveInventory.review(engine, hash))
            assertEquals(3, review.candidates.size)
            assertEquals(1_000_000, review.torrentProgressPpm)
            assertTrue(review.allFilesVerified)
            assertEquals(2, review.candidates.first().listedCompleteCount)
            assertEquals(1, review.candidates[1].missingCount)
            assertEquals(2, review.candidates[1].firstMissing)
            assertNull(LocalArchiveInventory.review(engine, "0".repeat(40)))

            val fence = LocalMutationFence.revision
            compose.waitUntil(5_000) {
                LocalDetailsRuns.running.isEmpty() &&
                    fence.acceptObservation(fence.snapshot(), emptyList<Any>())
            }
            val port = EngineLocalDetailsPort(engine)
            compose.setContent {
                ChunksTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        LocalDetailsOperations(hash, current = true, stale = false, engine = { port })
                    }
                }
            }
            compose.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_FILES).performScrollTo().performClick()
            compose.onNodeWithText("Review RAR archive volumes…").performScrollTo().performClick()
            val namesOnly = "All listed volumes report verified pieces; durable disk bytes, the final volume and archive integrity are unknown."
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText(namesOnly)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(namesOnly).assertExists()
            compose.onNodeWithText("Filename candidates and native hash-verified piece counts.", substring = true).assertExists()
            val inspect = "Inspect this group’s volume headers"
            compose.onNodeWithText(inspect).performScrollTo().performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasText("Volume headers could not be confirmed from the verified files."))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Next group").performScrollTo().performClick()
            compose.onNodeWithText("Missing volume number 2 (1 gap in listed numbering).").assertExists()
            compose.onNodeWithText(namesOnly).assertDoesNotExist()
            compose.onNodeWithText(inspect).assertDoesNotExist()
            compose.onNodeWithText("Next group").performScrollTo().performClick()
            compose.onNodeWithText(inspect).performScrollTo().performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasText("Volume headers agree on order and a final volume. Archive identity and integrity are unknown."))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(LocalTransferState.SEEDING, engine.status(hash)?.state)
            contents.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        } finally {
            engine.close()
            root.deleteRecursively()
        }
    }
}
