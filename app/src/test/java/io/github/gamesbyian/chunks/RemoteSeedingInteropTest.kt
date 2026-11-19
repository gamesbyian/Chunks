package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.RTorrentClientAdapter
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.seeding.RTorrentSeedingPort
import io.github.gamesbyian.chunks.seeding.SeedingController
import io.github.gamesbyian.chunks.seeding.RemoteSeeding
import io.github.gamesbyian.chunks.seeding.SeedingPolicy
import java.io.File
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/**
 * Remote Smart Seeding against a REAL rTorrent: its own d.up.total/d.down.total
 * counters from real piece traffic drive one verified, at-most-once automatic
 * Stop through the shared controller and the exact-hash setStarted readback.
 *
 * rTorrent first downloads every piece from a Local libtorrent seeder (ratio 0,
 * no stop), then uploads them to a fresh Local leech until its real ratio passes
 * the goal. Jobs present before activation are never touched. Only the exact
 * disposable fixture job and its files are removed afterwards.
 *
 * Plain JUnit (never Robolectric): the desktop libtorrent JNI library binds to one
 * classloader, and loading it in a Robolectric sandbox breaks later native tests in
 * the same fork. The opt-in gate and persistence are covered by RemoteSeedingTickTest.
 *
 * Skipped unless the loopback rig is configured (tools/interop/start_local_clients.sh
 * + local_env.sh): CHUNKS_INTEROP_RTORRENT_URL/_PASS/_PAYLOAD_DIR and CHUNKS_INTEROP_TRACKER.
 */
class RemoteSeedingInteropTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = mutableListOf<LocalTorrentEngine>()
    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }

    private fun env(name: String) = System.getenv("CHUNKS_INTEROP_RTORRENT_$name").orEmpty()
    private fun stage(text: String) = println("CHUNKS_REMOTE_SEEDING_INTEROP: $text")

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true, minReconnectSeconds = 1
    )).also { it.start(); engines += it }

    private suspend fun waitFor(what: String, timeoutMs: Long = 180_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!runCatching { condition() }.getOrDefault(false)) {
            check(System.currentTimeMillis() < deadline) { "Timed out: $what" }
            delay(500)
        }
    }

    @Test fun aRealRatioStopsTheNewRTorrentJobExactlyOnce(): Unit = runBlocking {
        val tracker = System.getenv("CHUNKS_INTEROP_TRACKER").orEmpty()
        val payloadDir = env("PAYLOAD_DIR")
        assumeTrue("Real rTorrent not configured", env("URL").isNotBlank() && env("PASS").isNotBlank() &&
            tracker.isNotBlank() && File(payloadDir).isDirectory)
        val remote = RTorrentRemote(RemoteCredentials(env("URL"), env("USER"), env("PASS")))
        val client = RTorrentClientAdapter(remote)
        val ops = ClientOperations(client, 60, 500)
        val environment = requireNotNull(RemoteSeeding.environmentOf(remote))
        val store = InteropAssignmentStore()
        val default = SeedingPolicy(ratioGoal = BigDecimal("0.5"))
        // Exactly what RemoteSeeding.tick does once its per-server switch is on.
        fun tick(): RemoteSeeding.Tick {
            val port = RTorrentSeedingPort(remote, store.epoch() == null)
            SeedingController(port, store).tick(environment, emptyList(), default, System.currentTimeMillis())
            return if (port.stopAttempted) RemoteSeeding.Tick.STOP_ATTEMPTED else RemoteSeeding.Tick.NO_WRITE
        }
        val existing = remote.listTransfers().map { it.hash.lowercase() }
        assertEquals("activation tick", RemoteSeeding.Tick.NO_WRITE, tick())
        assertTrue("every pre-existing job stays unmanaged", existing.all { store.get(it)?.policy == null })
        stage("activated pre_existing=${existing.size}")

        val random = SecureRandom()
        val name = "chunks-remote-seeding-" + System.currentTimeMillis().toString(36) + "-" + Integer.toHexString(random.nextInt())
        val source = temp.newFolder("seed-source")
        val payload = ByteArray(400_000).also(random::nextBytes)
        File(source, "$name/episode.bin").apply { parentFile.mkdirs(); writeBytes(payload) }
        val torrent = TorrentBuilder().path(File(source, name)).pieceSize(16 * 1024).flags(TorrentBuilder.V1_ONLY)
            .addTracker(tracker).generate().entry().bencode()
        val hash = ClientAddSource.TorrentFile(torrent).infoHash.lowercase()
        var remoteHasJob = false
        try {
            // 1. A new rTorrent job (after activation) with no data yet.
            val added = ops.add(ClientAddRequest(ClientAddSource.TorrentFile(torrent), File(payloadDir).canonicalPath, startNow = true))
            remoteHasJob = added !is ClientOperationOutcome.Refused
            assertTrue("$added", added is ClientOperationOutcome.Verified || added is ClientOperationOutcome.Pending)

            // 2. A Local seeder dials rTorrent; rTorrent downloads every piece (ratio 0).
            val seeder = engine()
            val seed = seeder.addStopped(torrent, source)
            assertTrue("$seed", seed is io.github.gamesbyian.chunks.engine.LocalAddResult.AddedStopped)
            seeder.resume(hash)
            // Loopback rTorrent never dials out; the Local side dials its fixed peer port.
            seeder.connectPeer(hash, InetSocketAddress("127.0.0.1", 51515))
            var probes = 0
            waitFor("rTorrent downloaded every piece from Local") {
                if (probes++ % 20 == 0) stage("probe remote=${client.torrent(hash)?.let { "${it.runState}/${it.completedBytes}/${it.totalBytes}" }} " +
                    "local=${seeder.status(hash)?.let { "${it.state} peers=${it.peers} up=${it.payloadUploadedBytes}" }}")
                client.torrent(hash)?.let { it.completedBytes == it.totalBytes && it.totalBytes != null } == true
            }
            tick()
            assertEquals("ratio 0 is below the goal: still running", true, client.torrent(hash)?.runState?.started)
            assertNull(store.get(hash)?.stopAttemptedReason)
            val evidence = remote.readSeedingEvidence(hash)
            stage("downloaded=${evidence.downloadedBytes == payload.size.toLong() || (evidence.downloadedBytes ?: 0) > 0} up_zero=${evidence.uploadedBytes == 0L}")
            seeder.close(); engines -= seeder

            // 3. A fresh Local leech pulls every piece back from rTorrent: real upload.
            val leech = engine()
            val leechPlace = temp.newFolder("leech")
            assertTrue(leech.addStoppedChecked(torrent, leechPlace) is io.github.gamesbyian.chunks.engine.LocalAddResult.AddedStopped)
            leech.resume(hash)
            leech.connectPeer(hash, InetSocketAddress("127.0.0.1", 51515))
            waitFor("Local leech got every piece from rTorrent") { leech.status(hash)?.progressPpm == 1_000_000 }

            // 4. The real ratio passes 0.5: exactly one verified automatic Stop.
            waitFor("rTorrent's own counters pass the ratio and the rule stops the job", 60_000) {
                val result = tick()
                if (store.get(hash)?.stoppedByRule != null) assertEquals(RemoteSeeding.Tick.STOP_ATTEMPTED, result)
                store.get(hash)?.stoppedByRule != null || store.get(hash)?.stopAttemptedReason != null
            }
            val assignment = requireNotNull(store.get(hash))
            assertTrue("verified stop: $assignment", assignment.stoppedByRule != null)
            assertEquals(ClientRunState.STOPPED, client.torrent(hash)?.runState)
            val proof = remote.readSeedingEvidence(hash)
            assertTrue("real counters: up >= 0.5 x down",
                BigDecimal(requireNotNull(proof.uploadedBytes)) >= BigDecimal("0.5").multiply(BigDecimal(requireNotNull(proof.downloadedBytes))))
            stage("stopped_by_rule=verified")

            // 5. A manual Start is the person's choice: the rule never stops it again.
            assertTrue(ops.setStarted(hash, true) is ClientOperationOutcome.Verified)
            repeat(3) { assertEquals("no further Stop", RemoteSeeding.Tick.NO_WRITE, tick()) }
            assertEquals(true, client.torrent(hash)?.runState?.started)
            stage("manual_start_respected=true")
            // Pre-existing jobs were never stopped by the controller.
            assertTrue(existing.all { store.get(it)?.stoppedByRule == null && store.get(it)?.stopAttemptedReason == null })
        } finally {
            if (remoteHasJob) {
                ops.setStarted(hash, false)
                ops.remove(hash, deleteData = false)
            }
            File(payloadDir, name).deleteRecursively()
        }
    }
}

/** In-memory assignment journal for plain-JUnit interop (no Android Context, no Robolectric sandbox). */
internal class InteropAssignmentStore : io.github.gamesbyian.chunks.seeding.SeedingController.AssignmentStore {
    private var epochMs: Long? = null
    private val map = HashMap<String, io.github.gamesbyian.chunks.seeding.SeedingController.Assignment>()
    override fun epoch() = epochMs
    override fun setEpoch(ms: Long): Boolean { if (epochMs != null) return false; epochMs = ms; return true }
    override fun get(infoHash: String) = map[infoHash]
    override fun put(infoHash: String, assignment: io.github.gamesbyian.chunks.seeding.SeedingController.Assignment): Boolean {
        map[infoHash] = assignment; return true
    }
}
