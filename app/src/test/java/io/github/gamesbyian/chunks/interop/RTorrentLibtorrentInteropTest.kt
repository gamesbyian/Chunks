package io.github.gamesbyian.chunks.interop

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LoopbackTracker
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
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
 * libtorrent via libtorrent4j (the phone engine) exchange real payloads over
 * the BitTorrent peer wire protocol, in both directions, with each side
 * piece-hash verifying what it receives and the bytes compared independently.
 *
 * Loopback only, no tracker/DHT/PEX: the phone engine dials rTorrent's fixed
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
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 18080 &&
            uri.path == "/RPC2" && uri.rawQuery == null && uri.userInfo == null) {
            "Refusing to run interop outside the disposable loopback rig"
        }
        val target = File(System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR").orEmpty())
        require(target.isAbsolute && target.path.contains("chunks-data") && target.isDirectory) {
            "No disposable data root was configured"
        }
        return Rig(RTorrentRemote(RemoteCredentials(address, "", "")), target,
            InetSocketAddress("127.0.0.1", requireNotNull(port)))
    }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true)).also {
        it.start(); engines += it
    }

    private fun waitFor(what: String, seconds: Int = 120, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
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

    @Test fun phoneEngineDownloadsExactPayloadSeededByRealRTorrent() = runBlocking {
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
            val phone = engine()
            val added = phone.addStopped(torrent, leechRoot, listOf(rig.peer))
            val status = (added as LocalAddResult.AddedStopped).status
            assertEquals(hash.lowercase(), status.infoHash.lowercase())
            assertEquals(LocalCommandOutcome.VERIFIED, phone.resume(status.infoHash).outcome)
            waitFor("phone engine to download from rTorrent") {
                val s = phone.status(status.infoHash)
                s?.error?.let { fail("Phone engine error: $it") }
                s?.state == LocalTransferState.SEEDING || s?.state == LocalTransferState.FINISHED
            }
            assertTrue(phone.flushCompletedForLibraryRead(status.infoHash, 20_000))
            val total = files.values.sumOf { it.size.toLong() }
            // libtorrent publishes transfer counters on its asynchronous stats tick.
            waitFor("phone engine download counter (payload arrived over the wire)", 30) {
                (phone.status(status.infoHash)?.payloadDownloadedBytes ?: 0) >= total
            }
            files.forEach { (path, bytes) ->
                assertArrayEquals("Exact bytes for $path",
                    bytes, File(leechRoot, "Interop from rTorrent/$path").readBytes())
            }
            waitFor("rTorrent to report upload to the phone engine", 30) {
                runBlocking { counter(rig.remote, "d.up.total", hash) } >= total
            }
        } finally {
            leechRoot.deleteRecursively()
        }
    }

    @Test fun realRTorrentDownloadsExactPayloadSeededByPhoneEngine() = runBlocking {
        val rig = rig()
        val seedParent = kotlin.io.path.createTempDirectory("chunks-interop-seed").toFile()
        try {
            val (root, files) = fixture(seedParent, "Interop from phone", System.nanoTime())
            val torrent = torrentFor(root)
            val phone = engine()
            val seed = (phone.addStopped(torrent, seedParent) as LocalAddResult.AddedStopped).status
            assertEquals(LocalCommandOutcome.VERIFIED, phone.resume(seed.infoHash).outcome)
            waitFor("phone engine to verify its seed") {
                phone.status(seed.infoHash)?.state == LocalTransferState.SEEDING
            }

            val leechParent = File(rig.dataRoot, "interop-leech-" + System.nanoTime()).apply { mkdirs() }
            // The server receives the document the phone engine exports for
            // "Prepare on rTorrent", not the original file: identity and
            // trackers must survive that handoff for rTorrent to find peers.
            val handoff = requireNotNull(phone.exportTorrentFile(seed.infoHash))
            val hash = addAndStart(rig.remote, handoff, leechParent)
            assertEquals(seed.infoHash.lowercase(), hash.lowercase())
            // rTorrent learns the phone seeder only from its tracker announce
            // and dials it itself: no DHT, PEX or manual peer injection.
            waitFor("rTorrent to download from the phone engine") {
                runBlocking { remoteRow(rig.remote, hash) }?.let {
                    it.sizeBytes > 0 && it.completedBytes == it.sizeBytes
                } == true
            }
            val total = files.values.sumOf { it.size.toLong() }
            assertTrue("rTorrent must receive the payload over the wire",
                counter(rig.remote, "d.down.total", hash) >= total)
            files.forEach { (path, bytes) ->
                assertArrayEquals("Exact bytes rTorrent wrote for $path",
                    bytes, File(leechParent, "Interop from phone/$path").readBytes())
            }
            waitFor("phone engine upload counter", 30) {
                (phone.status(seed.infoHash)?.payloadUploadedBytes ?: 0) >= total
            }
        } finally {
            seedParent.deleteRecursively()
        }
    }
}

