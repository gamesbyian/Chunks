package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.TorrentClientKind
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientTorrent
import io.github.gamesbyian.chunks.rpc.ClientWriteUncertainException
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.TorrentClient
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtocolClientOperationsPanelTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "ab".repeat(20)
    private val testReceipts = ProtocolOperationReceiptOwner()

    private inner class Fake : TorrentClient {
        override val kind = TorrentClientKind.QBITTORRENT
        var state = ClientRunState.STOPPED
        var present = true
        var writes = 0
        var lose = false
        var afterStartWrite: suspend () -> Unit = {}
        var afterRemoveWrite: suspend () -> Unit = {}
        var dir = "/d"
        var movedData: Boolean? = null
        var sessions = 0
        var fileReads = 0
        override suspend fun session(): ClientSessionInfo {
            sessions++
            return ClientSessionInfo(kind, "5", "/d", ClientCapability.entries.toSet())
        }
        override suspend fun torrents() = listOfNotNull(torrent(hash))
        override suspend fun torrent(hash: String) =
            if (present && hash == this@ProtocolClientOperationsPanelTest.hash)
                ClientTorrent(hash, "n", state, 1, 0, 0, 0, dir) else null
        override suspend fun files(hash: String): List<ClientFile> {
            fileReads++
            return listOf(ClientFile(0, "a", 1, 0, ClientFilePriority.NORMAL))
        }
        private fun write(apply: () -> Unit) {
            writes++
            if (lose) throw ClientWriteUncertainException("lost")
            apply()
        }
        override suspend fun add(request: ClientAddRequest) = write {}
        override suspend fun setStarted(hash: String, started: Boolean) {
            write { state = if (started) ClientRunState.DOWNLOADING else ClientRunState.STOPPED }
            afterStartWrite()
        }
        override suspend fun remove(hash: String, deleteData: Boolean) {
            write { present = false }
            afterRemoveWrite()
        }
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = write {}
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) =
            write { dir = directory; movedData = moveData }
        override suspend fun recheck(hash: String) = write {}
        override suspend fun transferSource(hash: String) =
            io.github.gamesbyian.chunks.rpc.ClientMagnets.build(hash, "n", emptyList())
    }

    private inner class Target : TorrentClient {
        override val kind = TorrentClientKind.DELUGE
        var adds = 0
        var afterAdd: suspend () -> Unit = {}
        var present = false
        var defaultDirectory: String? = "/d"
        var capabilities = ClientCapability.entries.toSet()
        val sessionReads = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun session(): ClientSessionInfo =
            ClientSessionInfo(kind, "2", defaultDirectory, capabilities).also { sessionReads.incrementAndGet() }
        override suspend fun torrents() = listOfNotNull(torrent(hash))
        override suspend fun torrent(hash: String) = if (present) ClientTorrent(hash, "n",
            ClientRunState.STOPPED, 1, 0, 0, 0, "/d") else null
        override suspend fun files(hash: String) = emptyList<ClientFile>()
        override suspend fun add(request: ClientAddRequest) {
            adds++
            present = true
            afterAdd()
        }
        override suspend fun setStarted(hash: String, started: Boolean) = Unit
        override suspend fun remove(hash: String, deleteData: Boolean) = Unit
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = Unit
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = Unit
        override suspend fun recheck(hash: String) = Unit
    }

    @Test fun cancelledCopyRetainsTheDestinationReceiptAcrossDetailsNavigation() {
        val receipts = ProtocolOperationReceiptOwner()
        val source = Fake()
        val target = Target().apply { afterAdd = { kotlinx.coroutines.awaitCancellation() } }
        val destination = ProtocolMoveTarget("copy-target", "Destination") { ClientOperations(target, 3, 1) }
        var shown by mutableStateOf(true)
        rule.setContent {
            ChunksTheme {
                if (shown) ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "Source", sourceClientId = "copy-source",
                    observedStarted = false, current = true,
                    createOperations = { ClientOperations(source, 3, 1) }, onChanged = {},
                    moveTargets = listOf(destination), sourceGeneration = "g1", receiptOwner = receipts
                )
            }
        }
        rule.waitForIdle()
        val copyTag = ChunksSemantics.PROTOCOL_MOVE + ".copy-target"
        val targetKey = ProtocolOperationReceiptOwner.Key("copy-target", hash)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.onNodeWithTag(copyTag).performClick()
        rule.waitUntil(5_000) { target.adds == 1 }
        rule.runOnIdle {
            assertEquals(ProtocolOperationReceiptOwner.Phase.POSSIBLE_WRITE, receipts.receipt(targetKey)?.phase)
            shown = false
        }
        rule.waitForIdle()
        assertEquals(ProtocolOperationReceiptOwner.Phase.UNKNOWN, receipts.receipt(targetKey)?.phase)
        rule.runOnIdle { shown = true }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.onNodeWithTag(copyTag).assertIsNotEnabled()
        rule.onNodeWithText("Check job on Destination").performClick()
        rule.waitUntil(5_000) { receipts.receipt(targetKey)?.phase == ProtocolOperationReceiptOwner.Phase.RECONCILED_PRESENT }
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE).assertDoesNotExist()
        assertEquals(1, target.adds)
        assertEquals(true, source.present)
    }

    @Test fun v2OnlyMoveSourceCannotAcquireAnUnsupportedDestinationLease() {
        val source = Fake()
        val target = Target()
        rule.setContent {
            ChunksTheme {
                ProtocolClientOperationsPanel(
                    hash = "ab".repeat(32), clientLabel = "This device", sourceClientId = "local",
                    observedStarted = false, current = true, sourceGeneration = "fixture-generation", receiptOwner = testReceipts,
                    createOperations = { ClientOperations(source, 3, 1) }, onChanged = {},
                    moveTargets = listOf(ProtocolMoveTarget("destination", "Destination") { ClientOperations(target, 3, 1) }),
                    moveOnly = true
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".destination").assertIsNotEnabled()
        assertEquals(0, target.adds)
    }

    @Test fun moveCopiesJobThenRetiresSourceOnlyAfterSecondTap() {
        val fake = Fake()
        val target = Target()
        rule.setContent {
            ChunksTheme {
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "Seedbox qB", sourceClientId = "source-qb", observedStarted = false,
                    current = true, sourceGeneration = "fixture-generation", receiptOwner = testReceipts,
                    createOperations = { ClientOperations(fake, 3, 1) }, onChanged = {},
                    moveTargets = listOf(ProtocolMoveTarget("t", "Box Deluge") { ClientOperations(target, 3, 1) })
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".t").performClick()
        rule.waitUntil(5_000) { target.present }
        rule.waitForIdle()
        rule.onNodeWithText("Job copied to Box Deluge (confirmed). Its files download there separately.").assertExists()
        assertEquals(1, target.adds)
        assertEquals("source untouched until the second tap", true, fake.present)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE).performClick()
        rule.waitUntil(5_000) { !fake.present }
        assertEquals(1, target.adds)
    }

    @Test fun replacedTargetConnectionWithdrawsTheFinishMoveAction() {
        val source = Fake()
        val target = Target()
        val sourceOperations = { ClientOperations(source, 3, 1) }
        var targetRevision by mutableStateOf(1)
        var targetListed by mutableStateOf(true)
        rule.setContent {
            ChunksTheme {
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "Source", sourceClientId = "source",
                    observedStarted = false, current = true, sourceGeneration = "fixture-generation", receiptOwner = testReceipts,
                    createOperations = sourceOperations, onChanged = {},
                    moveConnections = mapOf("t" to targetRevision),
                    moveTargets = listOfNotNull(ProtocolMoveTarget("t", "Target", connectionWitness = targetRevision) {
                        ClientOperations(target, 3, 1)
                    }.takeIf { targetListed })
                )
            }
        }
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".t").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE).fetchSemanticsNodes().size == 1
        }
        val retire = rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE)
        rule.runOnIdle { targetListed = false }
        retire.assertExists()
        rule.runOnIdle { targetRevision = 2 }
        retire.assertDoesNotExist()
        rule.runOnIdle { targetRevision = 1 }
        retire.assertDoesNotExist()
        assertEquals(0, source.writes)
        assertEquals(true, source.present)
        assertEquals(1, target.adds)
    }

    @Test fun aConfirmedCopyCarriesTheChunksNameButNeverOverwritesTheTargets() {
        val aliases = TransferAliasStore(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val sourceScope = ProtocolAliasScope.of("source-qb")
        val targetScope = ProtocolAliasScope.of("t")
        assertEquals(true, aliases.save(sourceScope, hash, "Named On Source"))
        aliases.save(targetScope, hash, null)
        val fake = Fake()
        val target = Target()
        rule.setContent {
            ChunksTheme {
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "Seedbox qB", sourceClientId = "source-qb", observedStarted = false,
                    current = true, sourceGeneration = "fixture-generation", receiptOwner = testReceipts,
                    createOperations = { ClientOperations(fake, 3, 1) }, onChanged = {},
                    moveTargets = listOf(ProtocolMoveTarget("t", "Box Deluge", targetScope) { ClientOperations(target, 3, 1) }),
                    sourceAliasScope = sourceScope
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".t").performClick()
        rule.waitUntil(5_000) { aliases.get(targetScope, hash) != null }
        assertEquals("Named On Source", aliases.get(targetScope, hash))
        assertEquals("the source keeps its name", "Named On Source", aliases.get(sourceScope, hash))
        // A name already on the target is the person's own: a later carry never replaces it.
        assertEquals(true, aliases.save(targetScope, hash, "Target's Own"))
        assertEquals(TransferAliasStore.MoveAliasCarry.TARGET_ALREADY_SET,
            aliases.carryVerifiedMoveAlias(sourceScope, targetScope, hash))
        assertEquals("Target's Own", aliases.get(targetScope, hash))
    }

    @Test fun rtorrentSourceUsesSameMoveReceiptWithoutDuplicateLifecycleControls() {
        val source = Fake()
        val destination = Target()
        rule.setContent {
            ChunksTheme {
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "rTorrent", sourceClientId = "rtorrent-account",
                    observedStarted = false, current = true, sourceGeneration = "fixture-generation", receiptOwner = testReceipts,
                    createOperations = { ClientOperations(source, 3, 1) }, onChanged = {},
                    moveTargets = listOf(ProtocolMoveTarget("other-deluge", "Deluge") {
                        ClientOperations(destination, 3, 1)
                    }), moveOnly = true
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).assertDoesNotExist()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_REMOVE).assertDoesNotExist()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".other-deluge").performClick()
        rule.waitUntil(5_000) { destination.present }
        rule.waitForIdle()
        assertEquals("Only an explicit second tap retires the source job", true, source.present)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE).performClick()
        rule.waitUntil(5_000) { !source.present }
        assertEquals(1, destination.adds)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE).assertDoesNotExist()
    }

    @Test fun moveOnlyRetirementSurvivesNavigationAndChecksAnAbsentSourceWithoutReplay() {
        val owner = ProtocolOperationReceiptOwner()
        val source = Fake().apply { afterRemoveWrite = { kotlinx.coroutines.awaitCancellation() } }
        val destination = Target()
        val sourceOperations = { ClientOperations(source, 3, 1) }
        val targets = listOf(ProtocolMoveTarget("target", "Deluge") { ClientOperations(destination, 3, 1) })
        var shown by mutableStateOf(true)
        var generation by mutableStateOf("rt-1")
        var current by mutableStateOf(true)
        rule.setContent {
            ChunksTheme {
                if (shown) ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "rTorrent", sourceClientId = "rtorrent-account",
                    sourceGeneration = generation, observedStarted = false, current = current,
                    canReconcile = true, createOperations = sourceOperations, onChanged = {},
                    moveTargets = targets, moveOnly = true, receiptOwner = owner
                )
            }
        }
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".target").performClick()
        val finish = rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE)
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE).fetchSemanticsNodes().size == 1
        }
        finish.performClick()
        rule.waitUntil(5_000) { source.writes == 1 }
        val key = ProtocolOperationReceiptOwner.Key("rtorrent-account", hash)
        rule.runOnIdle {
            assertEquals(ProtocolOperationReceiptOwner.Phase.POSSIBLE_WRITE, owner.receipt(key)?.phase)
            shown = false
        }
        rule.waitForIdle()
        assertEquals(ProtocolOperationReceiptOwner.Phase.UNKNOWN, owner.receipt(key)?.phase)
        rule.runOnIdle {
            generation = "rt-2"
            current = false
            shown = true
        }
        finish.assertDoesNotExist()
        rule.onNodeWithText("Check this transfer without changing it").performClick()
        rule.waitUntil(5_000) { owner.receipt(key)?.phase == ProtocolOperationReceiptOwner.Phase.RECONCILED_ABSENT }
        assertEquals(1, source.writes)
        assertEquals(1, destination.adds)
    }

    private fun render(fake: Fake, current: Boolean = true) {
        rule.setContent {
            ChunksTheme {
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "Seedbox qB", sourceClientId = "source-qb", observedStarted = false,
                    current = current, sourceGeneration = "fixture-generation", receiptOwner = testReceipts,
                    createOperations = { ClientOperations(fake, 3, 1) },
                    onChanged = {}
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun startIsConfirmedOnlyAfterExactReadback() {
        val fake = Fake()
        render(fake)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).performClick()
        rule.waitUntil(5_000) { fake.state == ClientRunState.DOWNLOADING }
        rule.waitForIdle()
        rule.onNodeWithText("Start: confirmed on Seedbox qB.").assertExists()
        assertEquals(1, fake.writes)
    }

    @Test fun lostReplyIsUnknownAndLocksFurtherCommands() {
        val fake = Fake().apply { lose = true }
        render(fake)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Commands are paused until a read-only check.").assertExists()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).assertIsNotEnabled()
        assertEquals(1, fake.writes)
    }

    @Test fun cancelledDispatchedCommandSurvivesNavigationAndSameProfileReconnect() {
        val owner = ProtocolOperationReceiptOwner()
        val transportHeld = CompletableDeferred<Unit>()
        val releaseTransport = CompletableDeferred<Unit>()
        val fake = Fake().apply {
            afterStartWrite = {
                withContext(NonCancellable) {
                    transportHeld.complete(Unit)
                    releaseTransport.await()
                }
                currentCoroutineContext().ensureActive()
            }
        }
        val factory = { ClientOperations(fake, 3, 1) }
        var show by mutableStateOf(true)
        var generation by mutableStateOf("g1")
        var profile by mutableStateOf("profile-a")
        rule.setContent {
            ChunksTheme {
                if (show) ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "Seedbox qB", sourceClientId = profile,
                    observedStarted = false, current = true,
                    createOperations = factory, onChanged = {},
                    sourceGeneration = generation, receiptOwner = owner
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).performClick()
        val key = ProtocolOperationReceiptOwner.Key("profile-a", hash)
        try {
            rule.waitUntil(5_000) { transportHeld.isCompleted }
            rule.runOnIdle {
                assertEquals(ProtocolOperationReceiptOwner.Phase.POSSIBLE_WRITE, owner.receipt(key)?.phase)
                show = false
            }
            rule.onNodeWithTag(ChunksSemantics.PROTOCOL_OPERATIONS).assertDoesNotExist()
            rule.runOnIdle { generation = "g2"; show = true }
            rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).assertIsNotEnabled()
            rule.onNodeWithText("Check this transfer without changing it").assertDoesNotExist()
            assertEquals(ProtocolOperationReceiptOwner.Phase.POSSIBLE_WRITE, owner.receipt(key)?.phase)
        } finally {
            releaseTransport.complete(Unit)
        }
        rule.waitUntil(5_000) { owner.receipt(key)?.phase == ProtocolOperationReceiptOwner.Phase.UNKNOWN }
        rule.onNodeWithText("Commands are paused until a read-only check.").assertExists()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).assertIsNotEnabled()
        rule.runOnIdle { profile = "profile-b" }
        rule.waitForIdle()
        rule.onNodeWithText("Commands are paused until a read-only check.").assertDoesNotExist()
        rule.runOnIdle { profile = "profile-a" }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).assertIsNotEnabled()
        rule.onNodeWithText("Check this transfer without changing it").performClick()
        rule.waitUntil(5_000) { owner.receipt(key)?.phase == ProtocolOperationReceiptOwner.Phase.RECONCILED_PRESENT }
        rule.onNodeWithText("Commands are paused until a read-only check.").assertDoesNotExist()
        assertEquals("Read-only reconciliation and navigation never repeat the write", 1, fake.writes)
    }

    @Test fun policyLeaseTemporarilyDisablesManualCommandsWithoutOfferingReconciliation() {
        val owner = ProtocolOperationReceiptOwner()
        val fake = Fake()
        val lease = requireNotNull(owner.reservePolicy("profile-a", hash, ProtocolOperationKind.SMART_SEEDING))
        rule.setContent {
            ChunksTheme {
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "Seedbox qB", sourceClientId = "profile-a",
                    observedStarted = false, current = true,
                    createOperations = { ClientOperations(fake, 3, 1) }, onChanged = {},
                    sourceGeneration = "g1", receiptOwner = owner
                )
            }
        }
        rule.onNodeWithText("Smart Seeding is checking this transfer.").assertExists()
        rule.onNodeWithText("Check this transfer without changing it").assertDoesNotExist()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).assertIsNotEnabled()
        rule.runOnIdle { lease.close() }
        rule.onNodeWithText("Smart Seeding is checking this transfer.").assertDoesNotExist()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).assertIsEnabled().performClick()
        rule.waitUntil(5_000) { fake.writes == 1 }
    }

    @Test fun removeKeepsFilesUnlessExplicitlyChosen() {
        val fake = Fake()
        render(fake)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_REMOVE).performClick()
        rule.onNodeWithText("Remove, keep files").assertExists()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_REMOVE_CONFIRM).performClick()
        rule.waitUntil(5_000) { !fake.present }
        rule.waitForIdle()
        rule.onNodeWithText("Remove: confirmed on Seedbox qB.").assertExists()
    }

    @Test fun dataRemovalReportsRegistrationSeparatelyFromUnverifiedPayload() {
        val fake = Fake()
        render(fake)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_REMOVE).performClick()
        rule.onNodeWithText("Also delete its downloaded files on the server").performClick()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_REMOVE_CONFIRM).performClick()
        rule.waitUntil(5_000) { !fake.present }
        rule.waitForIdle()
        rule.onNodeWithText("Transfer removed from Seedbox qB. Downloaded-file deletion was requested but could not be independently verified. Check the files on the server.").assertExists()
        rule.onNodeWithText("Remove and delete files: confirmed on Seedbox qB.").assertDoesNotExist()
        assertEquals(1, fake.writes)
    }

    @Test fun staleListOffersNoCommands() {
        val fake = Fake()
        render(fake, current = false)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).assertIsNotEnabled()
        assertEquals(0, fake.writes)
    }

    @Test fun changeFolderShowsTheCurrentFolderDefaultsToNoFileMoveAndVerifiesByReadback() {
        val fake = Fake()
        render(fake)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION).performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasText("Current folder on Seedbox qB: /d", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The same folder, or a relative one, can't be confirmed: nothing is sent.
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION_FOLDER).performTextReplacement("/d/")
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION_FOLDER).performTextReplacement("relative/x")
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION_FOLDER).performTextReplacement("/srv/new")
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION_CONFIRM).performClick()
        rule.waitUntil(5_000) { fake.dir == "/srv/new" }
        rule.waitForIdle()
        assertEquals("files are only moved on explicit request", false, fake.movedData)
        assertEquals(1, fake.writes)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_OPERATION_STATUS).assertTextContains("confirmed", substring = true)
    }

    @Test fun moveOnlyPanelCostsNoServerCallUntilCopyOrMoveIsTapped() {
        // Regression: an eager per-view (and per-recomposition) session read on
        // rTorrent Details starved the next remote Add in the installed journey.
        val source = Fake()
        val created = java.util.concurrent.atomic.AtomicInteger()
        rule.setContent {
            ChunksTheme {
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "rTorrent", sourceClientId = "rt", observedStarted = false,
                    current = true, sourceGeneration = "fixture-generation", receiptOwner = testReceipts,
                    createOperations = { created.incrementAndGet(); ClientOperations(source, 3, 1) },
                    onChanged = {},
                    moveTargets = listOf(ProtocolMoveTarget("local", "This device") { ClientOperations(Fake(), 3, 1) }),
                    moveOnly = true)
            }
        }
        rule.waitForIdle()
        assertEquals("opening Details reads nothing from the server", 0, source.sessions)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { source.sessions >= 1 }
        rule.waitForIdle()
        assertEquals("one read after the tap, not one per recomposition", 1, source.sessions)
        assertEquals("one adapter for the exact client, not one per recomposition", 1, created.get())
    }

    @Test fun smartRoutingMoveSuggestionIsShownFirstAndNeverActsByItself() {
        val source = Fake(); val destination = Target()
        rule.setContent {
            ChunksTheme {
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "rTorrent", sourceClientId = "rt", observedStarted = false,
                    current = true, sourceGeneration = "fixture-generation", receiptOwner = testReceipts,
                    createOperations = { ClientOperations(source, 3, 1) }, onChanged = {},
                    moveTargets = listOf(ProtocolMoveTarget("other", "Other") { ClientOperations(Fake(), 3, 1) },
                        ProtocolMoveTarget("de", "Seedbox Deluge") { ClientOperations(destination, 3, 1) }),
                    moveOnly = true, suggestedTarget = "de" to "tv episode rule")
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(ChunksSemantics.PROTOCOL_MOVE_SUGGESTION).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_SUGGESTION)
            .assertTextContains("Seedbox Deluge (tv episode rule)", substring = true)
        rule.waitForIdle()
        assertEquals("a suggestion never copies anything", 0, destination.adds)
        org.junit.Assert.assertTrue(source.present)
    }

    @Test fun moveAdviceRequiresTheCurrentDestinationsDefaultAndCapabilities() {
        val source = Fake()
        val verified = Target()
        val noPlace = Target().apply { defaultDirectory = null }
        val noStopped = Target().apply { capabilities -= ClientCapability.ADD_STOPPED }
        val sourceOperations = { ClientOperations(source, 3, 1) }
        var destination by mutableStateOf(verified)
        rule.setContent {
            ChunksTheme {
                val selected = destination
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "Source", sourceClientId = "source", observedStarted = false,
                    current = true, sourceGeneration = "fixture-generation", receiptOwner = testReceipts,
                    createOperations = sourceOperations, onChanged = {},
                    moveTargets = listOf(ProtocolMoveTarget("same-id", "Destination",
                        connectionWitness = selected) { ClientOperations(selected, 3, 1) }),
                    suggestedTarget = "same-id" to "tv episode rule"
                )
            }
        }
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        val suggestion = rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_SUGGESTION)
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(ChunksSemantics.PROTOCOL_MOVE_SUGGESTION).fetchSemanticsNodes().isNotEmpty()
        }
        for (unsupported in listOf(noPlace, noStopped)) {
            rule.runOnIdle { destination = unsupported }
            val manualTarget = rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".same-id")
            // Read Compose semantics after replacing the connection so the
            // recomposition starts the new probe before we await its result.
            manualTarget.assertExists()
            rule.waitUntil(5_000) { unsupported.sessionReads.get() > 0 }
            suggestion.assertDoesNotExist()
            assertEquals("one read for the replacement connection", 1, unsupported.sessionReads.get())
        }
        assertEquals(0, source.writes)
        assertEquals(0, verified.adds + noPlace.adds + noStopped.adds)
    }

    @Test fun openingMoveRefinesTheSuggestionFromTheJobsOwnFilesWithoutWriting() {
        val source = Fake(); val destination = Target()
        val sourceOperations = { ClientOperations(source, 3, 1) }
        var destinationCurrent by mutableStateOf(true)
        var metadataPending by mutableStateOf(true)
        rule.setContent {
            ChunksTheme {
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "rTorrent", sourceClientId = "rt", observedStarted = false,
                    current = true, sourceGeneration = "fixture-generation", receiptOwner = testReceipts,
                    createOperations = sourceOperations, onChanged = {},
                    moveTargets = listOf(ProtocolMoveTarget("other", "Other") { ClientOperations(Fake(), 3, 1) },
                        ProtocolMoveTarget("de", "Seedbox Deluge") { ClientOperations(destination, 3, 1) }),
                    moveOnly = true, suggestedTarget = null,
                    routingMetadataPending = metadataPending,
                    suggestForFiles = { names ->
                        if (destinationCurrent && names == listOf("a")) "de" to "book rule" else null
                    })
            }
        }
        rule.waitForIdle()
        assertEquals("nothing is read before Copy/Move is opened", 0, source.fileReads)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        val suggestion = rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_SUGGESTION)
        suggestion.assertDoesNotExist()
        assertEquals("an open Move review must wait for known pending metadata", 0, source.fileReads)
        rule.runOnIdle { metadataPending = false }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(ChunksSemantics.PROTOCOL_MOVE_SUGGESTION).fetchSemanticsNodes().isNotEmpty()
        }
        suggestion.assertTextContains("Seedbox Deluge (book rule)", substring = true)

        rule.runOnIdle { destinationCurrent = false }
        suggestion.assertDoesNotExist()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".de").assertExists()
        rule.runOnIdle { destinationCurrent = true }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(ChunksSemantics.PROTOCOL_MOVE_SUGGESTION).fetchSemanticsNodes().isNotEmpty()
        }
        suggestion.assertTextContains("Seedbox Deluge (book rule)", substring = true)
        assertEquals("target refreshes reuse the exact job's file read", 1, source.fileReads)
        assertEquals("reading files writes nothing", 0, source.writes)
        assertEquals(0, destination.adds)
    }
}
