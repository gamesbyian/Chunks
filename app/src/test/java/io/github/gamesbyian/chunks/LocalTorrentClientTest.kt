package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LoopbackTracker
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientJobTransfer
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.TorrentClientKind
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/**
 * The device's REAL libtorrent engine (desktop libtorrent4j) driven through
 * the one shared [ClientOperations] workflow and job-only [ClientJobTransfer],
 * exactly as the remote engines are. No fakes below the port.
 */
class LocalTorrentClientTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()

    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true
    )).also { it.start(); engines += it }

    private class Device(val engine: LocalTorrentEngine, val place: File, val fence: LocalScreenObservationRevision,
                         val client: LocalTorrentClient, val ops: ClientOperations) {
        /** What the Combined/device screen pump does after each change: a complete fresh list. */
        fun observe() { fence.acceptObservation(fence.snapshot(), engine.list()) }
    }

    private fun device(engine: LocalTorrentEngine = engine(), name: String = "place"): Device {
        val place = temp.newFolder(name).canonicalFile
        val fence = LocalScreenObservationRevision()
        val places = object : LocalClientPlaces {
            override fun defaultDirectory() = place.path
            override fun resolve(directory: String?) =
                if (directory == null) place else LocalClientPlaces.within(listOf(place), directory)
        }
        val client = LocalTorrentClient({ engine }, places = places, fence = LocalWriteFence.of(fence), deleteReadbackTimeoutMs = 20_000)
        return Device(engine, place, fence, client, ClientOperations(client, readbackAttempts = 40, readbackDelayMillis = 250))
    }

    private class Payload(val root: File, val torrent: ByteArray, val files: Map<String, ByteArray>) {
        val hash: String get() = ClientAddSource.TorrentFile(torrent).infoHash
    }

    private fun payload(name: String, tracker: String? = null, seed: Int = 7): Payload {
        val parent = temp.newFolder("src-$name")
        val root = File(parent, name).apply { mkdirs() }
        val random = Random(seed)
        val files = linkedMapOf(
            "a.bin" to ByteArray(300_000).also { random.nextBytes(it) },
            "b/c.bin" to ByteArray(90_123).also { random.nextBytes(it) },
            "d.txt" to "chunks local port fixture".toByteArray()
        )
        for ((path, bytes) in files) File(root, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
        val builder = TorrentBuilder().path(root).pieceSize(32 * 1024).flags(TorrentBuilder.V1_ONLY)
        if (tracker != null) builder.addTracker(tracker)
        return Payload(root, builder.generate().entry().bencode(), files)
    }

    private suspend fun waitFor(timeoutMs: Long = 60_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for engine state" }
            kotlinx.coroutines.delay(100)
        }
    }

    @Test fun sessionReportsLocalCapabilitiesAndVerifiedDefault() = runBlocking {
        val d = device()
        val info = d.ops.session()
        assertEquals(TorrentClientKind.LOCAL, info.kind)
        assertEquals(d.place.path, info.defaultDownloadDirectory)
        assertEquals(LocalTorrentClient.CAPABILITIES, info.capabilities)
        assertTrue(d.client.torrents().isEmpty())
    }

    @Test fun stoppedAddLifecyclePrioritiesRecheckExportAndRemoveKeepingFiles() = runBlocking {
        val d = device()
        val p = payload("lifecycle")
        val added = d.ops.add(ClientAddRequest(ClientAddSource.TorrentFile(p.torrent), d.place.path, startNow = false))
        assertTrue("$added", added is ClientOperationOutcome.Verified)
        val observed = (added as ClientOperationOutcome.Verified).observed!!
        assertEquals(ClientRunState.STOPPED, observed.runState)
        assertEquals(d.place.path, observed.downloadDirectory)
        d.observe()

        // Exact duplicate: a no-write refusal before any native call.
        val again = d.ops.add(ClientAddRequest(ClientAddSource.TorrentFile(p.torrent), null, startNow = false))
        assertTrue(again is ClientOperationOutcome.Refused)

        val files = d.ops.files(p.hash)
        assertEquals(3, files.size)
        assertTrue(files.all { it.priority == ClientFilePriority.NORMAL })
        val byPath = files.associateBy { it.path.substringAfter('/') }
        val wanted = mapOf(
            byPath.getValue("a.bin").index to ClientFilePriority.HIGH,
            byPath.getValue("b/c.bin").index to ClientFilePriority.LOW,
            byPath.getValue("d.txt").index to ClientFilePriority.SKIP)
        assertTrue(d.ops.setFilePriorities(p.hash, wanted) is ClientOperationOutcome.Verified)
        d.observe()
        assertEquals(wanted, d.ops.files(p.hash).associate { it.index to it.priority })
        // Un-skip back to Normal through the same shared workflow.
        assertTrue(d.ops.setFilePriorities(p.hash, mapOf(byPath.getValue("d.txt").index to ClientFilePriority.NORMAL))
            is ClientOperationOutcome.Verified)
        d.observe()

        val start = d.ops.setStarted(p.hash, true)
        assertTrue("$start", start is ClientOperationOutcome.Verified)
        d.observe()
        // Recheck is a stopped-only capability on Local: refused while running, nothing changed.
        val busy = d.ops.recheck(p.hash)
        assertTrue("$busy", busy is ClientOperationOutcome.Refused)
        d.observe() // even a refused native command waits for a fresh list on every surface
        val stop = d.ops.setStarted(p.hash, false)
        assertTrue("$stop", stop is ClientOperationOutcome.Verified)
        d.observe()
        val rechecked = d.ops.recheck(p.hash)
        // On a miss, name the engine's own state (state, stopped, progress): a recheck that
        // times out after 60 s with the job still running would be a real engine defect.
        assertTrue("$rechecked engine=${d.engine.status(p.hash)}", rechecked is ClientOperationOutcome.Verified)
        d.observe()

        val exported = d.client.transferSource(p.hash)
        assertTrue(exported is ClientAddSource.TorrentFile)
        assertEquals(p.hash, exported!!.infoHash)

        // Place a byte we can check survives a keep-data Remove.
        val kept = File(d.place, "lifecycle/a.bin").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        waitFor { d.engine.status(p.hash)?.state != io.github.gamesbyian.chunks.engine.LocalTransferState.CHECKING }
        val removed = d.ops.remove(p.hash, deleteData = false)
        assertTrue("$removed", removed is ClientOperationOutcome.Verified)
        assertNull(d.client.torrent(p.hash))
        assertTrue("Session Remove keeps payload files", kept.isFile)
    }

    @Test fun refusesUnverifiedFoldersAndHeldFenceWithoutAnyNativeWrite() = runBlocking {
        val d = device()
        val p = payload("refusals")
        val outside = temp.newFolder("outside").canonicalPath
        val wrong = d.ops.add(ClientAddRequest(ClientAddSource.TorrentFile(p.torrent), outside, startNow = false))
        assertTrue("$wrong", wrong is ClientOperationOutcome.Refused)
        assertTrue(d.engine.list().isEmpty())

        // Another surface holds the one process-wide Local mutation fence.
        assertTrue(d.fence.beginMutation())
        val locked = d.ops.add(ClientAddRequest(ClientAddSource.TorrentFile(p.torrent), null, startNow = false))
        assertTrue("$locked", locked is ClientOperationOutcome.Refused)
        assertTrue("A held fence admits no native Add", d.engine.list().isEmpty())
        d.fence.settledMutation()
        d.observe()
        assertTrue(d.ops.add(ClientAddRequest(ClientAddSource.TorrentFile(p.torrent), null, startNow = false))
            is ClientOperationOutcome.Verified)
    }

    @Test fun startOnAddDownloadsRealPiecesFromALocalPeerThenRemoveWithDataIsVerifiedOnDisk() = runBlocking {
        LoopbackTracker().use { tracker ->
            val p = payload("swarm", tracker = tracker.announceUrl, seed = 3)
            val seeder = device(name = "seed-place")
            // The seeder adds stopped at the folder that already holds every byte, then starts.
            File(seeder.place, "swarm").also { p.root.copyRecursively(it) }
            assertTrue(seeder.ops.add(ClientAddRequest(ClientAddSource.TorrentFile(p.torrent), null, startNow = true))
                .let { it is ClientOperationOutcome.Verified || it is ClientOperationOutcome.Pending })
            waitFor { seeder.client.torrent(p.hash)?.runState == ClientRunState.SEEDING }
            waitFor(20_000) { tracker.announces.get() >= 1 }

            val leecher = device(name = "leech-place")
            val added = leecher.ops.add(ClientAddRequest(ClientAddSource.TorrentFile(p.torrent), null, startNow = true))
            assertTrue("Start-on-Add verified only when the payload is actually started: $added",
                added is ClientOperationOutcome.Verified)
            waitFor(90_000) { leecher.client.torrent(p.hash)?.runState == ClientRunState.SEEDING }
            assertTrue(leecher.engine.flushCompletedForLibraryRead(p.hash))
            for ((path, bytes) in p.files) assertArrayEquals(bytes, File(leecher.place, "swarm/$path").readBytes())
            leecher.observe()

            val removed = leecher.ops.remove(p.hash, deleteData = true)
            assertTrue("$removed", removed is ClientOperationOutcome.Verified)
            for (path in p.files.keys) assertFalse("$path deleted", File(leecher.place, "swarm/$path").exists())
            // The seeder's own copy is a different client's data: untouched.
            for ((path, bytes) in p.files) assertArrayEquals(bytes, File(seeder.place, "swarm/$path").readBytes())
        }
    }

    @Test fun aStillFetchingMagnetJobMovesAsLibtorrentsOwnMagnetWithTheExactIdentity() = runBlocking {
        // Found by the installed journey: a magnet job copied to This device had
        // no metadata (no peers), so Copy back was refused "no metainfo".
        val source = device(name = "magnet-source")
        val target = device(name = "magnet-target")
        val hash = ByteArray(20).also(java.security.SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }
        val magnet = "magnet:?xt=urn:btih:$hash&dn=chunks-fetching&tr=" +
            java.net.URLEncoder.encode("http://127.0.0.1:1/announce", "UTF-8")
        val added = source.ops.add(ClientAddRequest(ClientAddSource.Magnet(magnet), null, startNow = false))
        assertTrue("$added", added !is ClientOperationOutcome.Refused && added !is ClientOperationOutcome.Uncertain)
        source.observe()

        val exported = source.client.transferSource(hash)
        assertTrue("$exported", exported is ClientAddSource.Magnet)
        assertEquals(hash, exported!!.infoHash)
        assertTrue("libtorrent's magnet keeps the job's tracker",
            (exported as ClientAddSource.Magnet).uri.contains("127.0.0.1"))

        val move = ClientJobTransfer(source.ops, source.client, target.ops, target.client, "local-a", "local-b")
        val copy = move.copy(hash, null, startOnTarget = false)
        assertTrue("$copy", copy is ClientJobTransfer.CopyResult.Copied)
        assertTrue("re-created from the magnet", !(copy as ClientJobTransfer.CopyResult.Copied).viaMetainfo)
        assertNotNull(target.client.torrent(hash))
        target.observe()
        assertTrue(move.retireSource(hash, copy) is ClientOperationOutcome.Verified)
        assertNull(source.client.torrent(hash))
    }

    @Test fun jobOnlyMoveBetweenTwoLocalEnginesUsesTheSharedReceiptAndKeepsSourceFiles() = runBlocking {
        val source = device(name = "move-source")
        val target = device(name = "move-target")
        val p = payload("moved")
        File(source.place, "moved").also { p.root.copyRecursively(it) }
        // Existing complete files: libtorrent verifies every piece, then stops by itself.
        val added = source.ops.add(ClientAddRequest(ClientAddSource.TorrentFile(p.torrent), null, startNow = false))
        assertTrue("$added", added is ClientOperationOutcome.Verified)
        waitFor { source.client.torrent(p.hash)?.runState == ClientRunState.STOPPED }
        assertEquals("verified existing pieces", source.client.torrent(p.hash)?.totalBytes,
            source.client.torrent(p.hash)?.completedBytes)
        source.observe()

        val move = ClientJobTransfer(source.ops, source.client, target.ops, target.client, "local-a", "local-b")
        val copy = move.copy(p.hash, null, startOnTarget = false)
        assertTrue("$copy", copy is ClientJobTransfer.CopyResult.Copied)
        assertTrue((copy as ClientJobTransfer.CopyResult.Copied).viaMetainfo)
        assertNotNull("Copy alone never retires the source", source.client.torrent(p.hash))
        assertEquals(target.place.path, target.client.torrent(p.hash)?.downloadDirectory)
        target.observe()

        val retired = move.retireSource(p.hash, copy)
        assertTrue("$retired", retired is ClientOperationOutcome.Verified)
        assertNull(source.client.torrent(p.hash))
        for ((path, bytes) in p.files) assertArrayEquals("job-only Move keeps $path",
            bytes, File(source.place, "moved/$path").readBytes())
        // The one-use receipt cannot be replayed.
        assertTrue(move.retireSource(p.hash, copy) is ClientOperationOutcome.Refused)
    }

    @Test fun sharedFileChoicesAtAddSkipReviewedFilesBeforeStartOnRealLibtorrent() = runBlocking {
        val d = device()
        val p = payload("choices")
        // The reviewed layout exactly as Universal Add presents it.
        val reviewed = requireNotNull(AddFileChoices.declared(
            io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(p.torrent)))
        val skip = reviewed.single { it.path.endsWith("c.bin") }
        val outcome = d.ops.add(ClientAddRequest(ClientAddSource.TorrentFile(p.torrent), null, startNow = true,
            skippedFiles = mapOf(skip.index to skip.path)))
        assertTrue("$outcome", outcome is ClientOperationOutcome.Verified)
        val files = d.client.files(p.hash).associateBy { it.index }
        assertEquals(ClientFilePriority.SKIP, files.getValue(skip.index).priority)
        assertTrue(files.values.filter { it.index != skip.index }.all { it.priority == ClientFilePriority.NORMAL })
        assertEquals(ClientRunState.DOWNLOADING, d.client.torrent(p.hash)?.runState)
    }
    @Test fun reviewedSkipLowAndHighAtAddAreAppliedStoppedOnRealLibtorrent() = runBlocking {
        val d = device()
        val p = payload("levels")
        val reviewed = requireNotNull(AddFileChoices.declared(
            io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(p.torrent)))
        val skip = reviewed.single { it.path.endsWith("c.bin") }
        val high = reviewed.single { it.path.endsWith("a.bin") }
        val low = reviewed.single { it.path.endsWith("d.txt") }
        val outcome = d.ops.add(ClientAddRequest(ClientAddSource.TorrentFile(p.torrent), null, startNow = false,
            skippedFiles = mapOf(skip.index to skip.path),
            filePriorities = mapOf(
                high.index to io.github.gamesbyian.chunks.rpc.ReviewedFilePriority(high.path, ClientFilePriority.HIGH),
                low.index to io.github.gamesbyian.chunks.rpc.ReviewedFilePriority(low.path, ClientFilePriority.LOW))))
        assertTrue("$outcome", outcome is ClientOperationOutcome.Verified)
        val files = d.client.files(p.hash).associateBy { it.index }
        assertEquals(ClientFilePriority.SKIP, files.getValue(skip.index).priority)
        assertEquals(ClientFilePriority.HIGH, files.getValue(high.index).priority)
        assertEquals(ClientFilePriority.LOW, files.getValue(low.index).priority)
        assertEquals("stopped Add stays stopped", ClientRunState.STOPPED, d.client.torrent(p.hash)?.runState)
    }

    @Test fun aLayoutThatDiffersFromTheReviewAppliesNoPriorityAndNeverStarts() = runBlocking {
        val d = device()
        val p = payload("mismatch")
        val reviewed = requireNotNull(AddFileChoices.declared(
            io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(p.torrent)))
        val high = reviewed.single { it.path.endsWith("a.bin") }
        // The reviewed path no longer names that index: never a guessed target.
        val outcome = d.ops.add(ClientAddRequest(ClientAddSource.TorrentFile(p.torrent), null, startNow = true,
            filePriorities = mapOf(high.index to
                io.github.gamesbyian.chunks.rpc.ReviewedFilePriority("elsewhere/z.bin", ClientFilePriority.HIGH))))
        assertTrue("$outcome", outcome is ClientOperationOutcome.Pending)
        assertTrue(d.client.files(p.hash).all { it.priority == ClientFilePriority.NORMAL })
        assertEquals("never started without confirmed choices", ClientRunState.STOPPED,
            d.client.torrent(p.hash)?.runState)
    }
}
