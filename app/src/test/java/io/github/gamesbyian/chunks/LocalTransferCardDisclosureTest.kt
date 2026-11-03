package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Presentational disclosure: the existing real mutation handlers are supplied but never tapped. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class LocalTransferCardDisclosureTest {
    @get:Rule val rule = createComposeRule()

    @Test fun compactPhoneCardKeepsPrimaryCommandsAndHidesOnlyDiagnostics() {
        var mutations = 0
        val transfer = LocalTransferStatus(
            infoHash = "a".repeat(40),
            name = "Public.Sample.mkv",
            state = LocalTransferState.DOWNLOADING,
            stopped = false,
            progressPpm = 500_000,
            wantedBytes = 1000,
            doneBytes = 500,
            payloadDownloadedBytes = 600,
            payloadUploadedBytes = 20,
            peers = 2,
            savePath = "/local",
            error = null
        )
        rule.setContent {
            ChunksTheme { Surface {
                LocalTransferCard(
                    transfer = transfer, busy = false,
                    onToggle = { mutations++ }, onRemove = { mutations++ },
                    files = emptyList(), onShowFiles = { mutations++ }
                )
            } }
        }
        rule.onNodeWithText("Stop").assertExists()
        rule.onNodeWithText("Remove…").assertExists()
        rule.onNodeWithText("Files").assertExists()
        rule.onNodeWithContentDescription("Stop Public.Sample.mkv on this phone").assertExists()
        rule.onNodeWithContentDescription(
            "Remove transfer Public.Sample.mkv from this phone").assertExists()
        rule.onNodeWithContentDescription(
            "Show files for Public.Sample.mkv on this phone").assertExists()
        rule.onNodeWithText("Network payload counter", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription(
            "More details for Public.Sample.mkv on this phone").performClick()
        rule.onNodeWithText("Network payload counter", substring = true).assertExists()
        rule.onNodeWithContentDescription(
            "Hide diagnostics for Public.Sample.mkv on this phone").performClick()
        rule.onNodeWithText("Network payload counter", substring = true).assertDoesNotExist()
        assertEquals("Expanding must not submit Start, Stop, Remove, or Files", 0, mutations)
    }

    @Test fun stoppedPhoneTransferAnnouncesStartAndExactLocalTarget() {
        val transfer = LocalTransferStatus(
            infoHash = "b".repeat(40),
            name = "Other.Sample.mkv",
            state = LocalTransferState.UNKNOWN,
            stopped = true,
            progressPpm = 100_000,
            wantedBytes = 1000,
            doneBytes = 100,
            payloadDownloadedBytes = 100,
            payloadUploadedBytes = 0,
            peers = 0,
            savePath = "/local",
            error = null
        )
        var writes = 0
        rule.setContent {
            ChunksTheme { Surface {
                LocalTransferCard(transfer, busy = false,
                    onToggle = { writes++ }, onRemove = { writes++ })
            } }
        }
        rule.onNodeWithContentDescription("Start Other.Sample.mkv on this phone").assertExists()
        rule.onNodeWithContentDescription(
            "Remove transfer Other.Sample.mkv from this phone").assertExists()
        assertEquals("Reading exact accessible action labels cannot change transfer state", 0, writes)
    }
}
