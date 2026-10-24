package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferAliasEditorInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun suggestionsAreOnlyDraftsAndResetRestoresOriginalQueueName() {
        val torrentName = "Celebrity.Jeopardy.S04E02.720p.HEVC.x265-MeGusta.mkv"
        var alias by mutableStateOf<String?>(null)
        val committed = mutableListOf<String?>()
        rule.setContent {
            MaterialTheme {
                TransferAliasEditor(
                    originalName = torrentName,
                    hash = "A".repeat(40),
                    alias = alias,
                    onSave = {
                        committed.add(it)
                        alias = it
                        true
                    }
                )
            }
        }

        rule.onNodeWithText("Change list name").performClick()
        rule.onNodeWithText("Celebrity Jeopardy S04E02").performClick()
        rule.runOnIdle { assertEquals(0, committed.size) } // recommendation never auto-applies
        rule.onNodeWithText("Save name").performClick()
        rule.runOnIdle {
            assertEquals(listOf("Celebrity Jeopardy S04E02"), committed)
            assertEquals("Celebrity Jeopardy S04E02", alias)
        }
        rule.onNodeWithText("Edit list name").performClick()
        rule.onNodeWithText("Use original torrent name").performClick()
        rule.runOnIdle {
            assertEquals(listOf("Celebrity Jeopardy S04E02", null), committed)
            assertEquals(null, alias)
        }
        rule.onNodeWithText("Change list name").assertExists()
    }
}
