package io.github.gamesbyian.chunks

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Connections reuses the same picker without submitting an Add or saving implicitly. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-xxhdpi")
class RemoteFolderPickerDefaultModeTest {
    @get:Rule val rule = createComposeRule()
    private val fakeRemote = RTorrentRemote(RemoteCredentials(
        "http://127.0.0.1:1/RPC2", "", ""))

    @Test fun choosingManualFolderCommitsOnlyThroughTheSettingsCallback() {
        var commits = 0
        var selected: String? = null
        rule.setContent {
            ChunksTheme {
                RemoteFolderPicker(
                    remote = fakeRemote,
                    connectionName = "Wood",
                    initialFolder = "/srv/old",
                    onChoose = { chosen -> commits++; selected = chosen },
                    onDismiss = {},
                    savedDefaultMode = true
                )
            }
        }
        rule.onNodeWithText("Enter path").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("/srv/new")
        rule.waitForIdle()
        assertEquals(0, commits)
        rule.onNodeWithText("Set as server default").performClick()
        assertEquals(1, commits)
        assertEquals("/srv/new", selected)
    }

    @Test fun cancellingSettingsPickerHasNoDefaultMutation() {
        var commits = 0
        var cancelled = 0
        rule.setContent {
            ChunksTheme {
                RemoteFolderPicker(
                    remote = fakeRemote,
                    connectionName = "Wood",
                    initialFolder = "/srv/old",
                    onChoose = { commits++ },
                    onDismiss = { cancelled++ },
                    savedDefaultMode = true
                )
            }
        }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, commits)
        assertEquals(1, cancelled)
    }
}
