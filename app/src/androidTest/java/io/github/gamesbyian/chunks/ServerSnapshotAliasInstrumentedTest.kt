package io.github.gamesbyian.chunks

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticExportProvider
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.snapshots.SnapshotManifestV1
import io.github.gamesbyian.chunks.snapshots.SnapshotRestorePreviewPanel
import io.github.gamesbyian.chunks.snapshots.SnapshotRestoreTarget
import io.github.gamesbyian.chunks.snapshots.applyRemoteSnapshotAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Compose snapshot review with synthetic owned archive and no remote RPC. */
@RunWith(AndroidJUnit4::class)
class ServerSnapshotAliasInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun picker(uri: Uri): ActivityResultRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?
            ) { dispatchResult(requestCode, uri) }
        }
    }

    @Test fun exactAccountAliasReviewDoesNotRenameServerOrOverwriteManualAlias() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hash = "ac".repeat(20)
        val alice = "https://synthetic.example/RPC2\u0000alice"
        val bob = "https://synthetic.example/RPC2\u0000bob"
        val store = TransferAliasStore(context)
        val name = "Public original"
        val item = SnapshotManifestV1.Item(hash, name,
            SnapshotManifestV1.Recovery.INVENTORY,
            settings = SnapshotManifestV1.Settings(displayAlias = "Snapshot label"))
        val raw = SnapshotManifestV1.encode(SnapshotManifestV1.Manifest(
            "chunks-native-test", "2026-11-23T00:00:00Z", SnapshotManifestV1.Source.LOCAL,
            listOf(item)), emptyMap())
        val uri = OwnedDiagnosticExportProvider.publish(
            context, "snapshot-remote-alias-test.chunksnap", raw)
        var revision by mutableStateOf(1L)
        var principal by mutableStateOf(alice)
        var current = RemoteTransfer(hash, name, 7, 0, false, "/safe")
        val registry = picker(uri)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                    MaterialTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            SnapshotRestorePreviewPanel(
                                targetHashes = listOf(hash), target = SnapshotRestoreTarget.SERVER,
                                inventoryConfirmed = true, inventoryStale = false,
                                busyEngine = false, mutationGeneration = revision,
                                onPrepareAdd = {}, onPrepareMagnet = {},
                                onApplyAlias = { entry, _, respond ->
                                    respond(applyRemoteSnapshotAlias(store, principal, entry, current))
                                })
                        }
                    }
                }
            }
            compose.onNodeWithText("Compare saved snapshot with rTorrent server").performClick()
            fun review() {
                compose.onNodeWithText("Choose .chunksnap to compare").performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodes(androidx.compose.ui.test.hasText(
                        "Apply saved alias if this transfer has none"))
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
            review()
            val apply = compose.onNodeWithText("Apply saved alias if this transfer has none")
            compose.onNodeWithText("It never renames the server torrent or any files.",
                substring = true).assertExists()
            assertNull(store.get(alice, hash))
            compose.runOnIdle { current = current.copy(name = "Unexpected rename") }
            apply.performScrollTo().performClick()
            compose.onNodeWithText("The exact account and original transfer could not be confirmed.",
                substring = true).assertExists()
            assertNull(store.get(alice, hash))

            compose.runOnIdle { current = current.copy(name = name) }
            apply.performScrollTo().performClick()
            compose.onNodeWithText("Saved remote display alias verified on this device.").assertExists()
            assertEquals("Snapshot label", store.get(alice, hash))
            assertNull(store.get(bob, hash))
            assertEquals(name, current.name) // Chunks label only, no remote transfer mutation.

            compose.runOnIdle { principal = bob; revision++ }
            apply.assertDoesNotExist()
            compose.runOnIdle { store.save(bob, hash, "Bob's manual alias") }
            review()
            compose.onNodeWithText("Apply saved alias if this transfer has none")
                .performScrollTo().performClick()
            compose.onNodeWithText("This account's existing alias was kept.",
                substring = true).assertExists()
            assertEquals("Bob's manual alias", store.get(bob, hash))
            assertEquals("Snapshot label", store.get(alice, hash))
        } finally {
            store.save(alice, hash, null)
            store.save(bob, hash, null)
        }
    }
}
