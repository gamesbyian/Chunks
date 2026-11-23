package io.github.gamesbyian.chunks.snapshots

import android.graphics.Bitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticExportProvider
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.SessionEraseOutcome
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Installed production snapshot panel backed by real, independently read disposable servers. */
@RunWith(AndroidJUnit4::class)
class RemoteSnapshotRestoreInstalledTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reviewAndCancelDoNotWriteThenConfirmedRestoreTouchesOnlyTheReviewedServer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("chunks_combined_disposable") == "authorized")
        // The existing CI fixture owns both exact loopback endpoints and this synthetic Basic login.
        val credentials = RemoteCredentials("http://127.0.0.1:18080/RPC2", "chunks", "chunks-one")
        val account = RTorrentRemote(credentials)
        val independent = RTorrentRemote(credentials)
        val other = RTorrentRemote(RemoteCredentials("http://127.0.0.1:18082/RPC2", "", ""))
        val context = instrumentation.targetContext
        val bytes = ("d4:infod6:lengthi5e4:name20:snapshot-android.bin12:piece lengthi16384e6:pieces20:" +
            "01234567890123456789ee").toByteArray()
        val original = TorrentMetainfoReader.preview(bytes)
        val hash = original.infoHashV1
        val item = SnapshotManifestV1.Item(hash, original.name, SnapshotManifestV1.Recovery.METAINFO)
        val raw = SnapshotManifestV1.encode(
            SnapshotManifestV1.Manifest("chunks-android", "2026-11-23T00:00:00Z",
                SnapshotManifestV1.Source.LOCAL, listOf(item)), mapOf(hash to bytes))
        val uri = OwnedDiagnosticExportProvider.publish(context, "disposable-remote-restore.chunksnap", raw)
        val picker = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                    options: ActivityOptionsCompat?
                ) { dispatchResult(requestCode, uri) }
            }
        }
        val (hashes, otherHashes, place) = runBlocking {
            account.version()
            Triple(independent.listCompleteSessionTransfers().map { it.hash },
                other.listCompleteSessionTransfers().map { it.hash }.toSet(),
                requireNotNull(independent.defaultDirectory()))
        }
        assertTrue(hashes.none { it.equals(hash, true) })
        val restore = SnapshotRemoteDefinitionRestore(account, AndroidSnapshotRestoreJournalStore(context),
            stillCurrent = { true }, admitWrite = { true }, settleWrite = {})
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SnapshotRestorePreviewPanel(
                            targetHashes = hashes, target = SnapshotRestoreTarget.SERVER,
                            inventoryConfirmed = true, inventoryStale = false, busyEngine = false,
                            mutationGeneration = 1L, remoteDefinitionRestore = restore,
                            remoteDefinitionActionScope = rememberCoroutineScope(),
                            remoteDefinitionAccountLabel = "Disposable server A"
                        )
                    }
                }
            }
        }
        fun awaitText(text: String, timeout: Long = 25_000) {
            compose.waitUntil(timeout) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }
        fun reviewOnce() {
            awaitText("Review stopped server restore")
            compose.onNodeWithText("Review stopped server restore").performScrollTo().performClick()
            awaitText("Confirm stopped restore")
            compose.onNodeWithText("Disposable server A").assertExists()
            compose.onNodeWithText("Infohash: " + hash).assertExists()
            compose.onNodeWithText("Server default Place: " + place).assertExists()
        }
        compose.onNodeWithText("Compare saved snapshot with rTorrent server").performClick()
        compose.onNodeWithText("Choose .chunksnap to compare").performScrollTo().performClick()
        reviewOnce()
        // This screen contains only the disposable account and synthetic source.
        // Capture just the functional confirmation for installed layout review.
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(requireNotNull(context.getExternalFilesDir(null)), "disposable-snapshot-review.png")
                .outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
        } finally {
            screenshot.recycle()
        }
        assertNull(runBlocking { independent.lookupName(hash) })
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Confirm stopped restore").assertDoesNotExist()
        assertNull(runBlocking { independent.lookupName(hash) })
        reviewOnce()
        // Invoke the installed accessibility action exactly once. A transient
        // window/overlay must not be mistaken for a dispatch or retried.
        compose.onNodeWithText("Confirm stopped restore").performSemanticsAction(SemanticsActions.OnClick) {
            assertTrue("The confirmation must own its enabled click action", it())
        }
        try {
            compose.waitUntil(45_000) {
                compose.onAllNodesWithText("Stopped at the reviewed Place.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            // Read-only witnesses. Never dump window text or repeat the Add.
            val state = listOf(
                "pending" to "Restoring one stopped definition",
                "origin-unknown" to "The origin of the registration",
                "blocked" to "No Add was sent by this action",
                "stale" to "The rTorrent account or comparison changed",
                "uncertain" to "Restore not verified",
                "dialog-open" to "Confirm stopped restore"
            ).firstOrNull { (_, prefix) ->
                compose.onAllNodesWithText(prefix, substring = true).fetchSemanticsNodes().isNotEmpty()
            }?.first ?: "no-result"
            val registered = runCatching { runBlocking { independent.lookupName(hash) != null } }
                .getOrNull()
            throw AssertionError("Stopped snapshot restore did not verify; state=$state " +
                "exactRegistration=${registered ?: "unavailable"}", timeout)
        }
        runBlocking {
            val inventory = independent.listCompleteSessionTransfers()
            val row = inventory.single { it.hash.equals(hash, true) }
            assertEquals(original.name, row.name)
            assertEquals(place, row.directory)
            assertFalse(row.started)
            assertEquals(false, independent.readActivity(hash).active)
            assertEquals(0L, row.completedBytes)
            assertEquals(hashes.toSet(), inventory.filterNot { it.hash.equals(hash, true) }.map { it.hash }.toSet())
            assertNull(other.lookupName(hash))
            assertEquals(otherHashes, other.listCompleteSessionTransfers().map { it.hash }.toSet())

            // Leave the preloaded daemons unchanged for the Combined fault journey.
            // The fixture is an independently verified exact stopped session, with
            // no downloaded data. Issue one session-only removal, never a retry.
            val removal = independent.eraseFromSession(hash, row.name, row.directory)
            assertTrue(removal is SessionEraseOutcome.VerifiedRemoved ||
                removal is SessionEraseOutcome.PendingRemoval)
            for (attempt in 0 until 10) {
                if (independent.lookupName(hash) == null) break
                delay(300)
            }
            assertNull("Only the tested fixture job must be gone", independent.lookupName(hash))
            assertEquals(hashes.toSet(), independent.listCompleteSessionTransfers().map { it.hash }.toSet())
        }
    }
}
