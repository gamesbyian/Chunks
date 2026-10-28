package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalFileSelectionBatchInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val files = listOf(
        LocalTransferFile(0, "Season/Episode1.mkv", 1_024, 0, true),
        LocalTransferFile(1, "Season/Episode2.mkv", 1_024, 0, false)
    )

    @Test fun pageLevelChangeRequiresSeparateReviewedConfirmation() {
        var applied: Pair<List<LocalTransferFile>, Boolean>? = null
        rule.setContent {
            MaterialTheme {
                // A real parent layout is essential: unpositioned root siblings
                // overlap, and taps can hit the file rows instead of the buttons.
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    LocalFileSelection(files, busy = false, transferKey = "abc",
                        onFileWanted = { _, _ -> },
                        onBatchFileWanted = { chosen, wanted -> applied = chosen to wanted })
                }
            }
        }
        rule.onNodeWithText("Skip page").performClick()
        assertNull("Opening review must never write native priorities", applied)
        rule.onNodeWithText("Confirm skip page").performClick()
        assertNotNull(applied)
        assertEquals(false, applied?.second)
        assertEquals(files, applied?.first)
    }

    @Test fun pageLevelChangeMayBeCancelledWithoutAnyAction() {
        var calls = 0
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    LocalFileSelection(files, busy = false, transferKey = "abc",
                        onFileWanted = { _, _ -> },
                        onBatchFileWanted = { _, _ -> calls++ })
                }
            }
        }
        rule.onNodeWithText("Select page").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Confirm select page").assertDoesNotExist()
        assertEquals(0, calls)
    }

    @Test fun nativeBusyStateDisablesBatchSelection() {
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    LocalFileSelection(files, busy = true, transferKey = "abc",
                        onFileWanted = { _, _ -> },
                        onBatchFileWanted = { _, _ -> })
                }
            }
        }
        rule.onNodeWithText("Skip page").assertIsNotEnabled()
        rule.onNodeWithText("Select page").assertIsNotEnabled()
    }
}
