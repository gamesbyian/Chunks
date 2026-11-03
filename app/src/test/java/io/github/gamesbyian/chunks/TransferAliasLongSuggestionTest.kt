package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** A fully spoken suggestion is still only an editable draft, never a rename. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi", fontScale = 2.0f)
class TransferAliasLongSuggestionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun longEpisodeSuggestionIsNotTruncatedOrSilentlyCommitted() {
        val original = "Chronicles.Of.The.Emerald.Observatory.With.An.Exceedingly.Long." +
            "Segment.Of.Visible.Words.S01E07.1080p.mkv"
        val expected = "Chronicles Of The Emerald Observatory With An Exceedingly " +
            "Long Segment Of Visible Words S01E07"
        val calls = mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    TransferAliasEditor(originalName = original, hash = "c".repeat(40),
                        alias = null, onSave = { calls.intValue++; true })
                }
            }
        }
        rule.onNodeWithText("Change list name").performClick()
        rule.onNodeWithText("Show name suggestions", substring = true).performClick()
        rule.onNodeWithContentDescription("Use list-name suggestion: $expected")
            .assertExists()
        rule.onNodeWithText(expected, useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Use list-name suggestion: $expected")
            .performClick()
        rule.runOnIdle {
            assertEquals("Selecting a suggested name cannot commit a rename", 0, calls.intValue)
        }
    }
}
