package io.github.gamesbyian.chunks.snapshots

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.RemoteFolderPicker
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.SessionEraseOutcome
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Actual selected-Place stopped Add via a challenged same-tenant ruTorrent mount. */
@RunWith(AndroidJUnit4::class)
class RemoteSnapshotSelectedPlaceInstalledTest {

    @get:Rule val compose = createComposeRule()

    @Test fun authenticatedBrowserSelectsExactChildWithoutTorrentMutation() {
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("chunks_combined_disposable") == "authorized")
        val account = RTorrentRemote(RemoteCredentials(
            "http://127.0.0.1:18080/rutorrent/plugins/httprpc/action.php", "chunks", "chunks-one"))
        val independent = RTorrentRemote(RemoteCredentials(
            "http://127.0.0.1:18080/RPC2", "chunks", "chunks-one"))
        val (before, selectedExpected) = runBlocking {
            account.version()
            independent.listCompleteSessionTransfers().map { it.hash }.toSet() to
                (requireNotNull(independent.defaultDirectory()) + "/reviewed")
        }
        var chosen by mutableStateOf<String?>(null)
        compose.setContent {
            MaterialTheme {
                RemoteFolderPicker(account, "Disposable server A", initialFolder = null,
                    onChoose = { chosen = it }, onDismiss = {})
            }
        }
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasContentDescription("Open folder reviewed"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Open folder reviewed").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("Use this folder") and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Use this folder").performClick()
        assertEquals(selectedExpected, chosen)
        assertEquals(before, runBlocking {
            independent.listCompleteSessionTransfers().map { it.hash }.toSet()
        })
    }
    @Test fun exactAuthenticatedSelectedFolderAddsOnceStoppedAndOtherAccountUntouched() {
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("chunks_combined_disposable") == "authorized")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = RTorrentRemote(RemoteCredentials(
            "http://127.0.0.1:18080/rutorrent/plugins/httprpc/action.php", "chunks", "chunks-one"))
        val independent = RTorrentRemote(RemoteCredentials(
            "http://127.0.0.1:18080/RPC2", "chunks", "chunks-one"))
        val other = RTorrentRemote(RemoteCredentials("http://127.0.0.1:18082/RPC2", "", ""))
        val name = "selected-place.bin"
        val tracker = "http://127.0.0.1:1/announce"
        val bytes = ("d8:announce${tracker.length}:$tracker" +
            "4:infod6:lengthi5e4:name${name.length}:$name" +
            "12:piece lengthi16384e6:pieces20:").toByteArray() +
            MessageDigest.getInstance("SHA-1").digest("hello".toByteArray()) +
            "ee".toByteArray()
        val original = TorrentMetainfoReader.preview(bytes)
        val hash = original.infoHashV1
        val item = SnapshotManifestV1.Item(hash, original.name, SnapshotManifestV1.Recovery.METAINFO)
        val archive = SnapshotManifestV1.decode(SnapshotManifestV1.encode(
            SnapshotManifestV1.Manifest("disposable", "2026-11-23T00:00:00Z",
                SnapshotManifestV1.Source.LOCAL, listOf(item)), mapOf(hash to bytes)))
        runBlocking {
            account.version()
            val previous = independent.listCompleteSessionTransfers().map { it.hash }
            val otherPrevious = other.listCompleteSessionTransfers().map { it.hash }.toSet()
            val chosen = requireNotNull(independent.defaultDirectory()) + "/reviewed"
            assertNull(independent.lookupName(hash))
            var admitted = 0
            val coordinator = SnapshotRemoteDefinitionRestore(account,
                AndroidSnapshotRestoreJournalStore(context),
                stillCurrent = { true }, admitWrite = { admitted++; true }, settleWrite = {})
            assertNull(coordinator.review(archive, hash, previous, recoveryOnly = false,
                selectedPlace = "/foreign/principal"))
            assertEquals(0, admitted)
            assertNull(independent.lookupName(hash))
            val review = requireNotNull(coordinator.review(archive, hash, previous,
                recoveryOnly = false, selectedPlace = chosen))
            assertFalse(review.serverDefault)
            assertEquals(chosen, review.place)
            assertEquals(SnapshotRemoteDefinitionRestore.Outcome.STALE,
                coordinator.restore(review, confirmed = false))
            assertEquals(0, admitted)
            assertEquals(SnapshotRemoteDefinitionRestore.Outcome.VERIFIED,
                coordinator.restore(review, confirmed = true))
            assertEquals(1, admitted)
            assertEquals(SnapshotRemoteDefinitionRestore.Outcome.BLOCKED,
                coordinator.restore(review, confirmed = true))
            val all = independent.listCompleteSessionTransfers()
            val row = all.single { it.hash.equals(hash, true) }
            assertEquals(name, row.name)
            assertEquals(chosen, row.directory)
            assertFalse(row.started)
            assertEquals(false, independent.readActivity(hash).active)
            assertEquals(0L, row.completedBytes)
            assertEquals(previous.map { it.uppercase() }.toSet(),
                all.filterNot { it.hash.equals(hash, true) }.map { it.hash.uppercase() }.toSet())
            assertNull(other.lookupName(hash))
            assertEquals(otherPrevious, other.listCompleteSessionTransfers().map { it.hash }.toSet())
            // Remove only this stopped synthetic session registration. No payload deletion.
            val removed = independent.eraseFromSession(hash, name, chosen)
            assertTrue(removed is SessionEraseOutcome.VerifiedRemoved ||
                removed is SessionEraseOutcome.PendingRemoval)
            for (attempt in 0 until 10) {
                if (independent.lookupName(hash) == null) break
                delay(300)
            }
            assertNull(independent.lookupName(hash))
        }
    }
}
