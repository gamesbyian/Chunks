package io.github.gamesbyian.chunks.snapshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class LocalSnapshotBatchStoppedRestoreReviewTest {
    @get:Rule val rule = createComposeRule()

    private fun source(hash: String, name: String) =
        SnapshotVerifiedTorrentDocumentV1.Prepared(
            hash, name, "chunks-" + hash.take(12) + ".torrent", byteArrayOf(1))

    @Test fun fullBatchRequiresOneExplicitEnabledDestinationConfirmation() {
        val approved = mutableStateOf(false)
        val confirms = mutableIntStateOf(0)
        val draft = LocalSnapshotBatchStoppedRestoreDraft(
            originals = listOf(source("a".repeat(40), "One"),
                source("b".repeat(40), "Two")),
            archiveFingerprint = "c".repeat(64),
            frozenHashes = emptyList(), frozenGeneration = 1L)
        rule.setContent {
            MaterialTheme {
                LocalSnapshotBatchStoppedRestoreReview(
                    draft, "/verified/new-place", eligible = approved.value,
                    onConfirm = { confirms.intValue++ },
                    onCancel = { })
            }
        }
        rule.onNodeWithText("Confirm 2 stopped restores").assertIsNotEnabled()
        rule.onNodeWithText("New local destination: /verified/new-place").assertExists()
        rule.onNodeWithText("Confirm 2 stopped snapshot restores").assertExists()
        rule.runOnIdle { approved.value = true }
        rule.onNodeWithText("Confirm 2 stopped restores").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, confirms.intValue) }
    }
}
