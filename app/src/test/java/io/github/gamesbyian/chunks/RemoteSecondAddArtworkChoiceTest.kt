package io.github.gamesbyian.chunks

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.gamesbyian.chunks.media.ChunksMedia
import io.github.gamesbyian.chunks.media.MediaPrivacySettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Installed real-magnet journeys (PR #1772 run 43763235677, PR #1773 run
 * 43767245237) lost the opt-out of "Automatically identify and fetch artwork &
 * metadata" on a SECOND remote Add after a finished first Add: the toggle never
 * read Off after one click. The user's choice for the new source must stick.
 * JVM evidence against a loopback rTorrent double; no peers or pieces.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class RemoteSecondAddArtworkChoiceTest {
    @get:Rule val rule = createComposeRule()
    private val server = FakeRTorrent()

    @Before fun launch() {
        // ChunksMedia is process-wide: an earlier class in the same JVM may have
        // turned artwork display off, which hides this per-Add choice entirely
        // (CI foundation 43770779896). Start from the shipped defaults.
        ChunksMedia.get(androidx.test.core.app.ApplicationProvider.getApplicationContext())
            .updateSettings(MediaPrivacySettings())
        rule.setContent {
            ChunksTheme {
                ChunksApp(sharedMagnet = null, incomingTorrent = null,
                    onIncomingMagnetConsumed = {}, onIncomingTorrentConsumed = {})
            }
        }
    }

    @After fun stop() = server.close()

    private fun list(): SemanticsNodeInteraction = rule.onNode(hasScrollToIndexAction())
    private fun visible(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitForText(text: String, timeout: Long = 30_000) = rule.waitUntil(timeout) { visible(text) }

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

    private fun magnet(hashChar: Char, name: String) = "magnet:?xt=urn:btih:" + hashChar.toString().repeat(40) + "&dn=" + name

    private fun enterSource(link: String) {
        list().performScrollToNode(hasText("Magnet or torrent link"))
        rule.onNodeWithText("Magnet or torrent link").performTextReplacement(link)
        rule.waitForIdle()
    }

    private fun artworkChoice(): ToggleableState? {
        list().performScrollToNode(hasTestTag(ChunksSemantics.ADD_ARTWORK_CHOICE))
        return rule.onNodeWithTag(ChunksSemantics.ADD_ARTWORK_CHOICE).fetchSemanticsNode()
            .config[SemanticsProperties.ToggleableState]
    }

    private fun openArtworkDisclosure() {
        // The choice is offered only after local settings load off the main thread.
        rule.waitUntil(15_000) {
            runCatching { list().performScrollToNode(hasText("Artwork & metadata")) }.isSuccess
        }
        if (rule.onAllNodesWithText("Automatically identify and fetch artwork & metadata")
                .fetchSemanticsNodes().isEmpty())
            rule.onNodeWithText("Artwork & metadata").performClick()
        rule.waitUntil(10_000) { runCatching { artworkChoice() != null }.getOrDefault(false) }
    }

    private fun submit() {
        list().performScrollToNode(hasTestTag(ChunksSemantics.ADD_SUBMIT))
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
    }

    @Test fun secondAddKeepsItsArtworkOptOut() = secondAddKeepsItsArtworkOptOut(firstOutcome = "Verified: added")

    private fun secondAddKeepsItsArtworkOptOut(firstOutcome: String) {
        connect()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_REMOTE).performScrollTo().performClick()
        rule.waitForIdle()

        // First Add, artwork left at its default, verified.
        enterSource(magnet('1', "first-episode"))
        submit()
        waitForText(firstOutcome)

        // Second Add of a new source: a single opt-out must hold.
        listOf("Start another Add", "Acknowledge session and start another Add",
            "Acknowledge registered job; Start unverified").firstOrNull { label ->
            runCatching { list().performScrollToNode(hasText(label)) }
            visible(label)
        }?.let { label -> rule.onNodeWithText(label).performScrollTo().performClick() }
        rule.waitForIdle()
        enterSource(magnet('2', "second-episode"))
        openArtworkDisclosure()
        assertEquals(ToggleableState.On, artworkChoice())
        rule.onNodeWithTag(ChunksSemantics.ADD_ARTWORK_CHOICE).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals("One tap opts this source out", ToggleableState.Off, artworkChoice())
        // A finished first Add's late verification effects must not re-check it.
        rule.mainClock.advanceTimeBy(10_000)
        rule.waitForIdle()
        assertEquals("The opt-out stays chosen", ToggleableState.Off, artworkChoice())
    }
}
