package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The bundled Local engine (real libtorrent) through the SAME shared port and
 * workflow as the remote clients, plus job-only Move between Local and a real
 * remote daemon with real peer-to-peer bytes when one is configured.
 */
// Plain JVM (no Robolectric): the engine's JVM no-follow read probe applies,
// exactly as in LocalTorrentEngineTest; org.json is the real library.
class LocalClientInteropTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()

    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true
    )).also { it.start(); engines += it }

    private fun fixture(tracker: String?): Pair<TorrentMetainfoBuilder.Result, Map<String, ByteArray>> {
        val random = SecureRandom()
        val name = "chunks-local-" + java.lang.Long.toString(System.nanoTime() and Long.MAX_VALUE, 36)
        val files = linkedMapOf("a.bin" to ByteArray(96 * 1024).also(random::nextBytes),
            "b.bin" to ByteArray(70 * 1024 + 5).also(random::nextBytes))
        val result = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
            files.map { (f, b) -> TorrentMetainfoBuilder.Entry(listOf(f), b.size.toLong()) { b.inputStream() } },
            trackerTiers = tracker?.let { listOf(listOf(it)) } ?: emptyList(), pieceLength = 16 * 1024))
        return result to files
    }

    /** The ONE production Local adapter; this test is the sole writer of its private engine. */
    private fun localClient(engine: LocalTorrentEngine, place: File) = io.github.gamesbyian.chunks.LocalTorrentClient(
        { engine }, places = object : io.github.gamesbyian.chunks.LocalClientPlaces {
            override fun defaultDirectory() = place.canonicalPath
            override fun resolve(directory: String?) = if (directory == null) place.canonicalFile
                else io.github.gamesbyian.chunks.LocalClientPlaces.within(listOf(place), directory)
        }, fence = io.github.gamesbyian.chunks.LocalWriteFence.singleWriter)

    private fun verified(label: String, o: ClientOperationOutcome) =
        assertTrue("$label: $o", o is ClientOperationOutcome.Verified)

    @Test fun localEngineRunsTheSharedJourney() = runBlocking {
        val place = temp.newFolder("place")
        val client = localClient(engine(), place)
        val ops = ClientOperations(client, 60, 250)
        val (fx, files) = fixture(null)
        val hash = fx.infoHashV1
        val dir = File(place, "job").apply { mkdirs() }
        val top = File(dir, TorrentMetainfoReader.preview(fx.torrent).name).apply { mkdirs() }
        files.forEach { (f, b) -> File(top, f).writeBytes(b) }

        verified("add_stopped", ops.add(ClientAddRequest(ClientAddSource.TorrentFile(fx.torrent), dir.absolutePath, false)))
        assertTrue(ops.add(ClientAddRequest(ClientAddSource.TorrentFile(fx.torrent), dir.absolutePath, false))
            is ClientOperationOutcome.Refused)
        val listed = client.files(hash)
        assertEquals(2, listed.size)
        verified("priorities", ops.setFilePriorities(hash,
            mapOf(listed[0].index to ClientFilePriority.SKIP, listed[1].index to ClientFilePriority.HIGH)))
        verified("priorities_restore", ops.setFilePriorities(hash, listed.associate { it.index to ClientFilePriority.NORMAL }))
        verified("start", ops.setStarted(hash, true))
        var seeding = false
        for (i in 0 until 120) {
            val t = client.torrent(hash)
            if (t?.runState == ClientRunState.SEEDING && t.completedBytes == fx.totalBytes) { seeding = true; break }
            delay(250)
        }
        assertTrue("Local engine never verified its payload", seeding)
        verified("stop", ops.setStarted(hash, false))
        assertTrue(client.transferSource(hash) is ClientAddSource.TorrentFile)
        // Outside every verified Place: refused before any write.
        val outside = temp.newFolder("outside")
        val (other, _) = fixture(null)
        assertTrue(ops.add(ClientAddRequest(ClientAddSource.TorrentFile(other.torrent), outside.absolutePath, false))
            is ClientOperationOutcome.Refused)
        verified("remove_keep_files", ops.remove(hash, deleteData = false))
        assertTrue(files.keys.all { File(top, it).isFile })
    }

    /** Local -> remote and remote -> Local job Move, bytes over real BitTorrent. */
    @Test fun moveBetweenLocalAndARealRemoteClientTransfersRealBytes() = runBlocking {
        val tracker = System.getenv("CHUNKS_INTEROP_TRACKER").orEmpty()
        val url = System.getenv("CHUNKS_INTEROP_QBITTORRENT_URL").orEmpty()
        val remoteDir = System.getenv("CHUNKS_INTEROP_QBITTORRENT_PAYLOAD_DIR").orEmpty()
        assumeTrue("Needs the local qBittorrent daemon and loopback tracker",
            tracker.isNotBlank() && url.isNotBlank() && remoteDir.isNotBlank())
        val remote = QBittorrentClient(RemoteCredentials(url,
            System.getenv("CHUNKS_INTEROP_QBITTORRENT_USER").orEmpty(),
            System.getenv("CHUNKS_INTEROP_QBITTORRENT_PASS").orEmpty()))
        val place = temp.newFolder("place")
        val local = localClient(engine(), place)
        val localOps = ClientOperations(local, 60, 500)
        val remoteOps = ClientOperations(remote, 60, 500)

        suspend fun waitSeeding(c: TorrentClient, hash: String, total: Long, poke: suspend () -> Unit): Boolean {
            for (i in 0 until 240) {
                val t = c.torrent(hash)
                if (t?.runState == ClientRunState.SEEDING && t.completedBytes == total) return true
                if (i % 10 == 3) poke()
                delay(500)
            }
            return false
        }

        for (direction in listOf("LOCAL->QBITTORRENT", "QBITTORRENT->LOCAL")) {
            val (fx, files) = fixture(tracker)
            val hash = fx.infoHashV1
            val name = TorrentMetainfoReader.preview(fx.torrent).name
            val toRemote = direction.startsWith("LOCAL")
            val (src, srcOps, dst, dstOps) = if (toRemote) listOf(local, localOps, remote, remoteOps)
                else listOf(remote, remoteOps, local, localOps)
            src as TorrentClient; dst as TorrentClient
            srcOps as ClientOperations; dstOps as ClientOperations
            val srcDir = (if (toRemote) File(place, "src-" + hash.take(8)) else File(remoteDir, "src-" + hash.take(8))).apply { mkdirs() }
            val dstDir = (if (toRemote) File(remoteDir, "dst-" + hash.take(8)) else File(place, "dst-" + hash.take(8))).apply { mkdirs() }
            File(srcDir, name).mkdirs()
            files.forEach { (f, b) -> File(srcDir, "$name/$f").writeBytes(b) }

            verified("$direction source_add", srcOps.add(ClientAddRequest(ClientAddSource.TorrentFile(fx.torrent),
                srcDir.absolutePath, startNow = true)))
            assertTrue("$direction: source never seeded",
                waitSeeding(src, hash, fx.totalBytes) { runCatching { src.reannounce(hash) } })
            val transfer = ClientJobTransfer(srcOps, src, dstOps, dst,
                sourceClientId = "test-$direction-source", targetClientId = "test-$direction-target")
            val copied = transfer.copy(hash, dstDir.absolutePath, startOnTarget = true)
            assertTrue("$direction copy: $copied", copied is ClientJobTransfer.CopyResult.Copied)
            assertTrue("$direction: target never downloaded from the source", waitSeeding(dst, hash, fx.totalBytes) {
                runCatching { src.reannounce(hash) }; runCatching { dst.reannounce(hash) }
            })
            files.forEach { (f, b) ->
                assertTrue("$direction $f bytes", File(dstDir, "$name/$f").readBytes().contentEquals(b))
            }
            println("CHUNKS_INTEROP: MOVE $direction target_downloaded_from_source=true pieces=${fx.pieceCount} bytes_identical=true")
            verified("$direction retire", transfer.retireSource(hash, copied as ClientJobTransfer.CopyResult.Copied))
            assertTrue(files.keys.all { File(srcDir, "$name/$it").isFile })
            dstOps.remove(hash, deleteData = false)
        }
    }

    /**
     * The open Local <-> Transmission finding: Transmission does not pick up a
     * loopback peer from the tracker on this rig, so Local dials its fixed peer
     * port (51513), as a real swarm peer would. The Move itself is the same
     * production job-only Copy -> verified target -> separate source retirement.
     */
    @Test fun moveBetweenLocalAndRealTransmissionTransfersRealBytes() =
        moveWithDialledPeer(io.github.gamesbyian.chunks.model.RemoteBackendKind.TRANSMISSION, 51513)

    /** Deluge's interop daemon listens on a fixed 51516 (tools/interop/start_local_clients.sh). */
    @Test fun moveBetweenLocalAndRealDelugeTransfersRealBytes() =
        moveWithDialledPeer(io.github.gamesbyian.chunks.model.RemoteBackendKind.DELUGE, 51516)

    private fun moveWithDialledPeer(kind: io.github.gamesbyian.chunks.model.RemoteBackendKind, peerPort: Int) = runBlocking {
        fun env(name: String) = System.getenv("CHUNKS_INTEROP_" + kind.name + "_" + name).orEmpty()
        val url = env("URL")
        val remoteDir = env("PAYLOAD_DIR")
        assumeTrue("Needs the local ${kind.name} daemon", url.isNotBlank() && remoteDir.isNotBlank())
        val credentials = RemoteCredentials(url, env("USER"), env("PASS"))
        val remote: TorrentClient = when (kind) {
            io.github.gamesbyian.chunks.model.RemoteBackendKind.TRANSMISSION -> TransmissionClient(credentials)
            io.github.gamesbyian.chunks.model.RemoteBackendKind.DELUGE -> DelugeClient(credentials, env("DAEMON_ID").ifBlank {
                DelugeReadOnlyRpc(credentials, "unselected").registeredDaemonIds().single() })
            else -> error("unsupported")
        }
        val engine = engine()
        val place = temp.newFolder("place-" + kind.name.lowercase())
        val local = localClient(engine, place)
        val localOps = ClientOperations(local, 60, 500)
        val remoteOps = ClientOperations(remote, 60, 500)
        val transmissionPeer = java.net.InetSocketAddress("127.0.0.1", peerPort)

        suspend fun waitSeeding(c: TorrentClient, hash: String, total: Long, poke: suspend () -> Unit): Boolean {
            for (i in 0 until 240) {
                val t = c.torrent(hash)
                if (t?.runState == ClientRunState.SEEDING && t.completedBytes == total) return true
                if (i % 10 == 3) poke()
                delay(500)
            }
            return false
        }

        // Remote -> Local first: a copied magnet must fetch its metadata before Start
        // is observable, so it runs before any other connection between the two.
        for (toRemote in listOf(false, true)) {
            val direction = if (toRemote) "LOCAL->" + kind.name else kind.name + "->LOCAL"
            val (fx, files) = fixture(null)
            val hash = fx.infoHashV1
            val name = TorrentMetainfoReader.preview(fx.torrent).name
            val (src, srcOps, dst, dstOps) = if (toRemote) listOf(local, localOps, remote, remoteOps)
                else listOf(remote, remoteOps, local, localOps)
            src as TorrentClient; dst as TorrentClient
            srcOps as ClientOperations; dstOps as ClientOperations
            val srcDir = (if (toRemote) File(place, "src-" + hash.take(8)) else File(remoteDir, "src-" + hash.take(8))).apply { mkdirs() }
            val dstDir = (if (toRemote) File(remoteDir, "dst-" + hash.take(8)) else File(place, "dst-" + hash.take(8))).apply { mkdirs() }
            File(srcDir, name).mkdirs()
            files.forEach { (f, b) -> File(srcDir, "$name/$f").writeBytes(b) }

            verified("$direction source_add", srcOps.add(ClientAddRequest(ClientAddSource.TorrentFile(fx.torrent),
                srcDir.absolutePath, startNow = true)))
            assertTrue("$direction: source never seeded", waitSeeding(src, hash, fx.totalBytes) {
                if (!toRemote) runCatching { srcOps.recheck(hash) }
            })
            val transfer = ClientJobTransfer(srcOps, src, dstOps, dst,
                sourceClientId = "test-$direction-source", targetClientId = "test-$direction-target")
            // A copied magnet needs metadata from a peer before Start can be observed:
            // keep Local dialling Transmission while the copy's own readback runs.
            val dialler = launch(kotlinx.coroutines.Dispatchers.IO) {
                while (true) { runCatching { engine.connectPeer(hash, transmissionPeer) }; delay(1_000) }
            }
            val copied = try { transfer.copy(hash, dstDir.absolutePath, startOnTarget = true) }
                finally { dialler.cancel() }
            assertTrue("$direction copy: $copied", copied is ClientJobTransfer.CopyResult.Copied)
            assertTrue("$direction: target never downloaded from the source", waitSeeding(dst, hash, fx.totalBytes) {
                engine.connectPeer(hash, transmissionPeer)
            })
            files.forEach { (f, b) ->
                assertTrue("$direction $f bytes", File(dstDir, "$name/$f").readBytes().contentEquals(b))
            }
            println("CHUNKS_INTEROP: MOVE $direction target_downloaded_from_source=true pieces=${fx.pieceCount} bytes_identical=true")
            verified("$direction retire", transfer.retireSource(hash, copied as ClientJobTransfer.CopyResult.Copied))
            assertTrue("source files kept", files.keys.all { File(srcDir, "$name/$it").isFile })
            dstOps.remove(hash, deleteData = false)
        }
    }
}
