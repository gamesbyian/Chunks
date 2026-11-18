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
        var writes = 0
        var failWrite: Exception? = null
        var applyWrite: (Fake) -> Unit = {}
        var failReads = false
        var files = listOf(ClientFile(0, "a", 1, 0, ClientFilePriority.NORMAL),
            ClientFile(1, "b", 1, 0, ClientFilePriority.NORMAL))

        override suspend fun session() = ClientSessionInfo(kind, "1", "/d", caps, levels)
        override suspend fun torrents() = listOfNotNull(present)
        var reads = 0
        var onRead: (Fake) -> Unit = {}
        override suspend fun torrent(hash: String): ClientTorrent? {
            reads++
            onRead(this)
            if (failReads) throw IOException("read failed")
            return present?.takeIf { it.hash == hash }
        }
        override suspend fun files(hash: String) = files
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

    @Test fun anyUnexpectedWriteFailureIsUncertainNotRefused() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply { failWrite = IOException("socket") }
        assertTrue(ops(fake).setStarted(hash, true) is ClientOperationOutcome.Uncertain)
        assertEquals(1, fake.writes)
    }

    @Test fun definiteRefusalIsRefused() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply { failWrite = ClientRefusedException("no") }
        assertTrue(ops(fake).setStarted(hash, true) is ClientOperationOutcome.Refused)
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
        val outcome = ops(fake).add(ClientAddRequest(source, null, startNow = true, skippedFiles = mapOf(1 to "other")))
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

    @Test fun layoutMatchIgnoresOnlyTheTopFolderPrefix() {
        val files = listOf(ClientFile(0, "Show/s1/e1.mkv", 1, 0, ClientFilePriority.NORMAL))
        assertEquals(listOf(0), ClientFileLayout.match(files, mapOf(0 to "s1/e1.mkv")))
        assertEquals(listOf(0), ClientFileLayout.match(files, mapOf(0 to "Show/s1/e1.mkv")))
        assertEquals(null, ClientFileLayout.match(files, mapOf(0 to "s2/e1.mkv")))
        assertEquals(null, ClientFileLayout.match(files, mapOf(1 to "s1/e1.mkv")))
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
