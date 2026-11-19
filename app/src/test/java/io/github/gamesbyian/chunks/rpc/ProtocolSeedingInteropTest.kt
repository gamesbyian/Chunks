package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.InteropAssignmentStore
import io.github.gamesbyian.chunks.seeding.ProtocolSeedingPort
import io.github.gamesbyian.chunks.seeding.SeedingController
import io.github.gamesbyian.chunks.seeding.RemoteSeeding
import io.github.gamesbyian.chunks.seeding.SeedingPolicy
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Remote Smart Seeding on REAL Transmission, qBittorrent and Deluge daemons:
 * each client's own seeding-time counter drives one verified, at-most-once
 * automatic Stop through the shared controller and ClientOperations readback.
 * A pre-placed, client-verified payload seeds at once; no peer is needed.
 * Jobs present before activation are never touched; only the exact fixture
 * is removed with its data.
 *
 * Plain JUnit (never Robolectric): the desktop libtorrent JNI library binds to one
 * classloader; the opt-in gate and persistence are covered by RemoteSeedingTickTest.
 *
 * Skipped unless CHUNKS_INTEROP_<BACKEND>_URL/_PASS/_PAYLOAD_DIR are set
 * (tools/interop/start_local_clients.sh + local_env.sh).
 */
class ProtocolSeedingInteropTest {
    private var store = InteropAssignmentStore()
    private var default = SeedingPolicy()
    /** Exactly what RemoteSeeding.tickProtocol does once its per-profile switch is on. */
    private fun tick(ops: ClientOperations, environment: String): RemoteSeeding.Tick {
        val port = ProtocolSeedingPort(ops, store.epoch() == null)
        SeedingController(port, store).tick(environment, emptyList(), default, System.currentTimeMillis())
        return if (port.stopAttempted) RemoteSeeding.Tick.STOP_ATTEMPTED else RemoteSeeding.Tick.NO_WRITE
    }

    private lateinit var kind: RemoteBackendKind
    private fun env(name: String) = System.getenv("CHUNKS_INTEROP_" + kind.name + "_" + name).orEmpty()
    private fun stage(text: String) = println("CHUNKS_PROTOCOL_SEEDING_INTEROP: " + kind.name + " " + text)

    private fun client(): TorrentClient {
        val credentials = RemoteCredentials(env("URL"), env("USER"), env("PASS"))
        return when (kind) {
            RemoteBackendKind.TRANSMISSION -> TransmissionClient(credentials)
            RemoteBackendKind.QBITTORRENT -> QBittorrentClient(credentials)
            RemoteBackendKind.DELUGE -> DelugeClient(credentials, env("DAEMON_ID").ifBlank {
                runBlocking { DelugeReadOnlyRpc(credentials, "unselected").registeredDaemonIds() }.single() })
            RemoteBackendKind.RTORRENT -> error("rTorrent has its own port")
        }
    }

