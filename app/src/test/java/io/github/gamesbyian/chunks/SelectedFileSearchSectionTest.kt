package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
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

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h700dp-xhdpi")
    fun narrowLargeTextFullPageAndCoverageRemainReachable() {
        val targets = (0 until 24).map { i ->
            val exact = UnifiedTransferKey.of(EnvironmentId("test-$i"), "%040x".format(i + 1))
            SelectedFileSearch.Target(exact, "Client $i", "Transfer $i", false,
                read = if (i == 0) ({
                    (0 until 21).map { n ->
                        ClientFile(n, "folder/target-$n.mkv", 42, null, ClientFilePriority.NORMAL)
                    }
                }) else null, authorized = { i == 0 })
        }
        var opened: UnifiedTransferKey? = null
        rule.setContent {
            val original = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(original.density, 2f)) {
                ChunksTheme {
                    Surface {
                        LazyColumn {
                            item {
                                SelectedFileSearchSection(targets, "narrow", { true }, { opened = it })
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("Find files across selected transfers").performClick()
        rule.onNodeWithTag("selected-file-search-query").performScrollTo().performTextInput("target")
        rule.onNodeWithText("Search selected files").performScrollTo().performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Page 1 of 2").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Client 23: Transfer 23: File list unavailable").performScrollTo().assertExists()
        rule.onNodeWithText("Next files").performScrollTo().performClick()
        rule.onNodeWithText("Page 2 of 2").performScrollTo().assertExists()
        rule.onNodeWithText("Open transfer details").performScrollTo().performClick()
        assertEquals(targets.first().key, opened)
        rule.onRoot().captureRoboImage("build/chunks-visual/selected-file-search-page-320x700-200pct.png")
        rule.onNodeWithText("Previous files").performScrollTo().performClick()
        rule.onNodeWithText("Page 1 of 2").performScrollTo().assertExists()
    }
}
