package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.InteropAssignmentStore
import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.seeding.ProtocolSeedingPort
import io.github.gamesbyian.chunks.seeding.RateLimit
import io.github.gamesbyian.chunks.seeding.RemoteSeeding
import io.github.gamesbyian.chunks.seeding.SeedingController
import io.github.gamesbyian.chunks.seeding.SeedingPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Smart Seeding's upload cap on the REAL Transmission, qBittorrent and Deluge
 * daemons: the client's own per-transfer limit (never a shared throttle) is
 * written once and confirmed by an independent read; a matching cap is not
 * rewritten; a cap the client cannot store exactly is never written; an
 * unrelated pre-existing transfer is untouched.
 *
 * Skipped unless CHUNKS_INTEROP_<BACKEND>_URL/_PASS/_PAYLOAD_DIR are set.
 */
class ProtocolUploadCapInteropTest {
    private lateinit var kind: RemoteBackendKind
    private fun env(name: String) = System.getenv("CHUNKS_INTEROP_" + kind.name + "_" + name).orEmpty()
    private fun stage(text: String) = println("CHUNKS_UPLOAD_CAP_INTEROP: " + kind.name + " " + text)

    private fun client(): TorrentClient {
        val credentials = RemoteCredentials(env("URL"), env("USER"), env("PASS"))
        return when (kind) {
            RemoteBackendKind.TRANSMISSION -> TransmissionClient(credentials)
            RemoteBackendKind.QBITTORRENT -> QBittorrentClient(credentials)
            RemoteBackendKind.DELUGE -> DelugeClient(credentials, env("DAEMON_ID").ifBlank {
                runBlocking { DelugeReadOnlyRpc(credentials, "unselected").registeredDaemonIds() }.single() })
            RemoteBackendKind.RTORRENT -> error("rTorrent has no per-transfer upload cap")
        }
    }

    @Test fun transmission() = journey(RemoteBackendKind.TRANSMISSION)
    @Test fun qbittorrent() = journey(RemoteBackendKind.QBITTORRENT)
    @Test fun deluge() = journey(RemoteBackendKind.DELUGE)

    private fun journey(backend: RemoteBackendKind) = runBlocking {
        kind = backend
        // A cap needs no payload: stopped fixture jobs at the client's own default folder, so the
        // same journey runs against a loopback daemon or a protected live Seedhost client.
        assumeTrue("Only with an explicitly configured real client",
            env("URL").isNotBlank() && env("PASS").isNotBlank())
        val client = client()
        val ops = ClientOperations(client, readbackAttempts = 40, readbackDelayMillis = 500)
        val environment = RemoteSeeding.protocolEnvironment("upload-cap-interop-" + kind.name.lowercase())
        val step = requireNotNull(client.uploadCapStepBytes()) { "this client exposes a per-transfer cap" }
        stage("step_bytes=$step")

        fun fixture(): TorrentMetainfoBuilder.Result {
            val random = SecureRandom()
            val name = "chunks-cap-fixture-" + System.nanoTime().toString(36)
            val bytes = ByteArray(40 * 1024 + 3).also(random::nextBytes)
            return TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
                listOf(TorrentMetainfoBuilder.Entry(listOf("a.bin"), bytes.size.toLong()) { bytes.inputStream() }),
                trackerTiers = emptyList(), pieceLength = 16 * 1024))
        }
        suspend fun addStopped(fx: TorrentMetainfoBuilder.Result) = ops.add(
            ClientAddRequest(ClientAddSource.TorrentFile(fx.torrent), null, startNow = false))
        // An unrelated job added BEFORE Smart Seeding was enabled: must stay unmanaged.
        val bystander = fixture()
        assertTrue(addStopped(bystander) is ClientOperationOutcome.Verified)
        val bystanderCapBefore = client.uploadCapBytesPerSecond(bystander.infoHashV1)

        // 128 000 B/s is exactly storable in bytes, KiB and kB.
        val cap = 128_000L
        val store = InteropAssignmentStore()
        val policy = SeedingPolicy(uploadLimit = RateLimit(cap, RateLimit.Unit.KB))
        fun tick(p: SeedingPolicy = policy): Boolean {
            val port = ProtocolSeedingPort(ops, store.epoch() == null)
            SeedingController(port, store).tick(environment, emptyList(), p, System.currentTimeMillis())
            return port.stopAttempted
        }
        assertEquals("activation tick writes nothing", false, tick())

        val fx = fixture()
        val hash = fx.infoHashV1
        try {
            assertTrue(addStopped(fx) is ClientOperationOutcome.Verified)
            assertEquals("no cap before the rule", 0L, client.uploadCapBytesPerSecond(hash))
            assertEquals("one cap write", true, tick())
            assertEquals("independent readback", cap, client.uploadCapBytesPerSecond(hash))
            assertEquals("no rule note on success", null, store.get(hash)?.note)
            stage("cap=written_once_and_read_back bytes=$cap")
            assertEquals("a matching cap is not rewritten", false, tick())
            stage("second_tick=no_write")
            assertEquals("bystander untouched", bystanderCapBefore, client.uploadCapBytesPerSecond(bystander.infoHashV1))
            stage("bystander=untouched")
        } finally {
            // Only these exact disposable fixture jobs; no payload was ever written.
            ops.remove(hash, deleteData = false)
            ops.remove(bystander.infoHashV1, deleteData = false)
        }

        // A cap the client cannot store exactly is never written (no rounding).
        if (step > 1) {
            val odd = fixture()
            try {
                val oddStore = InteropAssignmentStore()
                val oddPolicy = SeedingPolicy(uploadLimit = RateLimit(step + 1, RateLimit.Unit.BYTES))
                fun oddTick(): Boolean {
                    val port = ProtocolSeedingPort(ops, oddStore.epoch() == null)
                    SeedingController(port, oddStore).tick(environment + "-odd", emptyList(), oddPolicy, System.currentTimeMillis())
                    return port.stopAttempted
                }
                oddTick()
                assertTrue(addStopped(odd) is ClientOperationOutcome.Verified)
                assertEquals("nothing sent for an inexact cap", false, oddTick())
                assertEquals(0L, client.uploadCapBytesPerSecond(odd.infoHashV1))
                assertTrue(oddStore.get(odd.infoHashV1)?.note.orEmpty().contains("supported range"))
                stage("inexact_cap=not_written")
            } finally {
                ops.remove(odd.infoHashV1, deleteData = false)
            }
        }
    }
}