    private suspend fun waitFor(what: String, timeoutMs: Long = 120_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!runCatching { condition() }.getOrDefault(false)) {
            check(System.currentTimeMillis() < deadline) { "Timed out: $what" }
            delay(1_000)
        }
    }

    private val engines = mutableListOf<io.github.gamesbyian.chunks.engine.LocalTorrentEngine>()
    @org.junit.Before fun native() = io.github.gamesbyian.chunks.engine.DesktopLibtorrent.ensureLoaded()
    @org.junit.After fun closeEngines() { engines.forEach { it.close() } }
    private fun engine() = io.github.gamesbyian.chunks.engine.LocalTorrentEngine(io.github.gamesbyian.chunks.engine.LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true, minReconnectSeconds = 1
    )).also { it.start(); engines += it }

    /** Deluge refreshes seeding time for an idle seed: a time limit stops it. */
    @Test fun deluge() = timeJourney(RemoteBackendKind.DELUGE)

    /**
     * Transmission 4.0.5 (loopback rig) keeps secondsSeeding at 0 for an idle seed
     * verified at Add, and the rig cannot connect a peer to it. Negative control:
     * real counters are read, and a time rule never fabricates a Stop.
     */
    @Test fun transmission() = timeJourney(RemoteBackendKind.TRANSMISSION, expectStop = false)

    /** qBittorrent: a ratio Stop from its own counters after real piece traffic both ways. */
    @Test fun qbittorrent() = ratioJourney(RemoteBackendKind.QBITTORRENT, peerPort = 51514)

    private fun timeJourney(backend: RemoteBackendKind, expectStop: Boolean = true) = runBlocking {
        kind = backend
        val root = File(env("PAYLOAD_DIR"))
        assumeTrue("Only with an explicitly configured real client",
            env("URL").isNotBlank() && env("PASS").isNotBlank() && root.isDirectory)
        val client = client()
        val ops = ClientOperations(client, readbackAttempts = 40, readbackDelayMillis = 500)
        val environment = RemoteSeeding.protocolEnvironment("seeding-interop-" + kind.name.lowercase())
        store = InteropAssignmentStore()
        default = SeedingPolicy(maxSeedSeconds = 8)
        val existing = client.torrents().map { it.hash.lowercase() }
        assertEquals("activation tick", RemoteSeeding.Tick.NO_WRITE, tick(ops, environment))
        val assignments = store
        assertTrue(existing.all { assignments.get(it)?.policy == null })

        val random = SecureRandom()
        val name = "chunks-protocol-seeding-" + System.currentTimeMillis().toString(36) + "-" + Integer.toHexString(random.nextInt())
        val bytes = ByteArray(96 * 1024 + 5).also(random::nextBytes)
        val fx = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
            listOf(TorrentMetainfoBuilder.Entry(listOf("a.bin"), bytes.size.toLong()) { bytes.inputStream() }),
            trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024))
        val hash = fx.infoHashV1.lowercase()
        val folder = File(root, "chunks-seeding-" + hash.take(12)).apply { mkdirs() }
        File(folder, "$name/a.bin").apply { parentFile.mkdirs(); writeBytes(bytes) }
        try {
            val added = ops.add(ClientAddRequest(ClientAddSource.TorrentFile(fx.torrent), folder.absolutePath, startNow = true))
            assertTrue("$added", added is ClientOperationOutcome.Verified || added is ClientOperationOutcome.Pending)
            waitFor("the client verified the pre-placed pieces and seeds") {
                client.seedingFacts(hash)?.seeding == true
            }
            val facts = requireNotNull(client.seedingFacts(hash))
            assertNotNull("uploaded counter", facts.uploadedBytes)
            assertNotNull("downloaded counter", facts.downloadedBytes)
            assertNotNull("seeding time", facts.seedingSeconds)
            stage("counters up=${facts.uploadedBytes != null} down=${facts.downloadedBytes != null} " +
                "seed_time=${facts.seedingSeconds != null} private=${facts.isPrivate} public_trackers=${facts.publicTrackerUrls?.size}")

            if (!expectStop) {
                repeat(12) {
                    assertEquals("an unadvanced time counter never stops", RemoteSeeding.Tick.NO_WRITE,
                        tick(ops, environment))
                    delay(1_000)
                }
                val a = requireNotNull(assignments.get(hash))
                assertTrue("managed, never stopped: $a", a.policy != null && a.stopAttemptedReason == null)
                assertEquals(true, client.torrent(hash)?.runState?.started)
                stage("time_counter_seconds=${client.seedingFacts(hash)?.seedingSeconds} no_false_stop=true")
                return@runBlocking
            }
            var probes = 0
            waitFor("the client's own seeding time passes 8 s and the rule stops the job") {
                if (probes++ % 10 == 0) client.seedingFacts(hash)?.let {
                    stage("probe seeding=${it.seeding} seconds=${it.seedingSeconds} note=${assignments.get(hash)?.note} " +
                        "source=${assignments.get(hash)?.source} policy=${assignments.get(hash)?.policy?.maxSeedSeconds}")
                }
                val tick = tick(ops, environment)
                val a = assignments.get(hash)
                if (a?.stoppedByRule != null) assertEquals(RemoteSeeding.Tick.STOP_ATTEMPTED, tick)
                a?.stoppedByRule != null || a?.stopAttemptedReason != null
            }
            val assignment = requireNotNull(assignments.get(hash))
            assertTrue("verified stop: $assignment", assignment.stoppedByRule != null)
            assertEquals(ClientRunState.STOPPED, client.torrent(hash)?.runState)
            assertTrue("stopped by its own time counter", (client.seedingFacts(hash)?.seedingSeconds ?: 0) >= 8)
            stage("stopped_by_rule=verified")

            assertTrue(ops.setStarted(hash, true) is ClientOperationOutcome.Verified)
            repeat(3) {
                assertEquals("no further Stop", RemoteSeeding.Tick.NO_WRITE, tick(ops, environment))
            }
            assertEquals(true, client.torrent(hash)?.runState?.started)
            assertTrue(existing.all { assignments.get(it)?.stoppedByRule == null && assignments.get(it)?.stopAttemptedReason == null })
            stage("manual_start_respected=true")
        } finally {
            var removed = ops.remove(hash, deleteData = true)
            if (removed is ClientOperationOutcome.Refused) removed = ops.remove(hash, deleteData = false)
            folder.deleteRecursively()
            stage("cleanup=" + removed.javaClass.simpleName)
        }
    }

    private fun ratioJourney(backend: RemoteBackendKind, peerPort: Int) = runBlocking {
        kind = backend
        val root = File(env("PAYLOAD_DIR"))
        assumeTrue("Only with an explicitly configured real client",
            env("URL").isNotBlank() && env("PASS").isNotBlank() && root.isDirectory)
        val client = client()
        val ops = ClientOperations(client, readbackAttempts = 40, readbackDelayMillis = 500)
        val environment = RemoteSeeding.protocolEnvironment("seeding-ratio-interop-" + kind.name.lowercase())
        store = InteropAssignmentStore()
        default = SeedingPolicy(ratioGoal = java.math.BigDecimal("0.5"))
        assertEquals(RemoteSeeding.Tick.NO_WRITE, tick(ops, environment))
        val assignments = store

        val random = SecureRandom()
        val name = "chunks-protocol-ratio-" + System.currentTimeMillis().toString(36) + "-" + Integer.toHexString(random.nextInt())
        val source = java.nio.file.Files.createTempDirectory("chunks-ratio-seed").toFile()
        val payload = ByteArray(400_000).also(random::nextBytes)
        File(source, "$name/episode.bin").apply { parentFile.mkdirs(); writeBytes(payload) }
        val torrent = org.libtorrent4j.TorrentBuilder().path(File(source, name)).pieceSize(16 * 1024)
            .flags(org.libtorrent4j.TorrentBuilder.V1_ONLY).addTracker("http://127.0.0.1:1/announce")
            .generate().entry().bencode()
        val hash = ClientAddSource.TorrentFile(torrent).infoHash.lowercase()
        val folder = File(root, "chunks-ratio-" + hash.take(12)).apply { mkdirs() }
        try {
            val added = ops.add(ClientAddRequest(ClientAddSource.TorrentFile(torrent), folder.absolutePath, startNow = true))
            assertTrue("$added", added is ClientOperationOutcome.Verified || added is ClientOperationOutcome.Pending)
            // The client downloads every piece from a Local seeder that dials it: ratio 0.
            val seeder = engine()
            assertTrue(seeder.addStopped(torrent, source) is io.github.gamesbyian.chunks.engine.LocalAddResult.AddedStopped)
            seeder.resume(hash)
            seeder.connectPeer(hash, java.net.InetSocketAddress("127.0.0.1", peerPort))
            waitFor("the client downloaded every piece from Local") {
                client.torrent(hash)?.let { it.totalBytes != null && it.completedBytes == it.totalBytes } == true
            }
            tick(ops, environment)
            assertEquals("ratio 0: still running", true, client.torrent(hash)?.runState?.started)
            seeder.close(); engines -= seeder
            // A fresh Local leech pulls every piece back: the client's own upload counter grows.
            val leech = engine()
            val leechPlace = java.nio.file.Files.createTempDirectory("chunks-ratio-leech").toFile()
            assertTrue(leech.addStoppedChecked(torrent, leechPlace) is io.github.gamesbyian.chunks.engine.LocalAddResult.AddedStopped)
            leech.resume(hash)
            leech.connectPeer(hash, java.net.InetSocketAddress("127.0.0.1", peerPort))
            waitFor("Local leech got every piece from the client") { leech.status(hash)?.progressPpm == 1_000_000 }
            waitFor("the client's own counters pass the ratio and the rule stops the job", 90_000) {
                tick(ops, environment)
                assignments.get(hash)?.let { it.stoppedByRule != null || it.stopAttemptedReason != null } == true
            }
            assertTrue("verified stop", assignments.get(hash)?.stoppedByRule != null)
            assertEquals(ClientRunState.STOPPED, client.torrent(hash)?.runState)
            val facts = requireNotNull(client.seedingFacts(hash))
            assertTrue("real counters: up >= 0.5 x down", java.math.BigDecimal(requireNotNull(facts.uploadedBytes)) >=
                java.math.BigDecimal("0.5").multiply(java.math.BigDecimal(requireNotNull(facts.downloadedBytes))))
            stage("ratio_stop=verified")
            assertTrue(ops.setStarted(hash, true) is ClientOperationOutcome.Verified)
            repeat(3) { assertEquals(RemoteSeeding.Tick.NO_WRITE, tick(ops, environment)) }
            assertEquals(true, client.torrent(hash)?.runState?.started)
            stage("manual_start_respected=true")
        } finally {
            var removed = ops.remove(hash, deleteData = true)
            if (removed is ClientOperationOutcome.Refused) removed = ops.remove(hash, deleteData = false)
            folder.deleteRecursively(); source.deleteRecursively()
            stage("cleanup=" + removed.javaClass.simpleName)
        }
    }
}
