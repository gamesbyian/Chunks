package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalTransferAliasInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val hash = "f".repeat(40)
    private val remoteScope = "https://example.invalid/RPC2\u0000owner"
    private val original = "Original release name"
    private fun local() = LocalTransferStatus(
        infoHash = hash, name = original,
        state = LocalTransferState.DOWNLOADING, stopped = true,
        progressPpm = 0, wantedBytes = 64, doneBytes = 0,
        payloadDownloadedBytes = 0, payloadUploadedBytes = 0,
        peers = 0, savePath = "/data/local", error = null)

    @Test fun localAliasIsStoredOnlyInDeviceScopedMetadataAndNeverCrossesRemoteProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferAliasStore(context)
        val localScope = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        try {
            assertTrue(store.save(localScope, hash, "My List Name"))
            assertEquals("My List Name", TransferAliasStore(context).get(localScope, hash))
            assertNull(store.get(remoteScope, hash))
            assertEquals(original, local().name)
            assertTrue(store.save(localScope, hash, null))
            assertNull(store.get(localScope, hash))
        } finally {
            store.save(localScope, hash, null)
        }
    }

    @Test fun localCardDisplaysAliasAndOriginalWithRenameOnlyExplanation() {
        var alias by mutableStateOf<String?>("Custom Chunks label")
        compose.setContent {
            MaterialTheme {
                LocalTransferCard(
                    transfer = local(), busy = false,
                    onToggle = {}, onRemove = {},
                    alias = alias, onSaveAlias = { proposed -> alias = proposed; true }
                )
            }
        }
        compose.onNodeWithText("Custom Chunks label").assertExists()
        compose.onNodeWithText("Original torrent: " + original).assertExists()
        compose.onNodeWithText("Edit list name").performClick()
        compose.onNodeWithText(
            "Only the name shown in Chunks changes. The torrent, files and seeding remain untouched."
        ).assertExists()
        compose.onNodeWithText("Use original torrent name").performClick()
        compose.onNodeWithText(original).assertExists()
    }
}
