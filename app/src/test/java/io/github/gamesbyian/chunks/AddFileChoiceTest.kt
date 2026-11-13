package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
class AddFileChoiceTest {
    @get:Rule val rule = createComposeRule()
    private val files = listOf(DeclaredAddFile(0, "Pack/e01.mkv", 700_000_000), DeclaredAddFile(1, "Pack/extras/sample.mkv", 20_000_000))

    @Test fun everyFileStartsSelectedAndUntickingOnlyMarksItSkipped() {
        var skipped by mutableStateOf(emptySet<Int>())
        var open by mutableStateOf(false)
        rule.setContent { ChunksTheme { AddFileChoice(files, skipped, open, enabled = true,
            onExpanded = { open = it }, onSkippedChange = { skipped = it }) } }
        rule.onNodeWithText("Files to download (2 of 2", substring = true).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("sample.mkv").assertExists()
        rule.onNodeWithText("e01.mkv").assertIsOn()
        rule.onNodeWithText("sample.mkv").performClick()
        rule.onNodeWithText("sample.mkv").assertIsOff()
        assertEquals(setOf(1), skipped)
        rule.onNodeWithTag(ChunksSemantics.ADD_FILE_CHOICE).assertExists()
        rule.onNodeWithText("Files to download (1 of 2", substring = true).assertExists()
    }

    @Test fun aHugePackPointsToDetailsInsteadOfAnUnboundedList() {
        val many = (0 until AddFileChoices.MAX_LISTED + 1).map { DeclaredAddFile(it, "Pack/f$it.bin", 1) }
        rule.setContent { ChunksTheme { AddFileChoice(many, emptySet(), expanded = true, enabled = true, onExpanded = {}, onSkippedChange = {}) } }
        rule.onNodeWithText("choose files in its Details", substring = true).assertExists()
        rule.onNodeWithText("f0.bin").assertDoesNotExist()
    }

    @Test fun skippingEveryFileIsFlagged() {
        rule.setContent { ChunksTheme { AddFileChoice(files, setOf(0, 1), expanded = false, enabled = true,
            onExpanded = {}, onSkippedChange = {}) } }
        rule.onNodeWithText("Choose at least one file to download.").assertExists()
    }
}
