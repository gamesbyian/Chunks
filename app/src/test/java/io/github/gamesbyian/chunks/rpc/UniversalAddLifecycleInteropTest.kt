package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.model.UniversalAddAdmission
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddExecutor
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.UniversalAddLifecycle
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddRun
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ONE shared Universal Add lifecycle against real daemons: the app's own
 * executors ([ProtocolAddExecutor], [RTorrentAddExecutor]) and the
 * read-only [ProtocolAddObserver], driven exactly as the Add screens drive them.
 *
 * Per client: a reviewed stopped .torrent Add in an exact folder is VERIFIED
 * with positive provenance and a real piece-hash check of pre-placed payload;
 * a second ticket for the same hash is a known no-write (no second job);
 * and (protocol clients) a write whose reply is lost keeps the fence, then
 * one read-only Check status confirms the landed job without attributing or
 * resending it. Only the exact disposable fixtures are removed with data.
 *
 * Skipped unless CHUNKS_INTEROP_<BACKEND>_URL/_PASS are set
 * (tools/interop/start_local_clients.sh + local_env.sh). Prints no secrets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UniversalAddLifecycleInteropTest {
    private lateinit var kind: RemoteBackendKind
    private fun env(name: String) = System.getenv("CHUNKS_INTEROP_" + kind.name + "_" + name).orEmpty()
    private fun stage(text: String) = println("CHUNKS_LIFECYCLE_INTEROP: " + kind.name + " " + text)
    private fun credentials() = RemoteCredentials(env("URL"), env("USER"), env("PASS"))

    private fun protocolClient(): TorrentClient = when (kind) {
        RemoteBackendKind.TRANSMISSION -> TransmissionClient(credentials())
        RemoteBackendKind.QBITTORRENT -> QBittorrentClient(credentials())
        RemoteBackendKind.DELUGE -> {
            val daemon = env("DAEMON_ID").ifBlank {
                runBlocking { DelugeReadOnlyRpc(credentials(), "unselected").registeredDaemonIds() }.single()
            }
            DelugeClient(credentials(), daemon)
        }
        RemoteBackendKind.RTORRENT -> RTorrentClientAdapter(RTorrentRemote(credentials()))
    }

    private class Fixture(val result: TorrentMetainfoBuilder.Result, val name: String, val files: Map<String, ByteArray>)

    private fun fixture(): Fixture {
        val random = SecureRandom()
        val name = "chunks-lifecycle-" + System.currentTimeMillis().toString(36) + "-" + Integer.toHexString(random.nextInt())
        val files = linkedMapOf(
            "a.bin" to ByteArray(64 * 1024 + 17).also(random::nextBytes),
            "b.bin" to ByteArray(48 * 1024).also(random::nextBytes))
        return Fixture(TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(
            name = name,
            entries = files.map { (f, b) -> TorrentMetainfoBuilder.Entry(listOf(f), b.size.toLong()) { b.inputStream() } },
            trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")),
            pieceLength = 16 * 1024)), name, files)
    }

    /** Pre-place the exact payload in a folder the daemon can read. */
    private fun placed(fx: Fixture): String {
        val root = File(env("PAYLOAD_DIR")).also { assumeTrue("payload dir required", it.isDirectory) }
        val dir = File(root, "chunks-lifecycle-" + fx.result.infoHashV1.take(12)).apply { mkdirs() }
        File(dir, fx.name).mkdirs()
        fx.files.forEach { (f, b) -> File(dir, fx.name + "/" + f).writeBytes(b) }
        return dir.absolutePath
    }

    private fun admit(intent: UniversalAddIntent): Pair<UniversalAddCoordinator, UniversalAddCoordinator.Ticket> {
        val coordinator = UniversalAddCoordinator()
        val decision = UniversalAddAdmission.authorize(coordinator, intent,
            EnvironmentCapabilities.unprobed(intent.target.environment), EnvironmentAvailability.LIVE,
            { true }, System.currentTimeMillis())
        return coordinator to requireNotNull(decision.ticket) { decision.explanation }
    }

    /** The landed write's reply is lost, as after a dropped connection. */
    private class LoseReplyAfterWrite(private val real: TorrentClient) : TorrentClient by real {
        override suspend fun add(request: ClientAddRequest) {
            real.add(request)
            throw ClientWriteUncertainException("reply lost after the write")
        }
    }

    private suspend fun awaitPiecesVerified(client: TorrentClient, fx: Fixture) {
        for (i in 0 until 120) {
            val t = client.torrent(fx.result.infoHashV1)
            if (t != null && t.completedBytes == fx.result.totalBytes) {
                stage("pieces_verified=${fx.result.pieceCount}"); return
            }
            delay(500)
        }
        throw AssertionError("The client never verified the pre-placed pieces")
    }

    private suspend fun removeWithData(client: TorrentClient, fx: Fixture, folder: String) {
        val ops = ClientOperations(client, readbackAttempts = 40, readbackDelayMillis = 500)
        var removed = ops.remove(fx.result.infoHashV1, deleteData = true)
        // A client without Remove + data gets a session-only remove; the test
        // then deletes only its own disposable fixture folder below.
        if (removed is ClientOperationOutcome.Refused) removed = ops.remove(fx.result.infoHashV1, deleteData = false)
        stage("cleanup=" + removed.javaClass.simpleName)
        assertTrue("cleanup: $removed", removed is ClientOperationOutcome.Verified)
        File(folder).deleteRecursively()
    }

    @Test fun transmission() = protocolJourney(RemoteBackendKind.TRANSMISSION)
    @Test fun qbittorrent() = protocolJourney(RemoteBackendKind.QBITTORRENT)
    @Test fun deluge() = protocolJourney(RemoteBackendKind.DELUGE)
    @Test fun rtorrent() = rtorrentJourney()

    private fun protocolJourney(backend: RemoteBackendKind) = runBlocking {
        kind = backend
        assumeTrue("Only with an explicitly configured real client", env("URL").isNotBlank() && env("PASS").isNotBlank())
        val client = protocolClient()
        val ops = ClientOperations(client, readbackAttempts = 40, readbackDelayMillis = 500)
        val profile = "lifecycle-" + kind.name.lowercase()

        // 1. One reviewed stopped .torrent Add through the shared lifecycle.
        val fx = fixture()
        val folder = placed(fx)
        val request = ClientAddRequest(ClientAddSource.TorrentFile(fx.result.torrent), folder, startNow = false)
        val (coordinator, ticket) = admit(ProtocolUniversalAddAdapter.intent(profile, request, null))
        val run = UniversalAddLifecycle.run(coordinator, ticket, ProtocolAddExecutor(ops, request))
        assertTrue("$run", run is UniversalAddRun.Completed && run.result is ClientOperationOutcome.Verified)
        assertEquals(UniversalAddCoordinator.Phase.VERIFIED, coordinator.phase)
        stage("add=verified_new_registration")
        assertEquals(ClientOperationOutcome.Verified::class, ops.recheck(fx.result.infoHashV1)::class)
        awaitPiecesVerified(client, fx)

        // 2. A second reviewed ticket for the same hash never writes a second job.
        val (dupCoordinator, dupTicket) = admit(ProtocolUniversalAddAdapter.intent(profile, request, null))
        val dup = UniversalAddLifecycle.run(dupCoordinator, dupTicket, ProtocolAddExecutor(ops, request))
        assertTrue("$dup", dup is UniversalAddRun.Completed && dup.result is ClientOperationOutcome.Refused)
        assertEquals(UniversalAddCoordinator.Phase.REVIEWED, dupCoordinator.phase)
        assertEquals(1, client.torrents().count { it.hash.equals(fx.result.infoHashV1, ignoreCase = true) })
        stage("duplicate=known_no_write")

        // 3. A landed write with a lost reply keeps the fence; Check status confirms, never resends.
        val lost = fixture()
        val lostFolder = placed(lost)
        val lostRequest = ClientAddRequest(ClientAddSource.TorrentFile(lost.result.torrent), lostFolder, startNow = false)
        val (lostCoordinator, lostTicket) = admit(ProtocolUniversalAddAdapter.intent(profile, lostRequest, null))
        val lostOps = ClientOperations(LoseReplyAfterWrite(client), readbackAttempts = 40, readbackDelayMillis = 500)
        UniversalAddLifecycle.run(lostCoordinator, lostTicket, ProtocolAddExecutor(lostOps, lostRequest))
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, lostCoordinator.phase)
        // The client may still be checking pre-placed data; read only until it settles stopped.
        var readback: UniversalAddCoordinator.Readback? = null
        for (i in 0 until 60) {
            readback = UniversalAddLifecycle.recheck(lostCoordinator, ProtocolAddObserver(client))
            if (lostCoordinator.phase == UniversalAddCoordinator.Phase.SESSION_CONFIRMED) break
            delay(500)
        }
        assertEquals(UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED, readback)
        assertEquals(1, client.torrents().count { it.hash.equals(lost.result.infoHashV1, ignoreCase = true) })
        stage("lost_reply=fenced_then_session_confirmed_without_resend")

        removeWithData(client, fx, folder)
        removeWithData(client, lost, lostFolder)
    }

    private fun rtorrentJourney() = runBlocking {
        kind = RemoteBackendKind.RTORRENT
        assumeTrue("Only with an explicitly configured real rTorrent", env("URL").isNotBlank() && env("PASS").isNotBlank())
        val remote = RTorrentRemote(credentials())
        val client = RTorrentClientAdapter(remote)
        val fx = fixture()
        val folder = placed(fx)
        val intent = UniversalAddIntent(
            UniversalAddSource.metainfo(fx.result.torrent, fx.result.infoHashV1),
            UniversalAddTarget.AuthenticatedServer(remote.addAccountScope()),
            UniversalAddPlace.ServerCustom(folder), startWhenAdded = false, allowArtwork = true)
        val executor: UniversalAddExecutor<RTorrentAddExecutor.Result> = RTorrentAddExecutor(remote, false, null)
        val (coordinator, ticket) = admit(intent)
        val run = UniversalAddLifecycle.run(coordinator, ticket, executor)
        assertTrue("$run", run is UniversalAddRun.Completed)
        assertEquals(UniversalAddCoordinator.Phase.VERIFIED, coordinator.phase)
        stage("add=verified_new_registration")
        val ops = ClientOperations(client, readbackAttempts = 40, readbackDelayMillis = 500)
        ops.recheck(fx.result.infoHashV1)
        awaitPiecesVerified(client, fx)

        val (dupCoordinator, dupTicket) = admit(intent)
        val dup = UniversalAddLifecycle.run(dupCoordinator, dupTicket, RTorrentAddExecutor(remote, false, null))
        assertTrue("$dup", dup is UniversalAddRun.Completed &&
            (dup.result as? RTorrentAddExecutor.Result.Executed)?.existingWithoutWrite == true)
        assertEquals(UniversalAddCoordinator.Phase.REVIEWED, dupCoordinator.phase)
        stage("duplicate=known_no_write")
        removeWithData(client, fx, folder)
    }
}
