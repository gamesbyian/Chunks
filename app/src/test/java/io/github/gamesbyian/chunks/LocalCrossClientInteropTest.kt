package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientJobTransfer
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.DelugeClient
import io.github.gamesbyian.chunks.rpc.DelugeReadOnlyRpc
import io.github.gamesbyian.chunks.rpc.QBittorrentClient
import io.github.gamesbyian.chunks.rpc.RTorrentClientAdapter
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.TorrentClient
import io.github.gamesbyian.chunks.rpc.TransmissionClient
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/**
 * Local ↔ remote job-only Move with REAL engines on both sides and real
 * piece-verified bytes crossing between them:
 *
 *  1. Local libtorrent verifies an existing payload, seeds it;
 *  2. Copy (exported metainfo) → the real remote client downloads every piece
 *     from Local via the loopback tracker; the separate retirement removes
 *     only the Local job (files kept);
 *  3. Move back: a fresh Local engine re-creates the job from the remote
 *     client's own export (or trackers magnet), downloads every piece from the
 *     remote client, and the remote job is retired (its files kept).
 *
 * Skipped per backend unless CHUNKS_INTEROP_<BACKEND>_URL/_PASS and
 * CHUNKS_INTEROP_TRACKER are set (tools/interop/start_local_clients.sh).
 * Prints no endpoint, credential, path or name. Plain JVM (real org.json),
 * so the engine's JVM no-follow read probe applies as in LocalTorrentEngineTest.
 */
class LocalCrossClientInteropTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()

    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }

    @Test fun transmission(): Unit = journey(RemoteBackendKind.TRANSMISSION)
    @Test fun qbittorrent(): Unit = journey(RemoteBackendKind.QBITTORRENT)
    @Test fun deluge(): Unit = journey(RemoteBackendKind.DELUGE)
    @Test fun rtorrent(): Unit = journey(RemoteBackendKind.RTORRENT)

    private fun env(kind: RemoteBackendKind, name: String) =
        System.getenv("CHUNKS_INTEROP_" + kind.name + "_" + name).orEmpty()

    private fun remote(kind: RemoteBackendKind): TorrentClient {
        val credentials = RemoteCredentials(env(kind, "URL"), env(kind, "USER"), env(kind, "PASS"))
        return when (kind) {
            RemoteBackendKind.TRANSMISSION -> TransmissionClient(credentials)
            RemoteBackendKind.QBITTORRENT -> QBittorrentClient(credentials)
            RemoteBackendKind.DELUGE -> DelugeClient(credentials, env(kind, "DAEMON_ID").ifBlank {
                val ids = runBlocking { DelugeReadOnlyRpc(credentials, "unselected").registeredDaemonIds() }
                require(ids.size == 1) { "Select one exact Deluge daemon" }
                ids.single()
            })
            RemoteBackendKind.RTORRENT -> RTorrentClientAdapter(RTorrentRemote(credentials))
        }
    }

    private class Device(val engine: LocalTorrentEngine, val place: File,
                         val fence: LocalScreenObservationRevision, val client: LocalTorrentClient,
                         val ops: ClientOperations) {
        fun observe() { fence.acceptObservation(fence.snapshot(), engine.list()) }
    }

    private fun device(name: String): Device {
        val engine = LocalTorrentEngine(LocalEngineConfig(
            listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true
        )).also { it.start(); engines += it }
        val place = temp.newFolder(name).canonicalFile
        val fence = LocalScreenObservationRevision()
        val client = LocalTorrentClient({ engine }, places = object : LocalClientPlaces {
            override fun defaultDirectory() = place.path
            override fun resolve(directory: String?) =
                if (directory == null) place else LocalClientPlaces.within(listOf(place), directory)
        }, fence = LocalWriteFence.of(fence))
        // A magnet Start-on-Add is verified only after metadata arrives and payload starts.
        return Device(engine, place, fence, client, ClientOperations(client, 120, 500))
    }

    private suspend fun waitFor(what: String, timeoutMs: Long = 180_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!runCatching { condition() }.getOrDefault(false)) {
            check(System.currentTimeMillis() < deadline) { "Timed out: $what" }
            delay(250)
        }
    }

    private fun stage(kind: RemoteBackendKind, text: String) = println("CHUNKS_LOCAL_INTEROP: ${kind.name} $text")

    private fun journey(kind: RemoteBackendKind): Unit = runBlocking {
        val tracker = System.getenv("CHUNKS_INTEROP_TRACKER").orEmpty()
        val payloadDir = env(kind, "PAYLOAD_DIR")
        assumeTrue("Real ${kind.name} client not configured",
            env(kind, "URL").isNotBlank() && env(kind, "PASS").isNotBlank() && tracker.isNotBlank() &&
                payloadDir.isNotBlank() && File(payloadDir).isDirectory)
        val remote = remote(kind)
        val remoteOps = ClientOperations(remote, 60, 500)
        val remoteCaps = remoteOps.session().capabilities

        // A unique random payload: its hash cannot collide with any existing job.
        val random = SecureRandom()
        val name = "chunks-local-interop-" + System.currentTimeMillis().toString(36) + "-" +
            Integer.toHexString(random.nextInt())
        val files = linkedMapOf(
            "one.bin" to ByteArray(260_000).also(random::nextBytes),
            "two/three.bin" to ByteArray(70_001).also(random::nextBytes))
        val remoteRoot = File(payloadDir, name)
        for ((path, bytes) in files) File(remoteRoot, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
        val torrent = TorrentBuilder().path(remoteRoot).pieceSize(16 * 1024).flags(TorrentBuilder.V1_ONLY)
            .addTracker(tracker).generate().entry().bencode()
        val hash = ClientAddSource.TorrentFile(torrent).infoHash
        var remoteHasJob = false
        try {
            // 1. The real remote client hash-checks the payload already in its folder and seeds it.
            val seeded = remoteOps.add(ClientAddRequest(ClientAddSource.TorrentFile(torrent),
                File(payloadDir).canonicalPath, startNow = true))
            remoteHasJob = seeded !is ClientOperationOutcome.Refused
            assertTrue("$seeded", seeded is ClientOperationOutcome.Verified || seeded is ClientOperationOutcome.Pending)
            waitFor("remote verified its payload and seeds") {
                val row = remote.torrent(hash)
                row?.runState == ClientRunState.SEEDING && row.completedBytes == row.totalBytes
            }
            stage(kind, "remote seeding verified")

            // 2. remote → Local job Move: Local downloads every piece from the remote client.
            val local = device("local")
            val inbound = ClientJobTransfer(remoteOps, remote, local.ops, local.client, "remote-" + kind.name, "local")
            val copied = inbound.copy(hash, null, startOnTarget = true)
            assertTrue("$copied", copied is ClientJobTransfer.CopyResult.Copied)
            local.observe()
            waitFor("Local downloaded every piece from the remote client") {
                local.client.torrent(hash)?.runState == ClientRunState.SEEDING
            }
            assertTrue(local.engine.flushCompletedForLibraryRead(hash))
            for ((path, bytes) in files) assertArrayEquals("bytes from the remote client",
                bytes, File(local.place, "$name/$path").readBytes())
            stage(kind, "Local completed all pieces from remote")
            assertNotNull("Copy alone keeps the remote job", remote.torrent(hash))
            val remoteRetired = inbound.retireSource(hash, copied as ClientJobTransfer.CopyResult.Copied)
            assertTrue("$remoteRetired", remoteRetired is ClientOperationOutcome.Verified)
            remoteHasJob = false
            assertNull(remote.torrent(hash))
            for ((path, bytes) in files) assertArrayEquals("job-only retirement keeps remote files",
                bytes, File(remoteRoot, path).readBytes())
            stage(kind, "remote job retired, its files kept")

            // 3. Local → remote job Move: the remote client re-downloads every piece from Local.
            assertTrue(remoteRoot.deleteRecursively())
            val outbound = ClientJobTransfer(local.ops, local.client, remoteOps, remote, "local", "remote-" + kind.name)
            val back = outbound.copy(hash, File(payloadDir).canonicalPath, startOnTarget = true)
            assertTrue("$back", back is ClientJobTransfer.CopyResult.Copied)
            remoteHasJob = true
            assertTrue("Local exports its own metainfo", (back as ClientJobTransfer.CopyResult.Copied).viaMetainfo)
            var probes = 0
            waitFor("remote downloaded every piece from Local") {
                val row = remote.torrent(hash)
                if (probes++ % 40 == 0) stage(kind, "probe remote=${row?.runState}/${row?.completedBytes}/${row?.totalBytes} " +
                    "local=${local.engine.status(hash)?.let { "${it.state} peers=${it.peers} up=${it.payloadUploadedBytes}" }}")
                row != null && row.totalBytes != null && row.completedBytes == row.totalBytes
            }
            waitFor("remote payload flushed", 60_000) {
                files.all { (path, bytes) -> File(remoteRoot, path).let { it.isFile && it.readBytes().contentEquals(bytes) } }
            }
            stage(kind, "remote completed all pieces from Local")
            val localRetired = outbound.retireSource(hash, back)
            assertTrue("$localRetired", localRetired is ClientOperationOutcome.Verified)
            assertNull(local.client.torrent(hash))
            for ((path, bytes) in files) assertArrayEquals("job-only retirement keeps Local files",
                bytes, File(local.place, "$name/$path").readBytes())
            stage(kind, "Local job retired, its files kept")
        } finally {
            if (remoteHasJob) runCatching {
                remoteOps.remove(hash, deleteData = ClientCapability.REMOVE_WITH_DATA in remoteCaps)
            }
            remoteRoot.deleteRecursively()
        }
    }
}
