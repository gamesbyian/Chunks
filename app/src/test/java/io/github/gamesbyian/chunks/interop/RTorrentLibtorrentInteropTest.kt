package io.github.gamesbyian.chunks.interop

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LoopbackTracker
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.FilePriorityOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
import io.github.gamesbyian.chunks.rpc.RemoteAddAndStart
import io.github.gamesbyian.chunks.rpc.StoppedRemoteAddPlan
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.libtorrent4j.TorrentBuilder

/**
 * Cross-implementation BitTorrent proof on one disposable CI runner:
 * rakshasa rTorrent (the remote engine family Chunks controls) and Arvid
 * libtorrent via libtorrent4j (the local device engine) exchange real payloads over
 * the BitTorrent peer wire protocol, in both directions, with each side
 * piece-hash verifying what it receives and the bytes compared independently.
 *
 * Loopback only, no tracker/DHT/PEX: the local device engine dials rTorrent's fixed
 * peer port. This is real protocol interoperability evidence, NOT Internet
 * swarm reachability, NAT traversal, Android or physical-device evidence.
 */
class RTorrentLibtorrentInteropTest {
    private val engines = ArrayList<LocalTorrentEngine>()
    private val tracker = LoopbackTracker()

    @Before fun native() = DesktopLibtorrent.ensureLoaded()

    @After fun tearDown() {
        engines.forEach { it.close() }
        tracker.close()
    }

    private data class Rig(val remote: RTorrentRemote, val dataRoot: File, val peer: InetSocketAddress)

