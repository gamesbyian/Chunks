package io.github.gamesbyian.chunks

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A server without _getdir always retains a usable manual destination. The
 * picker has no permission to submit Add merely by opening or choosing a path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-xxhdpi")
class RemoteFolderPickerTest {
    @get:Rule val rule = createComposeRule()
    private val unsupported = RTorrentRemote(RemoteCredentials(
        "http://127.0.0.1:1/RPC2", "", ""))

    @Test fun manualFallbackChoosesAbsoluteRemoteFolderWithoutAnotherAdd() {
        var chosen: String? = null
        var cancelled = 0
        rule.setContent {
            ChunksTheme {
                RemoteFolderPicker(unsupported, "Wood", null,
                    onChoose = { chosen = it }, onDismiss = { cancelled++ })
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Enter path").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Enter path").performClick()
        rule.onNode(hasSetTextAction())
            .performTextReplacement("/srv/wood/TV")
        rule.onNodeWithText("Use this path").performClick()
        assertEquals("/srv/wood/TV", chosen)
        assertEquals(0, cancelled)
    }

    @Test fun cancelDoesNotCommitAnUnreviewedPlace() {
        var chosen: String? = null
        var cancelled = 0
        rule.setContent {
            ChunksTheme {
                RemoteFolderPicker(unsupported, "Wood", "/srv/wood",
                    onChoose = { chosen = it }, onDismiss = { cancelled++ })
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Cancel").performClick()
        assertNull(chosen)
        assertEquals(1, cancelled)
    }
}
