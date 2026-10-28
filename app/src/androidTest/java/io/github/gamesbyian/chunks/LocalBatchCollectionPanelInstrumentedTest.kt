package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalBatchCollectionPanelInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE
    private val h1 = "8".repeat(40)
    private val h2 = "9".repeat(40)
    private val name = "Chunks local batch UI fixture"

    private fun transfer(hash: String, title: String) = LocalTransferStatus(
        infoHash = hash, name = title, state = LocalTransferState.DOWNLOADING,
        stopped = true, progressPpm = 0, wantedBytes = 10, doneBytes = 0,
        payloadDownloadedBytes = 0, payloadUploadedBytes = 0,
        peers = 0, savePath = "/local/test", error = null
    )
    private val transfers = listOf(transfer(h1, "Public transfer one"),
        transfer(h2, "Public transfer two"))

    @Test fun localBatchReviewCreatesOnlyScopedCollectionsAndDoesNotTouchTorrentIdentity() {
        val store = TransferCollectionStore(
            InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            compose.setContent {
                MaterialTheme {
                    LocalBatchCollectionPanel(transfers, transfers,
                        inventoryStale = false, store = store, onMembershipChanged = {})
                }
            }
            compose.onNodeWithText("Batch organize phone transfers").performClick()
            compose.onNodeWithText("Select shown (2)").performClick()
            compose.onNodeWithText("Collection name").performTextInput(name)
            compose.onNodeWithText("Review local Collection assignment").performClick()
            compose.onNodeWithText("Confirm local batch Collection").performClick()
            compose.waitUntil(10_000) {
                store.forTransfer(scope, h1).contains(name) &&
                    store.forTransfer(scope, h2).contains(name)
            }
            assertEquals("Public transfer one", transfers[0].name)
            assertEquals("Public transfer two", transfers[1].name)
        } finally {
            store.remove(scope, h1, name)
            store.remove(scope, h2, name)
        }
    }

    @Test fun staleInventoryCannotEnterBatchSelection() {
        val store = TransferCollectionStore(
            InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setContent {
            MaterialTheme {
                LocalBatchCollectionPanel(transfers, transfers,
                    inventoryStale = true, store = store, onMembershipChanged = {})
            }
        }
        compose.onNodeWithText("Batch organize phone transfers").assertIsNotEnabled()
        compose.onNodeWithText("Select shown (2)").assertDoesNotExist()
    }

    @Test fun batchCopyToServerNeedsSeparateConfirmationAndSendsExactlyTheSelection() {
        val store = TransferCollectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val copied = ArrayList<List<String>>()
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(
                    androidx.compose.foundation.rememberScrollState())) {
                    LocalBatchCollectionPanel(transfers, transfers, inventoryStale = false, store = store,
                        onMembershipChanged = {}, copyServerLabel = "seed.example",
                        onBatchCopyToServer = { copied += it })
                }
            }
        }
        compose.onNodeWithText("Batch organize phone transfers").performClick()
        compose.onNodeWithText("Public transfer two").performClick()
        compose.onNodeWithText("Copy Transfer to server (1)…").performScrollTo().performClick()
        compose.onNodeWithText("Downloaded files are not transferred", substring = true).assertExists()
        assertTrue(copied.isEmpty())
        compose.onNodeWithText("Confirm copy to server").performScrollTo().performClick()
        assertEquals(listOf(listOf(h2)), copied)
    }

    @Test fun batchCopyIsHiddenWhenSignedOut() {
        val store = TransferCollectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setContent {
            MaterialTheme {
                LocalBatchCollectionPanel(transfers, transfers, inventoryStale = false, store = store,
                    onMembershipChanged = {})
            }
        }
        compose.onNodeWithText("Batch organize phone transfers").performClick()
        compose.onNodeWithText("Copy selected to the server").assertDoesNotExist()
    }
}