    private fun rig(): Rig {
        val address = System.getenv("CHUNKS_DISPOSABLE_RT_URL").orEmpty()
        val port = System.getenv("CHUNKS_DISPOSABLE_RT_PEER_PORT")?.toIntOrNull()
        assumeTrue("Interop runs only inside the disposable rTorrent rig",
            address.isNotBlank() && port != null)
        val uri = URI(address)
        val authenticated = uri.rawPath == "/AUTHRPC"
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 18080 &&
            uri.rawPath in setOf("/RPC2", "/AUTHRPC") && uri.rawQuery == null &&
            uri.rawFragment == null && uri.userInfo == null) {
            "Refusing to run interop outside the exact disposable loopback rig"
        }
        val target = File(System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR").orEmpty())
        require(target.isAbsolute && target.path.contains("chunks-data") && target.isDirectory) {
            "No disposable data root was configured"
        }
        val user = if (authenticated) System.getenv("CHUNKS_DISPOSABLE_AUTH_USER").orEmpty() else ""
        val pass = if (authenticated) System.getenv("CHUNKS_DISPOSABLE_AUTH_PASS").orEmpty() else ""
        if (authenticated) require(user == "chunks-disposable" && pass.length >= 32) {
            "Missing per-run synthetic authentication identity"
        }
        val credentials = RemoteCredentials(address, user, pass)
        val remote = RTorrentRemote(credentials)
        if (authenticated) {
            // The peer-wire phase must use the accepted challenged principal,
            // not an anonymously readable sibling RPC2 path. Version alone
            // does not establish authentication; require both an inventory
            // read and the transport's accepted challenge evidence first.
            runBlocking {
                require(remote.version().isNotBlank()) { "Real rTorrent version missing" }
                remote.listTransfers("main")
                io.github.gamesbyian.chunks.rpc.requireRemoteConnectIdentityEvidence(
                    credentials, remote)
            }
        }
        return Rig(remote, target, InetSocketAddress("127.0.0.1", requireNotNull(port)))
    }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true)).also {
        it.start(); engines += it
    }

    private fun waitFor(what: String, seconds: Int = 120, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                // Gradle's short test log omits assertion messages; name the wait.
                println("CHUNKS_INTEROP_TIMEOUT: $what")
                fail("Timed out waiting for $what")
            }
            Thread.sleep(250)
        }
    }

    /** A multi-file payload whose last piece is short, as real torrents are. */
    private fun fixture(parent: File, name: String, seed: Long): Pair<File, Map<String, ByteArray>> {
        val root = File(parent, name).apply { mkdirs() }
        val random = Random(seed)
        val files = linkedMapOf(
            "video/part-1.bin" to ByteArray(1_500_000).also(random::nextBytes),
            "video/part-2.bin" to ByteArray(733_333).also(random::nextBytes),
            "readme.txt" to "Chunks interop fixture $seed\n".toByteArray()
        )
        files.forEach { (path, bytes) ->
            File(root, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
        }
        return root to files
    }

    private fun torrentFor(root: File) =
        // rTorrent 0.9.x and Chunks' remote Add accept BitTorrent v1 metainfo only.
        // The tracker is this test's own loopback HTTP tracker, so peer
        // discovery never depends on DHT or the public Internet.
        TorrentBuilder().path(root).flags(TorrentBuilder.V1_ONLY).pieceSize(64 * 1024)
            .addTracker(tracker.announceUrl).generate().entry().bencode()

    private suspend fun remoteRow(remote: RTorrentRemote, hash: String): RemoteTransfer? =
        remote.listTransfers("main").firstOrNull { it.hash.equals(hash, ignoreCase = true) }

    private suspend fun counter(remote: RTorrentRemote, method: String, hash: String): Long =
        (remote.call(method, listOf(hash)) as? Number)?.toLong() ?: -1L

    private suspend fun addAndStart(remote: RTorrentRemote, torrent: ByteArray, directory: File): String {
        val hash = TorrentMetainfoReader.preview(torrent).infoHashV1
        val added = remote.loadStoppedTorrentBytes(torrent, directory.absolutePath)
        assertTrue("rTorrent stopped Add was not verified: $added",
            added is RemoteAddOutcome.VerifiedStopped || added is RemoteAddOutcome.PendingVerification)
        waitFor("rTorrent to list the added torrent", 30) {
            runBlocking { remoteRow(remote, hash) } != null
        }
        val started = remote.setStarted(hash, true)
        assertTrue("rTorrent Start was not acknowledged: $started",
            started is LifecycleOutcome.Verified || started is LifecycleOutcome.Pending)
        waitFor("rTorrent to report the torrent started", 30) {
            runBlocking { remoteRow(remote, hash) }?.started == true
        }
        return hash
    }

    @Test fun localEngineDownloadsExactPayloadSeededByRealRTorrent() = runBlocking {
        val rig = rig()
        val seedParent = File(rig.dataRoot, "interop-seed-" + System.nanoTime()).apply { mkdirs() }
        val (root, files) = fixture(seedParent, "Interop from rTorrent", System.nanoTime())
        val torrent = torrentFor(root)
        val hash = addAndStart(rig.remote, torrent, seedParent)
        // rTorrent hash-checks the existing files itself before it may seed them.
        waitFor("rTorrent to verify and complete its seed data") {
            runBlocking { remoteRow(rig.remote, hash) }?.let { it.completedBytes == it.sizeBytes } == true
        }

        val leechRoot = kotlin.io.path.createTempDirectory("chunks-interop-leech").toFile()
        try {
            val local = engine()
            val added = local.addStopped(torrent, leechRoot, listOf(rig.peer))
            val status = (added as LocalAddResult.AddedStopped).status
            assertEquals(hash.lowercase(), status.infoHash.lowercase())
            local.resume(status.infoHash).let { assertEquals(it.toString(), LocalCommandOutcome.VERIFIED, it.outcome) }
            waitFor("local engine to download from rTorrent") {
                val s = local.status(status.infoHash)
                s?.error?.let { fail("Local engine error: $it") }
                s?.state == LocalTransferState.SEEDING || s?.state == LocalTransferState.FINISHED
            }
            assertTrue(local.flushCompletedForLibraryRead(status.infoHash, 20_000))
            val total = files.values.sumOf { it.size.toLong() }
            // libtorrent publishes transfer counters on its asynchronous stats tick.
            waitFor("local engine download counter (payload arrived over the wire)", 30) {
                (local.status(status.infoHash)?.payloadDownloadedBytes ?: 0) >= total
            }
            files.forEach { (path, bytes) ->
                assertArrayEquals("Exact bytes for $path",
                    bytes, File(leechRoot, "Interop from rTorrent/$path").readBytes())
            }
            waitFor("rTorrent to report upload to the local device engine", 30) {
                runBlocking { counter(rig.remote, "d.up.total", hash) } >= total
            }
        } finally {
            leechRoot.deleteRecursively()
        }
    }

    @Test fun realRTorrentDownloadsExactPayloadSeededByLocalEngine() = runBlocking {
        val rig = rig()
        val seedParent = kotlin.io.path.createTempDirectory("chunks-interop-seed").toFile()
        try {
            val (root, files) = fixture(seedParent, "Interop from the local device", System.nanoTime())
            val torrent = torrentFor(root)
            val local = engine()
            val seed = (local.addStopped(torrent, seedParent) as LocalAddResult.AddedStopped).status
            local.resume(seed.infoHash).let { assertEquals(it.toString(), LocalCommandOutcome.VERIFIED, it.outcome) }
            waitFor("local engine to verify its seed") {
                local.status(seed.infoHash)?.state == LocalTransferState.SEEDING
            }

            val leechParent = File(rig.dataRoot, "interop-leech-" + System.nanoTime()).apply { mkdirs() }
            // The server receives the document the local device engine exports for
            // "Prepare on rTorrent", not the original file: identity and
            // trackers must survive that handoff for rTorrent to find peers.
            val handoff = requireNotNull(local.exportTorrentFile(seed.infoHash))
            val hash = TorrentMetainfoReader.preview(handoff).infoHashV1
            assertEquals(seed.infoHash.lowercase(), hash.lowercase())
            val added = rig.remote.loadStoppedTorrentBytes(handoff, leechParent.absolutePath)
            assertTrue("Real rTorrent stopped Add was not verified: $added",
                added is RemoteAddOutcome.VerifiedStopped || added is RemoteAddOutcome.PendingVerification)
            waitFor("rTorrent to list the new stopped download", 30) {
                runBlocking { remoteRow(rig.remote, hash) }?.started == false
            }
            // Take an exact-hash baseline BEFORE starting the new real-engine
            // download: a fast loopback transfer could otherwise finish before
            // the first sample and falsely satisfy nondecreasing-only checks.
            val before = rig.remote.readTrafficSample(hash)
            assertEquals(hash.lowercase(), before.hash.lowercase())
            assertEquals("New stopped download received no peer bytes", 0L, before.receivedBytes)
            assertEquals("New stopped download has no verified pieces", 0L, before.verifiedBytes)
            val started = rig.remote.setStarted(hash, true)
            assertTrue("Real rTorrent Start was not acknowledged: $started",
                started is LifecycleOutcome.Verified || started is LifecycleOutcome.Pending)
            waitFor("rTorrent started after exact baseline", 30) {
                runBlocking { remoteRow(rig.remote, hash) }?.started == true
            }
            // rTorrent learns the local device seeder only from its tracker announce
            // and dials it itself: no DHT, PEX or manual peer injection.
            waitFor("rTorrent to download from the local device engine") {
                runBlocking { remoteRow(rig.remote, hash) }?.let {
                    it.sizeBytes > 0 && it.completedBytes == it.sizeBytes
                } == true
            }
            val total = files.values.sumOf { it.size.toLong() }
            val after = rig.remote.readTrafficSample(hash)
            assertEquals(hash.lowercase(), after.hash.lowercase())
            assertTrue("Exact-hash readback timestamp did not advance",
                after.monotonicNanos > before.monotonicNanos)
            assertEquals("Exact-hash advertised byte size", total, after.expectedBytes)
            assertEquals("Exact-hash verified piece bytes", total, after.verifiedBytes)
            assertTrue("Only exact-hash received bytes count, not server-global traffic",
                after.receivedBytes != null && after.receivedBytes >= total)
            assertTrue("Exact-hash received bytes must grow after Start",
                after.receivedBytes!! > before.receivedBytes!!)
            assertTrue("Exact-hash piece-verified bytes must grow after Start",
                after.verifiedBytes!! > before.verifiedBytes!!)
            files.forEach { (path, bytes) ->
                assertArrayEquals("Exact bytes rTorrent wrote for $path",
                    bytes, File(leechParent, "Interop from the local device/$path").readBytes())
            }
            waitFor("local engine upload counter", 30) {
                (local.status(seed.infoHash)?.payloadUploadedBytes ?: 0) >= total
            }
        } finally {
            seedParent.deleteRecursively()
        }
    }

    /**
     * L2 remote piece evidence: rTorrent leeches from the local device engine with
     * one file turned OFF through the production setFilePriority. rTorrent's
     * own per-file chunk counters must show the wanted files complete while
     * the skipped file holds at most one boundary chunk shared with a wanted
     * file; raising it to normal must then complete it with exact bytes.
     */
    @Test fun realRTorrentSkippedFileGetsNoPiecesUntilItsPriorityIsRaised() = runBlocking {
        val rig = rig()
        val seedParent = kotlin.io.path.createTempDirectory("chunks-interop-priority-seed").toFile()
        try {
            val (root, files) = fixture(seedParent, "Interop priorities", System.nanoTime())
            val torrent = torrentFor(root)
            val local = engine()
            val seed = (local.addStopped(torrent, seedParent) as LocalAddResult.AddedStopped).status
            local.resume(seed.infoHash).let { assertEquals(it.toString(), LocalCommandOutcome.VERIFIED, it.outcome) }
            waitFor("local engine to verify its seed") {
                local.status(seed.infoHash)?.state == LocalTransferState.SEEDING
            }
            val leechParent = File(rig.dataRoot, "interop-priority-" + System.nanoTime()).apply { mkdirs() }
            val hash = TorrentMetainfoReader.preview(torrent).infoHashV1
            val added = rig.remote.loadStoppedTorrentBytes(torrent, leechParent.absolutePath)
            assertTrue("Stopped Add not verified: $added",
                added is RemoteAddOutcome.VerifiedStopped || added is RemoteAddOutcome.PendingVerification)
            waitFor("rTorrent lists the stopped download", 30) {
                runBlocking { remoteRow(rig.remote, hash) }?.started == false
            }
            val listed = rig.remote.listFiles(hash)
            val skipIndex = listed.indexOfFirst { it.path == "video/part-2.bin" }
            assertTrue("fixture file missing from rTorrent: $listed", skipIndex >= 0)
            val skipFile = listed[skipIndex]
            val off = rig.remote.setFilePriority(hash, skipIndex, skipFile.path, skipFile.sizeBytes,
                skipFile.priority, 0)
            assertTrue("Production priority Off not verified: $off", off is FilePriorityOutcome.Verified)
            suspend fun chunks(index: Int, method: String) =
                (rig.remote.call(method, listOf("$hash:f$index")) as Number).toLong()
            val started = rig.remote.setStarted(hash, true)
            assertTrue("Start not acknowledged: $started",
                started is LifecycleOutcome.Verified || started is LifecycleOutcome.Pending)
            val wanted = listed.indices.filter { it != skipIndex }
            waitFor("rTorrent completes every wanted file from the local device peer") {
                runBlocking { wanted.all { chunks(it, "f.completed_chunks") == chunks(it, "f.size_chunks") } }
            }
            // Let any in-flight requests settle, then sample the skipped file.
            Thread.sleep(3_000)
            val skippedDone = chunks(skipIndex, "f.completed_chunks")
            val skippedTotal = chunks(skipIndex, "f.size_chunks")
            // File order comes from directory traversal and can differ by
            // filesystem, so the skipped file may border wanted files on one
            // or both ends. Only chunks it SHARES with a wanted file may be
            // complete; rTorrent reports each file's exact chunk range.
            suspend fun range(index: Int) =
                chunks(index, "f.range_first") until chunks(index, "f.range_second")
            val skippedRange = range(skipIndex)
            val wantedRanges = wanted.map { range(it) }
            val shared = skippedRange.count { chunk -> wantedRanges.any { chunk in it } }
            println("CHUNKS_RT_PRIORITY: skipped completed_chunks=$skippedDone size_chunks=$skippedTotal " +
                "order=${listed.map { it.path }} skippedRange=$skippedRange sharedBoundaryChunks=$shared")
            assertTrue("fixture skipped file must span many pieces", skippedTotal >= 10)
            assertTrue("a file shares chunks only at its two ends: $shared", shared in 0..2)
            assertTrue("Skipped file received pieces beyond its $shared shared boundary chunk(s): " +
                "$skippedDone/$skippedTotal", skippedDone <= shared)
            for (index in wanted) {
                val path = listed[index].path
                assertArrayEquals("Exact wanted bytes for $path", files.getValue(path),
                    File(leechParent, "Interop priorities/$path").readBytes())
            }
            val normal = rig.remote.setFilePriority(hash, skipIndex, skipFile.path, skipFile.sizeBytes, 0, 1)
            assertTrue("Production priority Normal not verified: $normal", normal is FilePriorityOutcome.Verified)
            // Having finished every wanted piece, rTorrent and the local device seed
            // may no longer be connected, and rTorrent spaces re-announces by
            // its own minimum (E sweep 41840562323). Ask for an announce now,
            // as a user's "update trackers" would, instead of waiting it out.
            rig.remote.call("d.tracker_announce", listOf(hash))
            waitFor("rTorrent completes the newly wanted file", 240) {
                runBlocking { chunks(skipIndex, "f.completed_chunks") == skippedTotal }
            }
            assertArrayEquals("Exact bytes after raising priority", files.getValue(skipFile.path),
                File(leechParent, "Interop priorities/${skipFile.path}").readBytes())
            rig.remote.setStarted(hash, false)
            Unit
        } finally {
            seedParent.deleteRecursively()
        }
    }

    /**
     * "Copy files to this device": the local device gets ONLY an exact-hash magnet
     * naming the server as a direct peer (no .torrent, no tracker, no DHT).
     * rTorrent must serve the metadata and every piece; the local device verifies
     * each piece and the bytes are compared independently. The server
     * transfer is left as it was.
     */
    @Test fun localCopiesACompletedServerTransferWithTheServerAsItsOnlyPeer() = runBlocking {
        val rig = rig()
        val seedParent = File(rig.dataRoot, "interop-copy-" + System.nanoTime()).apply { mkdirs() }
        val (root, files) = fixture(seedParent, "Copy to the local device", System.nanoTime())
        val hash = addAndStart(rig.remote, torrentFor(root), seedParent)
        waitFor("rTorrent to verify and complete its seed data") {
            runBlocking { remoteRow(rig.remote, hash) }?.let { it.completedBytes == it.sizeBytes } == true
        }
        val row = requireNotNull(remoteRow(rig.remote, hash))
        waitFor("rTorrent to report the torrent active", 30) {
            runBlocking { rig.remote.peerSource(hash) }.isActive
        }
        val source = rig.remote.peerSource(hash)
        assertEquals(rig.peer.port.toLong(), source.listenPort)
        val plan = io.github.gamesbyian.chunks.model.ServerToLocalCopy.plan(row, source) {
            java.net.InetAddress.getAllByName(it).toList()
        }
        plan as io.github.gamesbyian.chunks.model.ServerToLocalCopy.Ready
        assertTrue("No tracker may reach the local device", !plan.magnet.contains("tr="))

        val leechRoot = kotlin.io.path.createTempDirectory("chunks-interop-copy").toFile()
        try {
            val local = engine()
            val added = local.addMagnet(plan.magnet, leechRoot, autoStartAfterMetadata = true)
            val infoHash = (added as LocalAddResult.FetchingMetadata).status.infoHash
            assertEquals(hash.lowercase(), infoHash)
            waitFor("local engine to fetch metadata and copy every piece from rTorrent") {
                val s = local.status(infoHash)
                s?.error?.let { fail("Local engine error: $it") }
                s?.metadataKnown == true &&
                    (s.state == LocalTransferState.SEEDING || s.state == LocalTransferState.FINISHED)
            }
            assertTrue(local.flushCompletedForLibraryRead(infoHash, 20_000))
            files.forEach { (path, bytes) ->
                assertArrayEquals("Exact copied bytes for $path",
                    bytes, File(leechRoot, "Copy to the local device/$path").readBytes())
            }
            val total = files.values.sumOf { it.size.toLong() }
            waitFor("rTorrent to report uploading the copy", 30) {
                runBlocking { counter(rig.remote, "d.up.total", hash) } >= total
            }
            // Copy, not Move: the server still holds and runs the exact transfer.
            val after = requireNotNull(remoteRow(rig.remote, hash))
            assertEquals(row.sizeBytes, after.completedBytes)
            assertTrue(after.started)
            files.forEach { (path, bytes) ->
                assertArrayEquals("Server source unchanged for $path", bytes, File(root, path).readBytes())
            }
        } finally {
            leechRoot.deleteRecursively()
        }
    }

    /**
     * Positive magnet acceptance for CHUNKS' ordinary native rTorrent
     * start-on-load path. Unlike a fabricated infohash/unreachable tracker,
     * a real libtorrent seeder serves metadata and every payload piece.
     * The metainfo is real v1 bencode, the announce is a live HTTP tracker,
     * and rTorrent must retrieve the name/file layout before transferring.
     *
     * This is disposable real-engine/peer proof, not the installed Android UI
     * or an owner-host/public-swarm acceptance receipt.
     */
    @Test fun realRTorrentNativeMagnetAddResolvesMetadataAndDownloadsVerifiedBytes() = runBlocking {
        val rig = rig()
        val seedParent = kotlin.io.path.createTempDirectory("chunks-magnet-seed").toFile()
        val originalDefault = rig.remote.call("directory.default") as String
        val originalCwd = rig.remote.call("system.cwd") as String
        try {
            val (root, files) = fixture(seedParent, "Interop via magnet", System.nanoTime())
            val torrent = torrentFor(root)
            val hash = TorrentMetainfoReader.preview(torrent).infoHashV1
            val total = files.values.sumOf { it.size.toLong() }

            val local = engine()
            val seeded = (local.addStopped(torrent, seedParent) as LocalAddResult.AddedStopped).status
            assertEquals(hash.lowercase(), seeded.infoHash.lowercase())
            local.resume(hash).let { assertEquals(it.toString(), LocalCommandOutcome.VERIFIED, it.outcome) }
            waitFor("real magnet seeder to hash-check the source") {
                local.status(hash)?.state == LocalTransferState.SEEDING
            }

            // A new, empty parent under the disposable rTorrent destination:
            // no already-present job and no pre-populated download file.
            val leechParent = File(rig.dataRoot, "interop-magnet-" + System.nanoTime())
                .apply { mkdirs() }
            assertTrue(rig.remote.listTransfers("main").none { it.hash.equals(hash, true) })
            assertTrue(File(leechParent, "Interop via magnet").listFiles().isNullOrEmpty())

            val trackerUrl = java.net.URLEncoder.encode(tracker.announceUrl, "UTF-8")
            val magnet = "magnet:?xt=urn:btih:$hash&tr=$trackerUrl"
            // Exercise the owner's blank optional folder with real metadata
            // and pieces. These administrative changes target ONLY rig()'s
            // exact disposable localhost daemon and are always restored.
            rig.remote.call("system.cwd.set", listOf("", leechParent.absolutePath))
            rig.remote.call("directory.default.set", listOf("", ""))
            assertEquals("", rig.remote.call("directory.default"))
            val plan = StoppedRemoteAddPlan.fromMagnetAtServerDefault(magnet, "")
            val started = rig.remote.loadStarted(plan)
            assertTrue("Actual magnet native load refused: $started",
                started is RemoteAddAndStart.Started ||
                    started is RemoteAddAndStart.StartNotVerified)

            // Metadata, rather than active-state or fake-hash insertion, is
            // the first acceptance milestone. A newly inserted 0-byte magnet
            // with no real peers can never pass this gate.
            waitFor("rTorrent to retrieve genuine metadata from the seeder", 90) {
                runBlocking {
                    remoteRow(rig.remote, hash)?.let {
                        it.name == root.name && it.sizeBytes == total && it.started
                    } == true
                }
            }
            assertTrue("Read-only native Place/Start recovery must verify the resolved job",
                rig.remote.reconcileStartedAdd(plan) is RemoteAddAndStart.Started)
            assertEquals(File(leechParent, root.name).absolutePath,
                requireNotNull(remoteRow(rig.remote, hash)).directory)

            waitFor("rTorrent to download all peer pieces from magnet", 150) {
                runBlocking {
                    val row = remoteRow(rig.remote, hash)
                    val sample = rig.remote.readTrafficSample(hash)
                    row?.completedBytes == total &&
                        sample.hash.equals(hash, true) &&
                        sample.expectedBytes == total &&
                        sample.verifiedBytes == total &&
                        (sample.receivedBytes ?: 0L) >= total
                }
            }
            val finished = rig.remote.readTrafficSample(hash)
            assertEquals(hash.lowercase(), finished.hash.lowercase())
            assertEquals(total, finished.expectedBytes)
            assertEquals(total, finished.verifiedBytes)
            assertTrue("Received bytes must come from the real peer", (finished.receivedBytes ?: 0L) >= total)

            files.forEach { (relative, expected) ->
                assertArrayEquals("Remote magnet payload $relative", expected,
                    File(leechParent, "Interop via magnet/$relative").readBytes())
            }
            waitFor("independent seeder uploaded full magnet payload", 30) {
                (local.status(hash)?.payloadUploadedBytes ?: 0L) >= total
            }
        } finally {
            rig.remote.call("directory.default.set", listOf("", originalDefault))
            rig.remote.call("system.cwd.set", listOf("", originalCwd))
            seedParent.deleteRecursively()
        }
    }
}
