package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
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

    @Test fun reusesAnExistingCollectionByExplicitOneTapWithoutNetworkSideEffects() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferCollectionStore(context)
        val scope = "https://test.invalid/RPC2\u0000quick-collection-choice-test"
        val hash = "E".repeat(40)
        val knownHash = "F".repeat(40)
        val transfer = RemoteTransfer(hash, "Untouched.torrent.name", 100L, 70L, false, "/original/path")
        try {
            store.add(scope, knownHash, "Star Trek")
            store.remove(scope, hash, "Star Trek")
            rule.setContent {
                MaterialTheme {
                    TransferCollectionEditor(transfer, scope, store,
                        availableCollections = listOf("Star Trek", "STAR TREK")) {}
                }
            }
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithText("Add to Star Trek").performClick()
            rule.waitForIdle()
            assertEquals(listOf("Star Trek"), TransferCollectionStore(context).forTransfer(scope, hash))
            assertEquals("Untouched.torrent.name", transfer.name)
            assertEquals("/original/path", transfer.directory)
            rule.onNodeWithText("Remove from Star Trek").performClick()
            assertEquals(emptyList<String>(), store.forTransfer(scope, hash))
        } finally {
            store.remove(scope, hash, "Star Trek")
            store.remove(scope, knownHash, "Star Trek")
        }
    }

    @Test fun phoneEngineUsesAnIndependentCollectionNamespace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferCollectionStore(context)
        val infoHash = "D".repeat(40)
        val localScope = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        val remoteScope = "https://test.invalid/RPC2\u0000local-separation"
        try {
            store.remove(localScope, infoHash, "On the phone")
            rule.setContent {
                MaterialTheme {
                    TransferCollectionEditor(infoHash, "Local download", localScope, store) {}
                }
            }
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithTag("collection-name-input").performTextInput("On the phone")
            rule.onNodeWithText("Add to collection").performClick()
            rule.waitForIdle()
            assertEquals(listOf("On the phone"), TransferCollectionStore(context).forTransfer(localScope, infoHash))
            assertEquals(emptyList<String>(), store.forTransfer(remoteScope, infoHash))
            rule.onNodeWithText("Remove from On the phone").performClick()
            assertEquals(emptyList<String>(), store.forTransfer(localScope, infoHash))
        } finally {
            store.remove(localScope, infoHash, "On the phone")
        }
    }

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
