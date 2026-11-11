package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioral: the one-click remote Add still warns, before its single submission action, that a
 * trackerless source can only find peers through DHT or web seeds (the warning lived on the
 * removed confirmation card). Literal copy is the requirement here: it is a user-facing warning.
 * Review only; the endpoint is unroutable and no mutation may start.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class RemoteAddTrackerlessWarningTest {
    @get:Rule val rule = createComposeRule()

    private fun show(magnet: String) {
        val source = mutableStateOf(magnet)
        rule.setContent {
            ChunksTheme { Surface {
                VerifiedAddScreen(RTorrentRemote(RemoteCredentials("http://127.0.0.1:9/RPC2", "", "")),
                    source.value, "/srv",
                    onMagnet = { source.value = it }, onDestination = {}, onBack = {}, onComplete = {},
                    onMutationStarted = { throw AssertionError("review must not start a mutation") },
                    onMutationSettled = {})
            } }
        }
        rule.waitForIdle()
        runCatching {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Infohash:", substring = true))
        }
    }

    @Test fun trackerlessMagnetWarnsBeforeTheSingleAddAction() {
        show("magnet:?xt=urn:btih:" + "a".repeat(40))
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Infohash:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(trackerlessWarning(false)).assertExists()
    }

    @Test fun magnetWithATrackerShowsNoTrackerlessWarning() {
        show("magnet:?xt=urn:btih:" + "b".repeat(40) + "&tr=https%3A%2F%2Ftracker.example%2Fannounce")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Infohash:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithText(trackerlessWarning(false)).fetchSemanticsNodes().isEmpty())
    }
}
