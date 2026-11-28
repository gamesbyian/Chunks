package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    private fun waitForText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

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
            waitForText("No manual collections")
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithText("Choose existing collection", substring = true).performClick()
            rule.onNodeWithText("Add to Star Trek").performClick()
            waitForText("Membership saved on this device. Sharing is unchanged.")
            assertEquals(listOf("Star Trek"), TransferCollectionStore(context).forTransfer(scope, hash))
            assertEquals("Untouched.torrent.name", transfer.name)
            assertEquals("/original/path", transfer.directory)
            rule.onNodeWithText("Remove from Star Trek").performClick()
            waitForText("No manual collections")
            assertEquals(emptyList<String>(), store.forTransfer(scope, hash))
        } finally {
            store.remove(scope, hash, "Star Trek")
            store.remove(scope, knownHash, "Star Trek")
        }
    }

    @Test fun localEngineUsesAnIndependentCollectionNamespace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferCollectionStore(context)
        val infoHash = "D".repeat(40)
        val localScope = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        val remoteScope = "https://test.invalid/RPC2\u0000local-separation"
        try {
            store.remove(localScope, infoHash, "On the local device")
            rule.setContent {
                MaterialTheme {
                    TransferCollectionEditor(infoHash, "Local download", localScope, store) {}
                }
            }
            waitForText("No manual collections")
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithTag("collection-name-input").performTextInput("On the local device")
            rule.onNodeWithText("Add to collection").performClick()
            waitForText("Membership saved on this device. Sharing is unchanged.")
            assertEquals(listOf("On the local device"), TransferCollectionStore(context).forTransfer(localScope, infoHash))
            assertEquals(emptyList<String>(), store.forTransfer(remoteScope, infoHash))
            rule.onNodeWithText("Remove from On the local device").performClick()
            waitForText("No manual collections")
            assertEquals(emptyList<String>(), store.forTransfer(localScope, infoHash))
        } finally {
            store.remove(localScope, infoHash, "On the local device")
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
            waitForText("No manual collections")
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithTag("collection-name-input").performTextInput("Celeb Jeopardy")
            rule.onNodeWithText("Add to collection").performClick()
            waitForText("Membership saved on this device. Sharing is unchanged.")
            assertEquals(listOf("Celeb Jeopardy"), store.forTransfer(scope, hash))
            rule.onNodeWithText("Remove from Celeb Jeopardy").performClick()
            waitForText("No manual collections")
            assertEquals(emptyList<String>(), store.forTransfer(scope, hash))
            assertEquals("Original.Celebrity.Jeopardy.mkv", transfer.name)
            assertEquals("/unchanged/source", transfer.directory)
        } finally {
            store.remove(scope, hash, "Celeb Jeopardy")
        }
    }

    @Test fun switchingExactScopeWithSameHashDiscardsOldEditorState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferCollectionStore(context)
        val hash = "B".repeat(40)
        val local = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        val remote = "https://test.invalid/RPC2\u0000collection-scope-switch"
        val selectedScope = mutableStateOf(remote)
        try {
            store.add(remote, hash, "Remote membership")
            store.remove(local, hash, "Local membership")
            rule.setContent {
                MaterialTheme { TransferCollectionEditor(hash, "Same hash", selectedScope.value, store) {} }
            }
            waitForText("Remote membership")
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithTag("collection-name-input").performTextInput("Unsaved remote draft")
            rule.runOnIdle { selectedScope.value = local }
            waitForText("No manual collections")
            rule.onNodeWithText("Remote membership").assertDoesNotExist()
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithText("Unsaved remote draft").assertDoesNotExist()
            rule.onNodeWithTag("collection-name-input").performTextInput("Local membership")
            rule.onNodeWithText("Add to collection").performClick()
            waitForText("Membership saved on this device. Sharing is unchanged.")
            assertEquals(listOf("Local membership"), store.forTransfer(local, hash))
            assertEquals(listOf("Remote membership"), store.forTransfer(remote, hash))
        } finally {
            store.remove(remote, hash, "Remote membership")
            store.remove(local, hash, "Local membership")
        }
    }
}
