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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticExportProvider
import io.github.gamesbyian.chunks.RemoteFolderPicker
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteDirectoryLookup
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
        val directoryRoot = runBlocking { account.browseDirectories("") }
        assertTrue("Authenticated root lookup returned " +
            (if (directoryRoot is RemoteDirectoryLookup.Unavailable) directoryRoot.reason.name
             else "ready"), directoryRoot is RemoteDirectoryLookup.Ready)
        val rootPage = (directoryRoot as RemoteDirectoryLookup.Ready).page
        assertTrue("Expected only a disposable reviewed child",
            rootPage.folders.contains("reviewed"))
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
        // A second missing original shares the archive but has NO journal.
        // It must never become a restore candidate during read-only recovery.
        val otherName = "unattempted.bin"
        val otherBytes = ("d8:announce${tracker.length}:$tracker" +
            "4:infod6:lengthi5e4:name${otherName.length}:$otherName" +
            "12:piece lengthi16384e6:pieces20:").toByteArray() +
            MessageDigest.getInstance("SHA-1").digest("other".toByteArray()) +
            "ee".toByteArray()
        val otherOriginal = TorrentMetainfoReader.preview(otherBytes)
        val otherItem = SnapshotManifestV1.Item(
            otherOriginal.infoHashV1, otherOriginal.name, SnapshotManifestV1.Recovery.METAINFO)
        val archive = SnapshotManifestV1.decode(SnapshotManifestV1.encode(
            SnapshotManifestV1.Manifest("chunks-android", "2026-11-23T00:00:00Z",
                SnapshotManifestV1.Source.LOCAL,
                listOf(item, otherItem).sortedBy { it.infoHashV1 }),
            mapOf(hash to bytes, otherOriginal.infoHashV1 to otherBytes)))
        runBlocking {
            account.version()
            val previous = independent.listCompleteSessionTransfers().map { it.hash }
            val otherPrevious = other.listCompleteSessionTransfers().map { it.hash }.toSet()
            val chosen = requireNotNull(independent.defaultDirectory()) + "/reviewed"
            assertNull(independent.lookupName(hash))
            var admitted = 0
            val journalStore = AndroidSnapshotRestoreJournalStore(context)
            val coordinator = SnapshotRemoteDefinitionRestore(account,
                journalStore,
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

            // Fresh, complete current roster and two independently verified
            // original definitions: exact journal checks must never submit a
            // second Add or claim a missing journal proves a safe retry.
            val now = all.map { it.hash }
            assertNull("An outdated comparison must never claim a batch recovery",
                coordinator.inspectPreviousBatch(archive, previous, selectedPlace = chosen))
            assertEquals(1, admitted)
            val report = requireNotNull(coordinator.inspectPreviousBatch(
                archive, now, selectedPlace = chosen))
            assertEquals(2, report.items.size)
            assertEquals(1, report.verifiedCount)
            assertEquals(1, report.unresolvedCount)
            assertEquals(SnapshotRemoteDefinitionRestore.PreviousState.VERIFIED_STOPPED,
                report.items.single { it.hash == hash }.state)
            assertEquals(SnapshotRemoteDefinitionRestore.PreviousState.JOURNAL_UNAVAILABLE,
                report.items.single { it.hash == otherOriginal.infoHashV1 }.state)
            assertEquals(1, admitted)
            assertNull(independent.lookupName(otherOriginal.infoHashV1))

            // An explicit prior REVIEW journal is different from an absent or
            // corrupt one. The batch check itself must create NEITHER.
            val accountFingerprint = SnapshotArchiveV1.digest(
                ("chunks-rtorrent-snapshot-account-v1:" + account.addAccountScope())
                    .toByteArray(Charsets.UTF_8))
            val itemFingerprint = SnapshotRestoreOnceV1.itemArchiveFingerprint(
                SnapshotManifestV1.definitionArchiveFingerprint(archive),
                otherOriginal.infoHashV1)
            val untouchedJournal = SnapshotRestoreJournalV1.open(
                journalStore, itemFingerprint, accountFingerprint,
                listOf(otherOriginal.infoHashV1))
            assertEquals(SnapshotRestoreJournalV1.Phase.REVIEW,
                untouchedJournal.entries().single().phase)
            val noSubmission = requireNotNull(coordinator.inspectPreviousBatch(
                archive, now, selectedPlace = chosen))
            assertEquals(SnapshotRemoteDefinitionRestore.PreviousState.NO_SUBMISSION_IN_THIS_JOURNAL,
                noSubmission.items.single { it.hash == otherOriginal.infoHashV1 }.state)
            assertEquals(SnapshotRestoreJournalV1.Phase.REVIEW,
                untouchedJournal.entries().single().phase)
            assertEquals(1, admitted)
            // Reinterpreting the earlier Add under another selected folder
            // must report a different approval, not resolve it or retry it.
            val wrongPlace = requireNotNull(coordinator.inspectPreviousBatch(
                archive, now, selectedPlace = "/foreign/principal"))
            assertEquals(SnapshotRemoteDefinitionRestore.PreviousState.DIFFERENT_REVIEWED_PLACE,
                wrongPlace.items.single { it.hash == hash }.state)
            assertEquals(1, admitted)
            assertEquals(now.toSet(), independent.listCompleteSessionTransfers().map { it.hash }.toSet())
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

    @Test fun approvedRemoteStoppedBatchPreflightsEveryJournalAndNeverReplaysUncertainItems() {
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("chunks_combined_disposable") == "authorized")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = RTorrentRemote(RemoteCredentials(
            "http://127.0.0.1:18080/rutorrent/plugins/httprpc/action.php", "chunks", "chunks-one"))
        val independent = RTorrentRemote(RemoteCredentials(
            "http://127.0.0.1:18080/RPC2", "chunks", "chunks-one"))
        val other = RTorrentRemote(RemoteCredentials("http://127.0.0.1:18082/RPC2", "", ""))
        val tracker = "http://127.0.0.1:1/announce"
        fun original(name: String): Pair<String, ByteArray> {
            val bytes = ("d8:announce${tracker.length}:$tracker" +
                "4:infod6:lengthi5e4:name${name.length}:$name" +
                "12:piece lengthi16384e6:pieces20:").toByteArray() +
                MessageDigest.getInstance("SHA-1").digest(name.toByteArray()) +
                "ee".toByteArray()
            return TorrentMetainfoReader.preview(bytes).infoHashV1 to bytes
        }
        fun archive(vararg names: String): SnapshotManifestV1.Verified {
            val originals = names.map(::original)
            val manifest = SnapshotManifestV1.Manifest(
                "chunks-android", "2026-11-23T00:00:00Z",
                SnapshotManifestV1.Source.LOCAL, originals.map { (hash, bytes) ->
                    val name = TorrentMetainfoReader.preview(bytes).name
                    SnapshotManifestV1.Item(hash, name, SnapshotManifestV1.Recovery.METAINFO)
                }.sortedBy { it.infoHashV1 })
            return SnapshotManifestV1.decode(
                SnapshotManifestV1.encode(manifest, originals.toMap()))
        }
        runBlocking {
            account.version()
            val before = independent.listCompleteSessionTransfers().map { it.hash }
            val otherBefore = other.listCompleteSessionTransfers().map { it.hash }.toSet()
            val chosen = requireNotNull(independent.defaultDirectory()) + "/reviewed"
            val store = AndroidSnapshotRestoreJournalStore(context)
            var admitted = 0
            val batch = SnapshotRemoteBatchStoppedRestore(account, store,
                stillCurrent = { true }, admitWrite = { admitted++; true }, settleWrite = {})
            val verified = archive("batch-stopped-one.bin", "batch-stopped-two.bin")
            val selected = verified.manifest.items.map { it.infoHashV1 }

            assertNull("Another tenant cannot prepare stopped bulk writes",
                batch.prepare(verified, selected, before, selectedPlace = "/foreign/tenant"))
            val draft = requireNotNull(batch.prepare(verified, selected, before,
                selectedPlace = chosen))
            assertEquals(2, draft.count)
            assertFalse(draft.serverDefault)
            assertEquals(chosen, draft.place)
            val denied = batch.execute(draft, confirmed = false)
            assertEquals(SnapshotRemoteBatchStoppedRestore.Stop.NOT_CONFIRMED, denied.stop)
            assertEquals(0, denied.verified)
            assertEquals(0, admitted)
            for (hash in selected) assertNull(independent.lookupName(hash))

            // The last item of another proposed batch has an unresolved
            // durable IN_FLIGHT write. The entire group must be blocked BEFORE
            // any of its earlier items is submitted. No server fixture job is
            // manufactured here: only a hash-only synthetic journal receipt.
            val blocked = archive("blocked-batch-one.bin", "blocked-batch-two.bin")
            val blockedHashes = blocked.manifest.items.map { it.infoHashV1 }
            val pendingHash = blockedHashes.last()
            val target = SnapshotArchiveV1.digest(
                ("chunks-rtorrent-snapshot-account-v1:" + account.addAccountScope())
                    .toByteArray(Charsets.UTF_8))
            val placeKey = SnapshotArchiveV1.digest(
                ("chunks-rtorrent-snapshot-selected-v1:" + chosen)
                    .toByteArray(Charsets.UTF_8))
            val pendingJournal = SnapshotRestoreJournalV1.open(
                store, SnapshotRestoreOnceV1.itemArchiveFingerprint(
                    SnapshotManifestV1.definitionArchiveFingerprint(blocked), pendingHash),
                target, listOf(pendingHash))
            pendingJournal.prepare(pendingHash, placeKey)
            pendingJournal.beforeAdd(pendingHash, placeKey)
            assertNull("An uncertain later journal must prevent every Add",
                batch.prepare(blocked, blockedHashes, before, selectedPlace = chosen))
            assertEquals(0, admitted)
            for (hash in blockedHashes) assertNull(independent.lookupName(hash))

            // One group confirmation, two independent non-idempotent stopped
            // Adds and independent full-session readbacks between them.
            val completed = batch.execute(draft, confirmed = true)
            assertTrue(completed.complete)
            assertEquals(2, completed.total)
            assertEquals(2, completed.verified)
            assertEquals(2, admitted)
            val after = independent.listCompleteSessionTransfers()
            assertEquals(before.map { it.uppercase() }.toSet(),
                after.filterNot { it.hash in selected }.map { it.hash.uppercase() }.toSet())
            for (hash in selected) {
                val row = after.single { it.hash.equals(hash, true) }
                assertEquals(chosen, row.directory)
                assertFalse(row.started)
                assertEquals(false, independent.readActivity(hash).active)
                assertEquals(0L, row.completedBytes)
            }
            assertEquals(otherBefore, other.listCompleteSessionTransfers().map { it.hash }.toSet())
            assertEquals(SnapshotRemoteBatchStoppedRestore.Stop.SESSION_CHANGED,
                batch.execute(draft, confirmed = true).stop)
            assertEquals(2, admitted)
            for (hash in blockedHashes) assertNull(independent.lookupName(hash))
            // Remove only two synthetic stopped *session* registrations.
            // Never delete payloads, alter the other account or retry a write.
            for (hash in selected) {
                val row = independent.listCompleteSessionTransfers()
                    .single { it.hash.equals(hash, true) }
                val removed = independent.eraseFromSession(hash, row.name, row.directory)
                assertTrue(removed is SessionEraseOutcome.VerifiedRemoved ||
                    removed is SessionEraseOutcome.PendingRemoval)
                for (attempt in 0 until 10) {
                    if (independent.lookupName(hash) == null) break
                    delay(300)
                }
                assertNull(independent.lookupName(hash))
            }
            assertEquals(before.toSet(),
                independent.listCompleteSessionTransfers().map { it.hash }.toSet())
            assertEquals(otherBefore, other.listCompleteSessionTransfers().map { it.hash }.toSet())
        }
    }

    @Test fun installedSnapshotGroupUiRequiresSecondConfirmationBeforeTwoStoppedServerWrites() {
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("chunks_combined_disposable") == "authorized")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val account = RTorrentRemote(RemoteCredentials(
            "http://127.0.0.1:18080/rutorrent/plugins/httprpc/action.php", "chunks", "chunks-one"))
        val independent = RTorrentRemote(RemoteCredentials(
            "http://127.0.0.1:18080/RPC2", "chunks", "chunks-one"))
        val other = RTorrentRemote(RemoteCredentials("http://127.0.0.1:18082/RPC2", "", ""))
        val tracker = "http://127.0.0.1:1/announce"
        fun definition(name: String): Pair<String, ByteArray> {
            val bytes = ("d8:announce${tracker.length}:$tracker" +
                "4:infod6:lengthi5e4:name${name.length}:$name" +
                "12:piece lengthi16384e6:pieces20:").toByteArray() +
                MessageDigest.getInstance("SHA-1").digest(name.toByteArray()) +
                "ee".toByteArray()
            return TorrentMetainfoReader.preview(bytes).infoHashV1 to bytes
        }
        val sources = listOf(
            definition("ui-stopped-batch-one.bin"),
            definition("ui-stopped-batch-two.bin"))
        val manifest = SnapshotManifestV1.Manifest(
            "chunks-android", "2026-11-23T00:00:00Z",
            SnapshotManifestV1.Source.LOCAL,
            sources.map { (hash, bytes) ->
                SnapshotManifestV1.Item(hash, TorrentMetainfoReader.preview(bytes).name,
                    SnapshotManifestV1.Recovery.METAINFO)
            }.sortedBy { it.infoHashV1 })
        val bytes = SnapshotManifestV1.encode(manifest, sources.toMap())
        val uri = OwnedDiagnosticExportProvider.publish(
            context, "disposable-ui-stopped-batch.chunksnap", bytes)
        val picker = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int, contract: ActivityResultContract<I, O>,
                    input: I, options: ActivityOptionsCompat?
                ) { dispatchResult(requestCode, uri) }
            }
        }
        val (initial, otherBefore, defaultPlace) = runBlocking {
            account.version()
            Triple(independent.listCompleteSessionTransfers().map { it.hash },
                other.listCompleteSessionTransfers().map { it.hash }.toSet(),
                requireNotNull(independent.defaultDirectory()))
        }
        val store = AndroidSnapshotRestoreJournalStore(context)
        var admitted = 0
        val batch = SnapshotRemoteBatchStoppedRestore(account, store,
            stillCurrent = { true }, admitWrite = { admitted++; true }, settleWrite = {})
        val single = SnapshotRemoteDefinitionRestore(account, store,
            stillCurrent = { true }, admitWrite = { admitted++; true }, settleWrite = {})
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SnapshotRestorePreviewPanel(
                            targetHashes = initial, target = SnapshotRestoreTarget.SERVER,
                            inventoryConfirmed = true, inventoryStale = false, busyEngine = false,
                            mutationGeneration = 1L, remoteDefinitionRestore = single,
                            remoteBatchStoppedRestore = batch,
                            remoteDefinitionActionScope = rememberCoroutineScope(),
                            remoteDefinitionAccountLabel = "Disposable server A",
                            remoteDefinitionFolderAccount = account
                        )
                    }
                }
            }
        }
        fun await(text: String, timeout: Long = 45_000) {
            compose.waitUntil(timeout) {
                compose.onAllNodesWithText(text, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.onNodeWithText("Compare saved snapshot with rTorrent server").performClick()
        compose.onNodeWithText("Choose .chunksnap to compare").performScrollTo().performClick()
        await("Review 2 stopped server restores")
        compose.onNodeWithText("Review 2 stopped server restores").performScrollTo().performClick()
        await("Confirm 2 stopped restores")
        compose.onNodeWithText("rTorrent account: Disposable server A").assertExists()
        compose.onNodeWithText("Current server default Place: " + defaultPlace).assertExists()
        assertEquals(0, admitted)
        for ((hash, _) in sources) assertNull(runBlocking { independent.lookupName(hash) })
        compose.onNodeWithText("Confirm 2 stopped restores").performClick()
        await("2/2 stopped server definitions independently verified.")
        assertEquals(2, admitted)
        runBlocking {
            val roster = independent.listCompleteSessionTransfers()
            assertEquals(initial.map { it.uppercase() }.toSet(),
                roster.filterNot { row -> sources.any { it.first.equals(row.hash, true) } }
                    .map { it.hash.uppercase() }.toSet())
            for ((hash, _) in sources) {
                val row = roster.single { it.hash.equals(hash, true) }
                assertFalse(row.started)
                assertEquals(defaultPlace, row.directory)
                assertEquals(false, independent.readActivity(hash).active)
                assertEquals(0L, row.completedBytes)
            }
            assertEquals(otherBefore, other.listCompleteSessionTransfers().map { it.hash }.toSet())
            // Retire only this synthetic stopped session state and preserve
            // both daemons' preloaded data for subsequent fault/Combined cases.
            for ((hash, _) in sources) {
                val row = independent.listCompleteSessionTransfers()
                    .single { it.hash.equals(hash, true) }
                val removed = independent.eraseFromSession(hash, row.name, row.directory)
                assertTrue(removed is SessionEraseOutcome.VerifiedRemoved ||
                    removed is SessionEraseOutcome.PendingRemoval)
                for (attempt in 0 until 10) {
                    if (independent.lookupName(hash) == null) break
                    delay(300)
                }
                assertNull(independent.lookupName(hash))
            }
            assertEquals(initial.toSet(),
                independent.listCompleteSessionTransfers().map { it.hash }.toSet())
            assertEquals(otherBefore, other.listCompleteSessionTransfers().map { it.hash }.toSet())
        }
    }
}
