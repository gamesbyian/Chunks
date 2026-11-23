package io.github.gamesbyian.chunks

import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticExportProvider
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotRestorePreviewPanel
import io.github.gamesbyian.chunks.snapshots.SnapshotManifestV1
import io.github.gamesbyian.chunks.snapshots.applyLocalSnapshotAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSnapshotRestorePreviewPanelInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val transfer = LocalTransferStatus(
        infoHash = "a".repeat(40), name = "Public example",
        state = LocalTransferState.DOWNLOADING, stopped = false,
        progressPpm = 0, wantedBytes = 1000, doneBytes = 0,
        payloadDownloadedBytes = 0, payloadUploadedBytes = 0,
        peers = 0, savePath = "/app-private", error = null)

    @Test fun startupEmptyComposeListCannotBeTreatedAsCompleteEngineInventory() {
        compose.setContent {
            MaterialTheme {
                LocalSnapshotRestorePreviewPanel(
                    emptyList(), inventoryConfirmed = false, inventoryStale = false,
                    busyEngine = false, mutationGeneration = 0)
            }
        }
        compose.onNodeWithText("Compare saved snapshot with local").performClick()
        compose.onNodeWithText("Snapshot restore comparison (read-only)").assertExists()
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsNotEnabled()
        compose.onNodeWithText("Local inventory is not yet confirmed, is stale, or a native " +
            "operation is in progress. Wait for a fresh full read.").assertExists()
    }

    @Test fun trulyConfirmedEmptyEngineCanReviewWithoutClaimingAnyTorrentMutation() {
        compose.setContent {
            MaterialTheme {
                LocalSnapshotRestorePreviewPanel(
                    emptyList(), inventoryConfirmed = true, inventoryStale = false,
                    busyEngine = false, mutationGeneration = 1)
            }
        }
        compose.onNodeWithText("Compare saved snapshot with local").performClick()
        compose.onNodeWithText("Current verified local queue: 0 transfer identities. " +
            "Comparisons are discarded if this list changes.").assertExists()
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsEnabled()
        compose.onNodeWithText("Restore selected transfers").assertDoesNotExist()
    }

    @Test fun savedAliasRequiresSeparateActionAndPreservesExistingAlias() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hash = java.util.UUID.randomUUID().toString().replace("-", "") + "0".repeat(8)
        val target = transfer.copy(infoHash = hash)
        val store = TransferAliasStore(context)
        val localScope = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        val item = SnapshotManifestV1.Item(hash, target.name,
            SnapshotManifestV1.Recovery.INVENTORY,
            settings = SnapshotManifestV1.Settings(displayAlias = "Snapshot label"))
        val archive = SnapshotManifestV1.encode(SnapshotManifestV1.Manifest(
            "chunks-android", "2026-11-23T00:00:00Z", SnapshotManifestV1.Source.LOCAL,
            listOf(item)), emptyMap())
        val uri = OwnedDiagnosticExportProvider.publish(context, "snapshot-alias-test.chunksnap", archive)
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?
                ) {
                    dispatchResult(requestCode, uri)
                }
            }
        }
        var generation by mutableStateOf(1L)
        var live = target
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        LocalSnapshotRestorePreviewPanel(
                            listOf(target), inventoryConfirmed = true, inventoryStale = false,
                            busyEngine = false, mutationGeneration = generation,
                            onApplyAlias = { entry, _, report ->
                                report(applyLocalSnapshotAlias(store, entry, live))
                            })
                    }
                }
            }
        }
        try {
            compose.onNodeWithText("Compare saved snapshot with local").performClick()
            compose.onNodeWithText("Choose .chunksnap to compare").performScrollTo().performClick()
            val action = compose.onNodeWithText("Apply saved alias if this transfer has none")
            compose.waitUntil(10_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText(
                    "Apply saved alias if this transfer has none")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Infohash $hash").assertExists()
            assertNull(store.get(localScope, hash))
            compose.runOnIdle { live = target.copy(infoHash = "f".repeat(40)) }
            action.performScrollTo().performClick()
            compose.onNodeWithText("The exact Local transfer could not be confirmed. No alias was applied.")
                .assertExists()
            assertNull(store.get(localScope, hash))
            compose.runOnIdle { live = target }
            action.performScrollTo().performClick()
            compose.onNodeWithText("Saved alias verified on this device.").assertExists()
            assertEquals("Snapshot label", store.get(localScope, hash))
            compose.runOnIdle { store.save(localScope, hash, "Manual label") }
            action.performScrollTo().performClick()
            compose.onNodeWithText("An existing alias was kept. No alias was replaced.").assertExists()
            assertEquals("Manual label", store.get(localScope, hash))
            compose.runOnIdle { generation++ }
            action.assertDoesNotExist()
        } finally {
            store.save(localScope, hash, null)
        }
    }

    @Test fun staleFrameAndNativeCommandBlockPickerAndRevokePriorEligibility() {
        var isStale by mutableStateOf(false)
        var busy by mutableStateOf(false)
        var generation by mutableStateOf(1L)
        compose.setContent {
            MaterialTheme {
                LocalSnapshotRestorePreviewPanel(
                    listOf(transfer), inventoryConfirmed = true,
                    inventoryStale = isStale, busyEngine = busy,
                    mutationGeneration = generation)
            }
        }
        compose.onNodeWithText("Compare saved snapshot with local").performClick()
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsEnabled()
        compose.runOnIdle { isStale = true; generation++ }
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsNotEnabled()
        compose.runOnIdle { isStale = false; busy = true; generation++ }
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsNotEnabled()
        compose.runOnIdle { busy = false; generation++ }
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsEnabled()
    }
}
