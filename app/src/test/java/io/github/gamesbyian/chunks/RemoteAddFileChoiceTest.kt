package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioral + render evidence: a remote .torrent Add offers the same
 * "Files to download" review as Local (no longer "applies only on this
 * device"), and the single Add action cannot be used with every file unticked.
 * Review only: the endpoint is unroutable and no mutation may start.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class RemoteAddFileChoiceTest {
    @get:Rule val rule = createComposeRule()

    private fun twoFileTorrent(root: String = "Show S01"): ByteArray {
        fun b(text: String) = "${text.toByteArray().size}:$text"
        fun file(name: String, length: Long) = "d" + b("length") + "i${length}e" + b("path") + "l" + b(name) + "ee"
        val total = 40_000L + 25_000L
        val pieces = ((total + 16_383) / 16_384).toInt()
        val info = "d" + b("files") + "l" + file("Episode 01.mkv", 40_000) + file("Episode 02.mkv", 25_000) + "e" +
            b("name") + b(root) + b("piece length") + "i16384e" + "6:pieces" + "${pieces * 20}:"
        return ("d" + b("announce") + b("https://tracker.example/announce") + b("info")).toByteArray() +
            info.toByteArray() + ByteArray(pieces * 20) { 7 } + "ee".toByteArray()
    }

    @Test fun remoteTorrentAddReviewsFilesAndRefusesAnEmptySelection() {
        val bytes = twoFileTorrent()
        val session = RemoteAddSession(null).apply {
            selectedTorrent = TorrentMetainfoReader.preview(bytes)
            selectedBytes = bytes
            useFile = true
            textAtFileSelection = ""
        }
        rule.setContent {
            ChunksTheme { Surface {
                VerifiedAddScreen(RTorrentRemote(RemoteCredentials("http://127.0.0.1:9/RPC2", "", "")),
                    "", "/srv/downloads", onMagnet = {}, onDestination = {}, onBack = {}, onComplete = {},
                    onMutationStarted = { throw AssertionError("review must not start a mutation") },
                    onMutationSettled = {}, session = session, environmentLabel = "Wood")
            } }
        }
        val list = rule.onNode(hasScrollToIndexAction())
        rule.waitForIdle()
        list.performScrollToNode(hasTestTag(ChunksSemantics.ADD_FILE_CHOICE))
        rule.onNodeWithText("Files to download (2 of 2", substring = true).assertExists()
        assertTrue("A remote target is no longer told choices apply only on this device",
            rule.onAllNodesWithText("File choices apply when adding on this device").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag(ChunksSemantics.ADD_FILE_CHOICE).performClick()
        list.performScrollToNode(hasText("Episode 02.mkv"))
        rule.onRoot().captureRoboImage("build/chunks-visual/remote-add-file-choice.png")
        rule.onNodeWithText("Episode 02.mkv").performClick()
        rule.onNodeWithText("Files to download (1 of 2", substring = true).assertExists()
        assertEquals(setOf(1), session.skippedFiles)
        rule.onNodeWithText("Episode 01.mkv").performClick()
        rule.onNodeWithText("Choose at least one file to download.").assertExists()
        list.performScrollToNode(hasTestTag(ChunksSemantics.ADD_SUBMIT))
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertIsNotEnabled()
        rule.onRoot().captureRoboImage("build/chunks-visual/remote-add-file-choice-none.png")
        // Another reviewed .torrent never inherits these skips.
        val other = twoFileTorrent("Show S02")
        rule.runOnIdle {
            session.selectedTorrent = TorrentMetainfoReader.preview(other)
            session.selectedBytes = other
        }
        list.performScrollToNode(hasTestTag(ChunksSemantics.ADD_FILE_CHOICE))
        rule.onNodeWithText("Files to download (2 of 2", substring = true).assertExists()
    }
}
