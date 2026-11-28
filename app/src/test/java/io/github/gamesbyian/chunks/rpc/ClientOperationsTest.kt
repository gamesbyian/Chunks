package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.RemoteBackendKind
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientOperationsTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    private class Fake(
        var present: ClientTorrent?,
        val caps: Set<ClientCapability> = ClientCapability.entries.toSet(),
        val levels: Set<ClientFilePriority> = ClientFilePriority.entries.toSet()
    ) : TorrentClient {
        override val kind = TorrentClientKind.TRANSMISSION
        override var verifiesPayloadDeletionOnReturn = false
        override var provesNoSharedPayloadBeforeDelete = false
        /** Other jobs in the same client, each with the file list the client reports. */
        var siblings: Map<ClientTorrent, List<ClientFile>> = emptyMap()
        var failFilesFor: String? = null
        var writes = 0
        var failWrite: Exception? = null
        var applyWrite: (Fake) -> Unit = {}
        var failReads = false
        var files = listOf(ClientFile(0, "a", 1, 0, ClientFilePriority.NORMAL),
            ClientFile(1, "b", 1, 0, ClientFilePriority.NORMAL))

        override suspend fun session() = ClientSessionInfo(kind, "1", "/d", caps, levels)
        var inventoryReads = 0
        var onInventory: (Fake) -> Unit = {}
        override suspend fun torrents(): List<ClientTorrent> {
            inventoryReads++
            onInventory(this)
            return listOfNotNull(present) + siblings.keys
        }
        var fileReads = 0
        var onFiles: (Fake) -> Unit = {}
        var reads = 0
        var onRead: (Fake) -> Unit = {}
        override suspend fun torrent(hash: String): ClientTorrent? {
            reads++
            onRead(this)
            if (failReads) throw IOException("read failed")
            return present?.takeIf { it.hash == hash }
        }
        override suspend fun files(hash: String): List<ClientFile> {
            fileReads++
            onFiles(this)
            if (hash == failFilesFor) throw IOException("files unavailable")
            return siblings.entries.firstOrNull { it.key.hash == hash }?.value ?: files
        }
        private fun write() {
            writes++
            failWrite?.let { throw it }
            applyWrite(this)
        }
        override suspend fun add(request: ClientAddRequest) = write()
        override suspend fun setStarted(hash: String, started: Boolean) = write()
        override suspend fun remove(hash: String, deleteData: Boolean) = write()
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) {
            write()
            files = files.map { f -> priorities[f.index]?.let { f.copy(priority = it) } ?: f }
        }
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = write()
        override suspend fun recheck(hash: String) = write()
    }

    private fun torrent(state: ClientRunState, dir: String = "/d") =
        ClientTorrent(hash, "n", state, 1, 0, 0, 0, dir)

    private fun ops(fake: Fake) = ClientOperations(fake, readbackAttempts = 3, readbackDelayMillis = 1)

    @Test fun lostWriteIsUncertainAndNeverReplayed() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply {
            failWrite = ClientWriteUncertainException("lost")
        }
        val outcome = ops(fake).setStarted(hash, true)
        assertTrue(outcome is ClientOperationOutcome.Uncertain)
        assertEquals(1, fake.writes)
    }

    /** Invariant control `write-swallows-cancellation`: cancellation is never turned into an outcome. */
    @Test fun cancellationDuringAWriteIsRethrownNeverReportedAsAnOutcomeOrReplayed() {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply {
            failWrite = kotlinx.coroutines.CancellationException("cancelled after dispatch")
        }
        var outcome: ClientOperationOutcome? = null
        val thrown = try {
            runBlocking { outcome = ops(fake).setStarted(hash, true) }
            null
        } catch (cancel: kotlinx.coroutines.CancellationException) { cancel }
        assertTrue("cancellation propagated: $outcome", thrown != null)
        assertEquals("no outcome was produced for a cancelled write", null, outcome)
        assertEquals("never replayed", 1, fake.writes)
    }

    @Test fun anyUnexpectedWriteFailureIsUncertainNotRefused() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply { failWrite = IOException("socket") }
        assertTrue(ops(fake).setStarted(hash, true) is ClientOperationOutcome.Uncertain)
        assertEquals(1, fake.writes)
    }

    @Test fun definiteRefusalIsRefused() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply { failWrite = ClientRefusedException("no") }
        assertTrue(ops(fake).setStarted(hash, true) is ClientOperationOutcome.Refused)
    }

    @Test fun writeBoundaryRunsAfterExactPreflightAndCanRefuseBeforeAdapterEntry() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED))
        var checks = 0
        val outcome = kotlinx.coroutines.withContext(ClientOperationWriteBoundary {
            checks++
            assertEquals(1, fake.reads)
            throw ClientRefusedException("Connection changed")
        }) { ops(fake).setStarted(hash, true) }
        assertTrue(outcome is ClientOperationOutcome.Refused)
        assertEquals(1, checks)
        assertEquals(0, fake.writes)
        val absent = Fake(null)
        val refused = kotlinx.coroutines.withContext(ClientOperationWriteBoundary { checks++ }) {
            ops(absent).setStarted(hash, true)
        }
        assertTrue(refused is ClientOperationOutcome.Refused)
        assertEquals(1, checks)
        assertEquals(0, absent.writes)
    }

    @Test fun absentTargetSendsNoWrite() = runBlocking {
        val fake = Fake(null)
        assertTrue(ops(fake).remove(hash, deleteData = true) is ClientOperationOutcome.Refused)
        assertEquals(0, fake.writes)
    }

    @Test fun unreadablePreconditionSendsNoWrite() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply { failReads = true }
        assertTrue(ops(fake).setStarted(hash, true) is ClientOperationOutcome.Refused)
        assertEquals(0, fake.writes)
    }

    @Test fun acknowledgedButUnobservedIsPendingNotVerified() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED))
        val outcome = ops(fake).setStarted(hash, true)
        assertTrue(outcome is ClientOperationOutcome.Pending)
        assertEquals(1, fake.writes)
    }

    @Test fun readbackUnavailableAfterWriteIsUncertain() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply { applyWrite = { it.failReads = true } }
        assertTrue(ops(fake).setStarted(hash, true) is ClientOperationOutcome.Uncertain)
    }

    @Test fun queuedFilesystemCleanupIsPendingNotVerifiedBySessionAbsence() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply {
            // A ruTorrent erasedata plugin may accept cleanup and remove the
            // session before the backing files have actually been deleted.
            applyWrite = {
                it.present = null
                throw ClientPostWritePendingException(
                    "ruTorrent queued cleanup; downloaded-file deletion not verified")
            }
        }
        val result = ops(fake).remove(hash, deleteData = true)
        assertTrue(result is ClientOperationOutcome.Pending)
        assertEquals(1, fake.writes)
    }

    @Test fun remoteRemovalAckAndExactAbsenceDoNotVerifyPayloadDeletion() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply { applyWrite = { it.present = null } }
        val outcome = ops(fake).remove(hash, deleteData = true)
        assertTrue(outcome is ClientOperationOutcome.Pending)
        assertTrue((outcome as ClientOperationOutcome.Pending).registrationAbsent)
        assertEquals(1, fake.writes)
        assertEquals(2, fake.reads)
    }

    private val otherHash = "fedcba9876543210fedcba9876543210fedcba98"
    private fun file(path: String, index: Int = 0) = ClientFile(index, path, 1, 0, ClientFilePriority.NORMAL)
    private fun sibling(dir: String?, vararg paths: String) =
        ClientTorrent(otherHash, "other", ClientRunState.STOPPED, 1, 0, 0, 0, dir) to paths.mapIndexed { i, p -> file(p, i) }

    private fun sharedFake(vararg siblings: Pair<ClientTorrent, List<ClientFile>>) =
        Fake(torrent(ClientRunState.STOPPED)).apply {
            files = listOf(file("n/a.bin", 0), file("n/b.bin", 1))
            this.siblings = siblings.toMap()
            applyWrite = { it.present = null }
        }

    @Test fun reviewedPayloadDeletionRefusesWhenAnotherJobListsOneOfTheSameFiles() = runBlocking {
        for (otherDir in listOf("/d", "/d/", "/d/n", "/")) {
            val shared = when (otherDir) { "/d/n" -> "b.bin"; "/" -> "d/n/B.BIN"; else -> "n/B.BIN" }
            val fake = sharedFake(sibling(otherDir, shared))
            val outcome = ops(fake).removeReviewedPayload(hash, "/d")
            assertTrue("$otherDir: $outcome", outcome is ClientOperationOutcome.Refused)
            assertEquals(ClientPayloadOverlap.SHARED, (outcome as ClientOperationOutcome.Refused).reason)
            assertEquals(0, fake.writes)
            assertEquals(hash, fake.present?.hash)
        }
    }

    @Test fun reviewedPayloadDeletionProceedsOnceWhenSiblingsAreProvablyDisjoint() = runBlocking {
        val elsewhere = ClientTorrent("22" + otherHash.drop(2), "elsewhere", ClientRunState.STOPPED, 1, 0, 0, 0,
            "/elsewhere") to listOf(file("n/a.bin"))
        val fake = sharedFake(sibling("/d", "n/other.bin"), elsewhere)
        val outcome = ops(fake).removeReviewedPayload(hash, "/d")
        assertTrue(outcome.toString(), outcome is ClientOperationOutcome.Pending)
        assertTrue((outcome as ClientOperationOutcome.Pending).registrationAbsent)
        assertEquals(1, fake.writes)
    }

    @Test fun reviewedPayloadDeletionRefusesWhenAnySiblingCannotBeInterpreted() = runBlocking {
        val cases = listOf<Pair<String, (Fake) -> Unit>>(
            "unreported folder" to { it.siblings = mapOf(sibling(null, "x")) },
            "relative folder" to { it.siblings = mapOf(sibling("downloads", "x")) },
            "dot-segment folder" to { it.siblings = mapOf(sibling("/d/../x", "x")) },
            "escaping file path" to { it.siblings = mapOf(sibling("/d", "../n/a.bin")) },
            "absolute file path" to { it.siblings = mapOf(sibling("/d", "/n/a.bin")) },
            "unreadable file list" to { it.siblings = mapOf(sibling("/d", "x")); it.failFilesFor = otherHash },
            "unsafe target file" to { it.files = listOf(file("../x")) }
        )
        for ((label, arrange) in cases) {
            val fake = sharedFake().apply(arrange)
            val outcome = ops(fake).removeReviewedPayload(hash, "/d")
            assertTrue("$label: $outcome", outcome is ClientOperationOutcome.Refused)
            assertEquals(label, ClientPayloadOverlap.UNREADABLE, (outcome as ClientOperationOutcome.Refused).reason)
            assertEquals(label, 0, fake.writes)
        }
    }

    @Test fun reviewedPayloadDeletionRefusesWhenTheInventoryChangesDuringTheCheck() = runBlocking {
        val fake = sharedFake(sibling("/d", "n/other.bin"))
        fake.onFiles = { f ->
            // The sibling read is the second file read; a job appears before the confirming inventory read.
            if (f.fileReads == 2) f.siblings = f.siblings + (
                ClientTorrent("11" + otherHash.drop(2), "new", ClientRunState.STOPPED, 1, 0, 0, 0, "/d") to
                    listOf(file("n/a.bin")))
        }
        val outcome = ops(fake).removeReviewedPayload(hash, "/d")
        assertTrue(outcome.toString(), outcome is ClientOperationOutcome.Refused)
        assertEquals(ClientPayloadOverlap.CHANGED, (outcome as ClientOperationOutcome.Refused).reason)
        assertEquals(0, fake.writes)
    }

    @Test fun reviewedPayloadDeletionRefusesWhenFirstInventoryDisagreesWithReviewedRoot() = runBlocking {
        val fake = sharedFake(sibling("/moved", "n/a.bin"))
        fake.onInventory = { f ->
            f.present = f.present?.copy(downloadDirectory = "/moved")
        }
        val outcome = ops(fake).removeReviewedPayload(hash, "/d")
        assertTrue(outcome is ClientOperationOutcome.Refused)
        assertEquals(ClientPayloadOverlap.CHANGED, (outcome as ClientOperationOutcome.Refused).reason)
        assertEquals(0, fake.writes)
    }

    @Test fun reviewedPayloadDeletionRefusesSiblingRenameWithoutInventoryChange() = runBlocking {
        for (renamed in listOf("n/a.bin", "n/different.bin")) {
            val fake = sharedFake(sibling("/d", "n/other.bin"))
            fake.onFiles = { f ->
                if (f.fileReads == 3) f.siblings = mapOf(sibling("/d", renamed))
            }
            val outcome = ops(fake).removeReviewedPayload(hash, "/d")
            assertTrue("Sibling mapping changed: $outcome", outcome is ClientOperationOutcome.Refused)
            assertEquals(if (renamed == "n/a.bin") ClientPayloadOverlap.SHARED else ClientPayloadOverlap.CHANGED,
                (outcome as ClientOperationOutcome.Refused).reason)
            assertEquals(0, fake.writes)
        }
    }

    @Test fun reviewedPayloadDeletionRefusesInventoryChangeDuringFinalFileRead() = runBlocking {
        val fake = sharedFake(sibling("/d", "n/other.bin"))
        fake.onFiles = { f ->
            if (f.fileReads == 4) f.present = f.present?.copy(downloadDirectory = "/moved")
        }
        val outcome = ops(fake).removeReviewedPayload(hash, "/d")
        assertTrue(outcome is ClientOperationOutcome.Refused)
        assertEquals(ClientPayloadOverlap.CHANGED, (outcome as ClientOperationOutcome.Refused).reason)
        assertEquals(0, fake.writes)
    }

    @Test fun reviewedPayloadDeletionTreatsCaseAliasedDirectoryAsPossibleOverlap() = runBlocking {
        val fake = sharedFake(sibling("/D/N", "A.BIN"))
        val outcome = ops(fake).removeReviewedPayload(hash, "/d")
        assertTrue(outcome is ClientOperationOutcome.Refused)
        assertEquals(ClientPayloadOverlap.SHARED, (outcome as ClientOperationOutcome.Refused).reason)
        assertEquals(0, fake.writes)
    }

    @Test fun reviewedPayloadDeletionRefusesIncompleteOrAmbiguousPathEvidence() = runBlocking {
        val cases = listOf<Pair<String, (Fake) -> Unit>>(
            "empty target roster" to { it.files = emptyList() },
            "empty nested sibling roster" to { it.siblings = mapOf(sibling("/d")) },
            "interior repeated separator" to { it.siblings = mapOf(sibling("/d//n", "a.bin")) },
            "ambiguous network-style root" to { it.siblings = mapOf(sibling("//d/n", "a.bin")) },
            "backslash" to { it.siblings = mapOf(sibling("/d", "n\\\\a.bin")) },
            "duplicate sibling hash" to {
                val first = sibling("/d", "x")
                it.siblings = mapOf(first, first.first.copy(downloadDirectory = "/elsewhere") to first.second)
            },
            "duplicate target index" to { it.files = listOf(file("a"), file("b")) }
        )
        for ((label, arrange) in cases) {
            val fake = sharedFake().apply(arrange)
            val outcome = ops(fake).removeReviewedPayload(hash, "/d")
            assertTrue("$label: $outcome", outcome is ClientOperationOutcome.Refused)
            assertEquals(label, ClientPayloadOverlap.UNREADABLE, (outcome as ClientOperationOutcome.Refused).reason)
            assertEquals(label, 0, fake.writes)
        }
    }

    @Test fun adaptersThatProveNoSharingThemselvesAreNotReadTwiceAndJobOnlyRemovalNeverScans() = runBlocking {
        val own = sharedFake(sibling("/d", "n/a.bin")).apply { provesNoSharedPayloadBeforeDelete = true }
        assertTrue(ops(own).removeReviewedPayload(hash, "/d") is ClientOperationOutcome.Pending)
        assertEquals(1, own.writes)
        assertEquals(0, own.fileReads)
        val jobOnly = sharedFake(sibling("/d", "n/a.bin"))
        assertTrue(ops(jobOnly).remove(hash, deleteData = false) is ClientOperationOutcome.Verified)
        assertEquals(0, jobOnly.fileReads)
    }

    @Test fun sessionOnlyOrIndependentlyVerifiedPayloadRemovalCanBeConfirmed() = runBlocking {
        val jobOnly = Fake(torrent(ClientRunState.STOPPED)).apply { applyWrite = { it.present = null } }
        assertTrue(ops(jobOnly).remove(hash, deleteData = false) is ClientOperationOutcome.Verified)
        val proven = Fake(torrent(ClientRunState.STOPPED)).apply {
            verifiesPayloadDeletionOnReturn = true
            applyWrite = { it.present = null }
        }
        assertTrue(ops(proven).remove(hash, deleteData = true) is ClientOperationOutcome.Verified)
        assertEquals(1, jobOnly.writes)
        assertEquals(1, proven.writes)
    }

    @Test fun observedPostconditionIsVerified() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply {
            applyWrite = { it.present = torrent(ClientRunState.DOWNLOADING) }
        }
        assertTrue(ops(fake).setStarted(hash, true) is ClientOperationOutcome.Verified)
    }

    @Test fun startRequestMayNotBeVerifiedByQueuedOrUnknownState() = runBlocking {
        for (state in listOf(ClientRunState.QUEUED, ClientRunState.CHECKING,
            ClientRunState.UNKNOWN, ClientRunState.ERROR)) {
            val fake = Fake(torrent(ClientRunState.STOPPED)).apply {
                applyWrite = { it.present = torrent(state) }
            }
            val outcome = ops(fake).setStarted(hash, started = true)
            assertTrue("No observed Start for $state: $outcome",
                outcome is ClientOperationOutcome.Pending)
            assertEquals(1, fake.writes)
        }
    }

    @Test fun startedAddCannotBeVerifiedIfJobIsRegisteredStopped() = runBlocking {
        val fake = Fake(null).apply { applyWrite = { it.present = torrent(ClientRunState.STOPPED) } }
        val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")
        val outcome = ops(fake).add(ClientAddRequest(magnet, "/d", true))
        assertTrue(outcome is ClientOperationOutcome.Pending)
        assertEquals(1, fake.writes)
    }

    @Test fun startedAddIsPendingWhileQueuedOrChecking() = runBlocking {
        for (state in listOf(ClientRunState.QUEUED, ClientRunState.CHECKING,
            ClientRunState.UNKNOWN, ClientRunState.ERROR)) {
            val fake = Fake(null).apply { applyWrite = { it.present = torrent(state) } }
            val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")
            val outcome = ops(fake).add(ClientAddRequest(magnet, "/d", true))
            assertTrue("No proven active Add for $state: $outcome",
                outcome is ClientOperationOutcome.Pending)
            assertEquals(1, fake.writes)
        }
    }

    @Test fun startedAddCanBeVerifiedWithObservedDownloadingJob() = runBlocking {
        val fake = Fake(null).apply { applyWrite = { it.present = torrent(ClientRunState.DOWNLOADING) } }
        val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")
        assertTrue(ops(fake).add(ClientAddRequest(magnet, "/d", true)) is
            ClientOperationOutcome.Verified)
        assertEquals(1, fake.writes)
    }

    @Test fun missingCapabilityRefusesWithoutWrite() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED), caps = setOf(ClientCapability.REMOVE))
        assertTrue(ops(fake).remove(hash, deleteData = true) is ClientOperationOutcome.Refused)
        assertEquals(0, fake.writes)
    }

    @Test fun duplicateAddRefusedBeforeWrite() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED))
        val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")
        assertTrue(ops(fake).add(ClientAddRequest(magnet, "/d", false)) is ClientOperationOutcome.Refused)
        assertEquals(0, fake.writes)
    }

    @Test fun addInWrongDirectoryIsNotVerified() = runBlocking {
        val fake = Fake(null).apply { applyWrite = { it.present = torrent(ClientRunState.STOPPED, "/other") } }
        val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")
        val outcome = ops(fake).add(ClientAddRequest(magnet, "/d/", false))
        assertTrue(outcome is ClientOperationOutcome.Pending)
    }

    @Test fun stoppedAddWaitsForSettledStop() = runBlocking {
        val fake = Fake(null).apply { applyWrite = { it.present = torrent(ClientRunState.CHECKING) } }
        val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")
        assertTrue(ops(fake).add(ClientAddRequest(magnet, "/d", false)) is ClientOperationOutcome.Pending)
    }

    @Test fun prioritiesNormalizeToClientLevels() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED),
            levels = setOf(ClientFilePriority.SKIP, ClientFilePriority.NORMAL, ClientFilePriority.HIGH))
        val outcome = ops(fake).setFilePriorities(hash, mapOf(0 to ClientFilePriority.LOW, 1 to ClientFilePriority.SKIP))
        assertTrue(outcome is ClientOperationOutcome.Verified)
        assertEquals(ClientFilePriority.NORMAL, fake.files[0].priority)
    }

    @Test fun unknownFileIndexRefusedBeforeWrite() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED))
        assertTrue(ops(fake).setFilePriorities(hash, mapOf(9 to ClientFilePriority.SKIP)) is ClientOperationOutcome.Refused)
        assertEquals(0, fake.writes)
    }

    @Test fun magnetBase32AndHexAgree() {
        val hex = ClientHashes.magnetInfoHash("magnet:?xt=urn:btih:$hash&dn=x")
        assertEquals(hash, hex)
        val b32 = ClientHashes.magnetInfoHash("magnet:?xt=urn:btih:AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH")
        assertEquals("0123456789abcdef0123456789abcdef01234567", b32)
    }

    @Test fun qbittorrentStatesMapExplicitly() {
        assertEquals(ClientRunState.STOPPED, QBittorrentClient.stateOf("stoppedUP"))
        assertEquals(ClientRunState.STOPPED, QBittorrentClient.stateOf("pausedDL"))
        assertEquals(ClientRunState.METADATA, QBittorrentClient.stateOf("metaDL"))
        assertEquals(ClientRunState.UNKNOWN, QBittorrentClient.stateOf("newState"))
    }

    private val torrentBytes: ByteArray by lazy {
        io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.build(
            io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.Request("n", listOf(
                io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.Entry(listOf("a"), 3) { "abc".byteInputStream() },
                io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.Entry(listOf("b"), 3) { "def".byteInputStream() }),
                pieceLength = 16 * 1024)).torrent
    }

    private fun fileChoiceFake(): Pair<Fake, ClientAddSource.TorrentFile> {
        val source = ClientAddSource.TorrentFile(torrentBytes)
        // A remote client without native Add-time file choices: the gated multi-step path.
        val fake = Fake(null, caps = ClientCapability.entries.toSet() - ClientCapability.ADD_WITH_FILE_CHOICES)
        fake.files = listOf(ClientFile(0, "n/a", 3, 0, ClientFilePriority.NORMAL),
            ClientFile(1, "n/b", 3, 0, ClientFilePriority.NORMAL))
        var started = false
        fake.applyWrite = { f ->
            f.present = ClientTorrent(source.infoHash, "n", if (started) ClientRunState.DOWNLOADING else ClientRunState.STOPPED,
                6, 0, 0, 0, "/d")
            started = true // the 1st write is the stopped Add; any later lifecycle write starts it
        }
        return fake to source
    }

    @Test fun fileChoicesAddStoppedThenSkipThenStartEachVerified() = runBlocking {
        val (fake, source) = fileChoiceFake()
        val outcome = ops(fake).add(ClientAddRequest(source, null, startNow = true, skippedFiles = mapOf(1 to "b")))
        assertTrue("$outcome", outcome is ClientOperationOutcome.Verified)
        assertEquals(ClientFilePriority.SKIP, fake.files.single { it.index == 1 }.priority)
        assertEquals(ClientFilePriority.NORMAL, fake.files.single { it.index == 0 }.priority)
        assertEquals("Add, skips, Start: three writes, none repeated", 3, fake.writes)
    }

    @Test fun fileLayoutMismatchLeavesTheJobStoppedWithNoSkipOrStart() = runBlocking {
        val (fake, source) = fileChoiceFake()
        fake.files = fake.files.map { if (it.index == 1) it.copy(path = "unrelated/n/b") else it }
        val outcome = ops(fake).add(ClientAddRequest(source, null, startNow = true, skippedFiles = mapOf(1 to "b")))
        assertTrue("$outcome", outcome is ClientOperationOutcome.Pending)
        assertEquals("Only the stopped Add was written", 1, fake.writes)
        assertEquals(ClientRunState.STOPPED, fake.present?.runState)
    }

    @Test fun fileChoicesNeedPriorityCapabilityAndSendNothingOtherwise() = runBlocking {
        val source = ClientAddSource.TorrentFile(torrentBytes)
        val fake = Fake(null, caps = ClientCapability.entries.toSet() - ClientCapability.FILE_PRIORITIES)
        val outcome = ops(fake).add(ClientAddRequest(source, null, startNow = false, skippedFiles = mapOf(1 to "b")))
        assertTrue(outcome is ClientOperationOutcome.Refused)
        assertEquals(0, fake.writes)
    }

    @Test fun skipAndHighAreOnePriorityWriteBetweenStoppedAddAndStart() = runBlocking {
        val (fake, source) = fileChoiceFake()
        val outcome = ops(fake).add(ClientAddRequest(source, null, startNow = true,
            skippedFiles = mapOf(1 to "b"),
            filePriorities = mapOf(0 to ReviewedFilePriority("a", ClientFilePriority.HIGH))))
        assertTrue("$outcome", outcome is ClientOperationOutcome.Verified)
        assertEquals(ClientFilePriority.HIGH, fake.files.single { it.index == 0 }.priority)
        assertEquals(ClientFilePriority.SKIP, fake.files.single { it.index == 1 }.priority)
        assertEquals("Add, ONE priorities write, Start", 3, fake.writes)
    }

    @Test fun aLevelTheClientCannotStoreIsRefusedBeforeAnyWrite() = runBlocking {
        val source = ClientAddSource.TorrentFile(torrentBytes)
        // rTorrent, qBittorrent and Deluge store no distinct Low.
        val fake = Fake(null, caps = ClientCapability.entries.toSet() - ClientCapability.ADD_WITH_FILE_CHOICES,
            levels = setOf(ClientFilePriority.SKIP, ClientFilePriority.NORMAL, ClientFilePriority.HIGH))
        val outcome = ops(fake).add(ClientAddRequest(source, null, startNow = true,
            filePriorities = mapOf(0 to ReviewedFilePriority("a", ClientFilePriority.LOW))))
        assertTrue("$outcome", outcome is ClientOperationOutcome.Refused)
        assertEquals("never silently mapped Low to Normal, nothing added", 0, fake.writes)
    }

    @Test fun aFileIsEitherSkippedOrPrioritizedNeverBoth() {
        val source = ClientAddSource.TorrentFile(torrentBytes)
        assertTrue(runCatching {
            ClientAddRequest(source, null, startNow = false, skippedFiles = mapOf(0 to "a"),
                filePriorities = mapOf(0 to ReviewedFilePriority("a", ClientFilePriority.HIGH)))
        }.isFailure)
        assertTrue("Normal is the default, not a reviewed choice",
            runCatching { ReviewedFilePriority("a", ClientFilePriority.NORMAL) }.isFailure)
        assertTrue(runCatching { ReviewedFilePriority("a", ClientFilePriority.SKIP) }.isFailure)
    }

    @Test fun layoutChangingBetweenStoppedAddAndPriorityPreflightCannotWriteOrStart() = runBlocking {
        val (fake, source) = fileChoiceFake()
        fake.onFiles = { f ->
            if (f.fileReads >= 2) f.files = f.files.map {
                if (it.index == 1) it.copy(path = "alien/n/b") else it
            }
        }
        val result = ops(fake).add(ClientAddRequest(source, null, startNow = true,
            skippedFiles = mapOf(1 to "b")))
        assertTrue(result is ClientOperationOutcome.Pending)
        assertEquals("Only the stopped Add was written", 1, fake.writes)
        assertEquals(ClientRunState.STOPPED, fake.present?.runState)
    }

    @Test fun duplicateIndexDuringPriorityReadbackCannotConfirmOrStart() = runBlocking {
        val (fake, source) = fileChoiceFake()
        fake.onFiles = { f ->
            if (f.fileReads == 3) f.files = f.files + f.files.last()
        }
        val result = ops(fake).add(ClientAddRequest(source, null, startNow = true,
            skippedFiles = mapOf(1 to "b")))
        assertTrue(result is ClientOperationOutcome.Pending)
        assertEquals("Stopped Add and one priorities write, no Start or replay", 2, fake.writes)
    }

    @Test fun invalidReviewIsRefusedBeforeAnyRegistration() = runBlocking {
        val (fake, source) = fileChoiceFake()
        val outcome = ops(fake).add(ClientAddRequest(source, null, startNow = true, skippedFiles = mapOf(1 to "other/b")))
        assertTrue("$outcome", outcome is ClientOperationOutcome.Refused)
        assertEquals(0, fake.writes)
    }

    @Test fun layoutMatchUsesDeclaredRootAndCompleteUniqueIndicesAndSizes() {
        val preview = TorrentMetainfoPreview("Show", hash, 2, 3, 16384,
            files = listOf(TorrentMetainfoFile("Show/s1/e1.mkv", 1, false),
                TorrentMetainfoFile("Show/s2/e1.mkv", 2, false)))
        val rows = listOf(ClientFile(0, "Show/s1/e1.mkv", 1, 0, ClientFilePriority.NORMAL),
            ClientFile(1, "Show/s2/e1.mkv", 2, 0, ClientFilePriority.NORMAL))
        val review = mapOf(0 to "s1/e1.mkv")
        fun match(files: List<ClientFile>) = ClientFileLayout.match(files, preview, review)
        assertEquals(listOf(0), match(rows))
        assertEquals(listOf(0), match(rows.map { it.copy(path = it.path.removePrefix("Show/")) }))
        assertEquals(null, match(listOf(rows[0], rows[1].copy(path = "s2/e1.mkv"))))
        assertEquals(null, match(rows + rows[0]))
        assertEquals(null, match(rows.take(1)))
        assertEquals(null, match(rows.map { if (it.index == 0) it.copy(path = "alien/Show/s1/e1.mkv") else it }))
        assertEquals(null, match(rows.map { if (it.index == 0) it.copy(sizeBytes = 2) else it }))
        assertEquals(null, match(rows.map { if (it.index == 0) it.copy(path = "/Show/s1/e1.mkv") else it }))
        assertEquals(null, ClientFileLayout.match(rows, preview, mapOf(0 to "e1.mkv")))
        assertEquals(null, ClientFileLayout.match(rows, preview, mapOf(1 to "s1/e1.mkv")))
    }

    @Test fun paddingMayBeOmittedButNeverRenumberedOrSelected() {
        val preview = TorrentMetainfoPreview("n", hash, 3, 6, 16384, files = listOf(
            TorrentMetainfoFile("n/a", 1, false), TorrentMetainfoFile("n/.pad/2", 2, true),
            TorrentMetainfoFile("n/b", 3, false)))
        val rows = listOf(ClientFile(0, "a", 1, 0, ClientFilePriority.NORMAL),
            ClientFile(2, "b", 3, 0, ClientFilePriority.NORMAL))
        assertEquals(listOf(2), ClientFileLayout.match(rows, preview, mapOf(2 to "b")))
        assertEquals(null, ClientFileLayout.match(rows.map { if (it.index == 2) it.copy(index = 1) else it },
            preview, mapOf(2 to "b")))
        assertEquals(null, ClientFileLayout.match(rows, preview, mapOf(1 to ".pad/2")))
        assertEquals(listOf(2), ClientFileLayout.match(rows + ClientFile(1, ".pad/2", 2, 0, ClientFilePriority.NORMAL),
            preview, mapOf(2 to "b")))
    }

    @Test fun torrentSourceFreezesOriginalAndReturnedBuffers() {
        val input = torrentBytes.copyOf()
        val original = input.copyOf()
        val source = ClientAddSource.TorrentFile(input)
        input.fill(0)
        source.bytes.fill(0)
        org.junit.Assert.assertArrayEquals(original, source.bytes)
        assertEquals(TorrentMetainfoReader.preview(original).infoHashV1, source.infoHash)
    }

    @Test fun nativeAddTimeFileChoicesAreOneWriteThenIndependentlyVerified() = runBlocking {
        val source = ClientAddSource.TorrentFile(torrentBytes)
        val fake = Fake(null)
        fake.files = listOf(ClientFile(0, "n/a", 3, 0, ClientFilePriority.NORMAL),
            ClientFile(1, "n/b", 3, 0, ClientFilePriority.NORMAL))
        fake.applyWrite = { f ->
            f.present = ClientTorrent(source.infoHash, "n", ClientRunState.STOPPED, 6, 0, 0, 0, "/d")
        }
        // The client "forgets" the skip: the shared workflow must not call it verified.
        val forgot = ops(fake).add(ClientAddRequest(source, null, startNow = false, skippedFiles = mapOf(1 to "b")))
        assertTrue("$forgot", forgot is ClientOperationOutcome.Pending)
        assertEquals(1, fake.writes)
        fake.present = null
        fake.applyWrite = { f ->
            f.present = ClientTorrent(source.infoHash, "n", ClientRunState.STOPPED, 6, 0, 0, 0, "/d")
            f.files = f.files.map { if (it.index == 1) it.copy(priority = ClientFilePriority.SKIP) else it }
        }
        val applied = ops(fake).add(ClientAddRequest(source, null, startNow = false, skippedFiles = mapOf(1 to "b")))
        assertTrue("$applied", applied is ClientOperationOutcome.Verified)
        assertEquals(2, fake.writes)
    }

    @Test fun aStoppedAddThatTheClientThenStartsIsNeverVerified() = runBlocking {
        // qBittorrent magnet seen in installed CI: paused at first, then metaDL.
        val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")
        val fake = Fake(null)
        var registeredAt = -1
        fake.applyWrite = { f -> f.present = torrent(ClientRunState.STOPPED); registeredAt = f.reads }
        fake.onRead = { f ->
            if (registeredAt >= 0 && f.reads > registeredAt + 2) f.present = torrent(ClientRunState.METADATA)
        }
        val outcome = ops(fake).add(ClientAddRequest(magnet, null, startNow = false))
        assertTrue("$outcome", outcome is ClientOperationOutcome.Pending)
        assertEquals(ClientRunState.METADATA, (outcome as ClientOperationOutcome.Pending).observed?.runState)
        assertEquals("one Add, never replayed or auto-stopped", 1, fake.writes)
    }

    @Test fun aStableStoppedAddIsVerified() = runBlocking {
        val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")
        val fake = Fake(null).apply { applyWrite = { f -> f.present = torrent(ClientRunState.STOPPED) } }
        assertTrue(ops(fake).add(ClientAddRequest(magnet, null, startNow = false)) is ClientOperationOutcome.Verified)
    }
}
