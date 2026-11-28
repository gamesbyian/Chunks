package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h900dp-xhdpi")
class SelectedFileSearchSectionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun explicitSearchPagesMetadataAndDropsResultsOnPrivacyChange() {
        val exact = UnifiedTransferKey.of(EnvironmentId("test-local"), "a".repeat(40))
        val unavailable = UnifiedTransferKey.of(EnvironmentId("test-remote"), "b".repeat(40))
        var reads = 0
        var opened: UnifiedTransferKey? = null
        val permitted = mutableStateOf(true)
        val target = SelectedFileSearch.Target(exact, "Device", "Test transfer", false,
            read = {
                reads++
                listOf(ClientFile(14, "folder/target.mkv", 42, null, ClientFilePriority.NORMAL))
            }, authorized = { permitted.value })
        val unavailableTarget = SelectedFileSearch.Target(unavailable, "Other client", "Unavailable transfer",
            null, read = null, authorized = { false })
        rule.setContent {
            ChunksTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SelectedFileSearchSection(
                        targets = if (permitted.value) listOf(target, unavailableTarget) else emptyList(),
                        viewWitness = permitted.value,
                        viewCurrent = { permitted.value },
                        onOpen = { opened = it }
                    )
                }
            }
        }
        rule.onNodeWithText("Find files across selected transfers").performClick()
        rule.onNodeWithTag("selected-file-search-query").performTextInput("target")
        rule.waitForIdle()
        assertEquals("Typing must not contact a client", 0, reads)
        rule.onNodeWithText("Search selected files").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("folder/target.mkv").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, reads)
        rule.onNodeWithText("Some selected transfers were not fully searched.").assertExists()
        rule.onNodeWithText("Other client: Unavailable transfer: File list unavailable").assertExists()
        rule.onNodeWithText("File index 14 · 42 bytes · completed: unknown").assertExists()
        rule.onNodeWithText("Open transfer details").performScrollTo().performClick()
        assertEquals(exact, opened)
        rule.runOnIdle { permitted.value = false }
        rule.onNodeWithText("folder/target.mkv").assertDoesNotExist()
        rule.onNodeWithText("Open transfer details").assertDoesNotExist()
        rule.runOnIdle { permitted.value = true }
        rule.onNodeWithText("Find files across selected transfers").assertExists()
        rule.onNodeWithText("folder/target.mkv").assertDoesNotExist()
        assertEquals(1, reads)
    }
}
