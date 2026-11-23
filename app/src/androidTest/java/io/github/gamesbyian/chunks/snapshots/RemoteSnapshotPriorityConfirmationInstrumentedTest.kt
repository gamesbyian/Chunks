package io.github.gamesbyian.chunks.snapshots

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.RemoteFile
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Installed Compose consent gate only: no rTorrent session or network writes. */
@RunWith(AndroidJUnit4::class)
class RemoteSnapshotPriorityConfirmationInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "a".repeat(40)
    private val transfer = RemoteTransfer(hash, "Movie", 50, 20, false, "/verified")

    private fun entry(level: SnapshotFilePriorities.Level) =
        SnapshotRestorePreviewV1.Entry(
            hash, "Movie", SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT,
            false, "/untrusted",
            fileMappings = listOf(SnapshotFilePriorities.Mapping(
                SnapshotFilePriorities.File(0, "Movie/one.mkv", 30), level))
        )

    @Test fun readOnlyReviewRequiresSeparateExplicitApplyTapAndCancelNeverCallsWriter() {
        val reviewed = entry(SnapshotFilePriorities.Level.HIGH)
        val comparison = requireNotNull(SnapshotRemotePriorityComparison.plan(
            reviewed, transfer, listOf(RemoteFile("one.mkv", 30, 1))))
        val showing = mutableStateOf(true)
        var approvals = 0
        var cancellations = 0
        rule.setContent {
            if (showing.value) RemoteSnapshotPriorityConfirmation(
                entry = reviewed, review = comparison,
                onConfirm = { approvals++; showing.value = false },
                onDismiss = { cancellations++; showing.value = false }
            )
        }
        rule.onNodeWithText("Apply saved rTorrent file priorities?").assertIsDisplayed()
        rule.onNodeWithText("Apply 1 priorities").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, approvals) }
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Apply 1 priorities").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, approvals); assertEquals(1, cancellations) }
        rule.runOnIdle { showing.value = true }
        rule.onNodeWithText("Apply 1 priorities").performClick()
        rule.runOnIdle { assertEquals(1, approvals); assertEquals(1, cancellations) }
    }

    @Test fun unsupportedSavedLowNeverEnablesPriorityWriteConfirmation() {
        val reviewed = entry(SnapshotFilePriorities.Level.LOW)
        val comparison = requireNotNull(SnapshotRemotePriorityComparison.plan(
            reviewed, transfer, listOf(RemoteFile("one.mkv", 30, 1))))
        var approvals = 0
        rule.setContent {
            RemoteSnapshotPriorityConfirmation(reviewed, comparison,
                onConfirm = { approvals++ }, onDismiss = {})
        }
        rule.onNodeWithText("Apply 1 priorities").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, approvals) }
    }
}
