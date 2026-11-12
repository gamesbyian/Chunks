package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Owner report (12 Nov): a first Android Open With `.torrent` could be added; a second Open
 * With reached Add with no usable Add action. Drives the real MainActivity, the real
 * `ACTION_VIEW` → `onNewIntent()` intake and the real remote Add adapter against a stateful
 * loopback rTorrent double. JVM evidence only: no peers or pieces.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class RepeatedAndroidIntakeTest {
    @get:Rule val rule = createComposeRule()
    private val server = FakeRTorrent()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    /** Exactly what MainActivity.accept() hands ChunksApp after onNewIntent(ACTION_VIEW). */
    private val incoming = mutableStateOf<Uri?>(null)

    @org.junit.Before fun launch() {
        rule.setContent {
            ChunksTheme {
                ChunksApp(sharedMagnet = null, incomingTorrent = incoming.value,
                    onIncomingMagnetConsumed = {}, onIncomingTorrentConsumed = { incoming.value = null })
            }
        }
    }

    @After fun stop() = server.close()

    private fun b(text: String) = "${text.toByteArray().size}:$text"
    private fun torrent(name: String) = ("d8:announce" + b("https://tracker.example/announce") +
        "4:infod6:lengthi5e4:name" + b(name) + "12:piece lengthi16384e6:pieces20:01234567890123456789ee")
        .toByteArray()

    private fun list(): SemanticsNodeInteraction = rule.onNode(hasScrollToIndexAction())
    private fun waitForText(text: String, timeout: Long = 30_000) = rule.waitUntil(timeout) {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun connect() {
        rule.onNodeWithContentDescription("Connections").performClick()
        list().performScrollToNode(hasText("Seedbox hostname, ruTorrent URL or RPC address"))
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").performTextInput(server.endpoint)
        list().performScrollToNode(hasText("Connect to rTorrent"))
        rule.onNodeWithText("Connect to rTorrent").performClick()
        waitForText("Continue to Connections")
        rule.onNodeWithText("Continue to Connections").performClick()
        rule.waitForIdle()
    }

    /** A second Open With while Chunks is already showing Add. */
    private fun openWith(name: String): String {
        val bytes = torrent(name)
        val uri = Uri.parse("content://chunks.test.documents/$name.torrent")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        // The same VIEW intent MainActivity receives is what selects this document.
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/x-bittorrent")
        val accepted = requireNotNull(SharedTorrentDocument.fromIntent(intent)) { "Not an accepted Open With" }
        rule.runOnIdle { incoming.value = accepted }
        rule.waitForIdle()
        return TorrentMetainfoReader.preview(bytes).infoHashV1.lowercase()
    }

    private fun submitEnabled() {
        list().performScrollToNode(hasTestTag(ChunksSemantics.ADD_SUBMIT))
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertIsEnabled()
    }

    private fun useNewSourceIfOffered() {
        if (rule.onAllNodesWithText("Use new source").fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithText("Use new source").performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test fun secondOpenWithAfterAVerifiedAddGetsAUsableAddForTheNewTorrent() {
        connect()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_REMOTE).performScrollTo().performClick()
        rule.waitForIdle()

        val first = openWith("first-torrent.mkv")
        waitForText("first-torrent.mkv")
        submitEnabled()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
        waitForText("Verified: added")
        assertTrue(server.jobs.containsKey(first))

        val second = openWith("second-torrent.mkv")
        useNewSourceIfOffered()
        waitForText("second-torrent.mkv")
        // The defect: the finished attempt hid the new source and the Add action.
        submitEnabled()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
        rule.waitUntil(30_000) { server.jobs.containsKey(second) }
        waitForText("Verified: added")
        assertEquals("Exactly one load per explicit Add, never a replay of the first",
            2, server.loads().size)
        assertEquals(setOf(first, second), server.jobs.keys)
    }

    @Test fun anUnsubmittedDraftIsReplacedOnlyByExplicitChoiceAndTheAddStaysUsable() {
        connect()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_REMOTE).performScrollTo().performClick()
        rule.waitForIdle()
        openWith("draft-a.mkv")
        waitForText("draft-a.mkv")
        val replacement = openWith("draft-b.mkv")
        // An occupied, never-submitted draft is not silently replaced.
        waitForText("Use new source")
        rule.onNodeWithText("Use new source").performScrollTo().performClick()
        waitForText("draft-b.mkv")
        submitEnabled()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
        rule.waitUntil(30_000) { server.jobs.containsKey(replacement) }
        assertEquals("Only the explicitly chosen replacement is submitted", setOf(replacement), server.jobs.keys)
    }
}
