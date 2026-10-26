package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferCollectionEditorInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeEditAndRemovalOnlyChangeLocalMembershipMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferCollectionStore(context)
        val hash = "C".repeat(40)
        val scope = "https://test.invalid/RPC2\u0000compose-collections-test"
        val transfer = RemoteTransfer(hash, "Original.Celebrity.Jeopardy.mkv",
            200L, 100L, false, "/unchanged/source")
        try {
            rule.setContent {
                MaterialTheme {
                    TransferCollectionEditor(transfer, scope, store) {}
                }
            }
            rule.onNodeWithText("No manual collections").assertExists()
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithTag("collection-name-input").performTextInput("Celeb Jeopardy")
            rule.onNodeWithText("Add to collection").performClick()
            rule.waitForIdle()
            assertEquals(listOf("Celeb Jeopardy"), store.forTransfer(scope, hash))
            rule.onNodeWithText("Remove from Celeb Jeopardy").performClick()
            rule.waitForIdle()
            assertEquals(emptyList<String>(), store.forTransfer(scope, hash))
            assertEquals("Original.Celebrity.Jeopardy.mkv", transfer.name)
            assertEquals("/unchanged/source", transfer.directory)
        } finally {
            store.remove(scope, hash, "Celeb Jeopardy")
        }
    }
}
