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
class LocalSnapshotBatchRecoveryReviewTest {
    @get:Rule val rule = createComposeRule()

    @Test fun previousGroupRequiresIndependentDestinationReviewAndNeverGrantsAdd() {
        val ready = mutableStateOf(false)
        val checked = mutableIntStateOf(0)
        val draft = LocalSnapshotBatchRecoveryDraft(
            hashes = listOf("a".repeat(40), "b".repeat(40)),
            archiveFingerprint = "c".repeat(64),
            frozenHashes = emptyList(), frozenGeneration = 10L)
        rule.setContent {
            MaterialTheme {
                LocalSnapshotBatchRecoveryReview(
                    draft = draft, selectedPlace = "/previously-reviewed-local",
                    eligible = ready.value,
                    onConfirm = { checked.intValue++ }, onCancel = { })
            }
        }
        rule.onNodeWithText("Check 2 previous restores (read-only)").assertIsNotEnabled()
        rule.onNodeWithText("Folder to check (must match the previous review): /previously-reviewed-local")
            .assertExists()
        rule.onNodeWithText("Check previous stopped snapshot restores").assertExists()
        rule.runOnIdle { ready.value = true }
        rule.onNodeWithText("Check 2 previous restores (read-only)")
            .assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, checked.intValue) }
    }
}
