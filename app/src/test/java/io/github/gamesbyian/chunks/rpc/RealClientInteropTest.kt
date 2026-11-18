package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Full mutation journey against ONE real Transmission, qBittorrent or Deluge
 * daemon through the shared [RemoteClientOperations] workflow.
 *
 * Each backend's test is skipped unless CHUNKS_INTEROP_<BACKEND>_URL and
 * _PASS are set (local daemons from tools/interop/start_local_clients.sh,
 * or the owner's disposable development Seedhost clients via a manual
 * workflow). Optional: _USER, _DAEMON_ID (Deluge), _PAYLOAD_DIR.
 * Each run builds a unique random payload torrent so its hash cannot collide
 * with any existing job. When CHUNKS_INTEROP_PAYLOAD_DIR is a directory the
 * daemon itself can read, the payload is written there first: then the
 * client's own recheck must hash-verify every piece (real piece evidence)
 * and Remove + data is independently confirmed on the filesystem.
 *
 * Never prints endpoints, credentials, paths or torrent names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealClientInteropTest {
    private lateinit var kind: RemoteBackendKind

    private fun env(name: String) = System.getenv("CHUNKS_INTEROP_" + kind.name + "_" + name).orEmpty()

    private fun stage(text: String) = println("CHUNKS_INTEROP: " + kind.name + " " + text)

    private fun client(password: String = env("PASS")): RemoteTorrentClient {
        val credentials = RemoteCredentials(env("URL"), env("USER"), password)
        return when (kind) {
            RemoteBackendKind.TRANSMISSION -> TransmissionClient(credentials)
            RemoteBackendKind.QBITTORRENT -> QBittorrentClient(credentials)
            RemoteBackendKind.DELUGE -> {
                val daemon = env("DAEMON_ID").ifBlank {
                    val ids = runBlocking { DelugeReadOnlyRpc(credentials, "unselected").registeredDaemonIds() }
                    require(ids.size == 1) { "Select one exact Deluge daemon (found ${ids.size})" }
                    ids.single()
                }
                DelugeClient(credentials, daemon)
            }
            RemoteBackendKind.RTORRENT -> error("rTorrent uses its established adapter")
        }
    }

    private class Fixture(val result: TorrentMetainfoBuilder.Result, val name: String, val files: Map<String, ByteArray>)

    private fun fixture(): Fixture {
        val random = SecureRandom()
        val name = "chunks-interop-" + System.currentTimeMillis().toString(36) +
            "-" + Integer.toHexString(random.nextInt())
        val files = linkedMapOf(
            "a.bin" to ByteArray(96 * 1024).also(random::nextBytes),
            "b.bin" to ByteArray(80 * 1024 + 123).also(random::nextBytes)
        )
        val result = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(
            name = name,
            entries = files.map { (file, bytes) ->
                TorrentMetainfoBuilder.Entry(listOf(file), bytes.size.toLong()) { bytes.inputStream() }
            },
            pieceLength = 16 * 1024
        ))
        return Fixture(result, name, files)
    }

    private fun assertVerified(label: String, outcome: ClientOperationOutcome) {
        assertTrue("$label: $outcome", outcome is ClientOperationOutcome.Verified)
        stage("$label=verified")
    }

    @Test fun transmission() = journey(RemoteBackendKind.TRANSMISSION)
    @Test fun qbittorrent() = journey(RemoteBackendKind.QBITTORRENT)
    @Test fun deluge() = journey(RemoteBackendKind.DELUGE)

    private fun journey(backend: RemoteBackendKind) = runBlocking {
        kind = backend
        assumeTrue("Only with an explicitly configured real client",
            env("URL").isNotBlank() && env("PASS").isNotBlank())
        val start = System.nanoTime()
        val client = client()
        val ops = RemoteClientOperations(client, readbackAttempts = 40, readbackDelayMillis = 500)
        val session = ops.session()
        stage("session version_present=${session.version.isNotBlank()} capabilities=" +
            session.capabilities.map { it.name }.sorted().joinToString(","))
        val before = client.torrents().map { it.hash }.toSet()
        stage("inventory_before=${before.size}")

        val fx = fixture()
        val hash = fx.result.infoHashV1
        assertFalse(hash in before)
        val payloadRoot = env("PAYLOAD_DIR").takeIf { it.isNotBlank() }?.let(::File)
            ?.takeIf { it.isDirectory }
        val directory: String = if (payloadRoot != null) {
            val dir = File(payloadRoot, "chunks-interop-" + hash.take(12)).apply { mkdirs() }
            val top = File(dir, fx.name).apply { mkdirs() }
            fx.files.forEach { (file, bytes) -> File(top, file).writeBytes(bytes) }
            dir.absolutePath
        } else {
            val base = requireNotNull(session.defaultDownloadDirectory) { "Client reported no default folder" }
            base.trimEnd('/') + "/chunks-interop-" + hash.take(12)
        }

        // 1. Add the .torrent STOPPED at an exact directory.
        val add = ops.add(ClientAddRequest(ClientAddSource.TorrentFile(fx.result.torrent), directory, startNow = false))
        assertVerified("add_torrent_stopped", add)
        assertEquals(ClientRunState.STOPPED, (add as ClientOperationOutcome.Verified).observed!!.runState)

        // 1b. The app's own Connect path (browser-style URL normalization,
        //     single-daemon adoption, authenticated complete read) sees it.
        val connected = io.github.gamesbyian.chunks.RemoteProtocolConnect.connect(
            kind, env("URL"), env("USER"), env("PASS"), env("DAEMON_ID"))
        assertEquals(kind, connected.read.backend)
        assertTrue(connected.read.snapshot.canClaimCompleteLiveInventory)
        assertTrue(connected.read.snapshot.transfers.any { it.key.infoHash == hash })
        stage("app_connect=verified_inventory_contains_fixture")

        // 2. Duplicate Add is refused without a second registration.
        val dup = ops.add(ClientAddRequest(ClientAddSource.TorrentFile(fx.result.torrent), directory, false))
        assertTrue("duplicate: $dup", dup is ClientOperationOutcome.Refused)
        stage("duplicate_add=refused")

        // 3. Files in metainfo order.
        val files = client.files(hash)
        assertEquals(listOf("a.bin", "b.bin"), files.sortedBy { it.index }.map { it.path.substringAfterLast('/') })
        assertEquals(fx.files.values.map { it.size.toLong() }, files.sortedBy { it.index }.map { it.sizeBytes })
        stage("files=2")

        // 4. Priorities: skip one, raise the other, then restore.
        assertVerified("priorities_set", ops.setFilePriorities(hash,
            mapOf(files[0].index to ClientFilePriority.SKIP, files[1].index to ClientFilePriority.HIGH)))
        assertVerified("priorities_restore", ops.setFilePriorities(hash,
            files.associate { it.index to ClientFilePriority.NORMAL }))

        // 5. Start, and with real payload, the client's own hash check
        //    must verify every piece and reach seeding.
        assertVerified("start", ops.setStarted(hash, true))
        if (payloadRoot != null) {
            assertVerified("recheck_requested", ops.recheck(hash))
            var seeded: ClientTorrent? = null
            for (i in 0 until 120) {
                val t = client.torrent(hash)
                if (t != null && t.runState == ClientRunState.SEEDING && t.completedBytes == fx.result.totalBytes) {
                    seeded = t; break
                }
                delay(500)
            }
            assertNotNull("Client never verified all pieces", seeded)
            stage("pieces_verified=${fx.result.pieceCount} seeding=true")
        }

        // 6. Stop.
        assertVerified("stop", ops.setStarted(hash, false))

        // 7. Move storage (real file relocation) where the client supports it.
        var finalDir = directory
        if (payloadRoot != null && ClientCapability.SET_LOCATION in session.capabilities) {
            val moved = File(payloadRoot, "chunks-interop-moved-" + hash.take(12)).apply { mkdirs() }
            assertVerified("set_location_move", ops.setLocation(hash, moved.absolutePath, moveData = true))
            var arrived = false
            for (i in 0 until 60) {
                if (fx.files.keys.all { File(moved, fx.name + "/" + it).isFile }) { arrived = true; break }
                delay(500)
            }
            assertTrue("Moved payload not found at new location", arrived)
            stage("payload_moved=independently_observed")
            finalDir = moved.absolutePath
        }

        // 8. Magnet Add (stopped) of a second unique identity, session remove only.
        val magnetFx = fixture()
        val magnet = requireNotNull(magnetFx.result.magnet)
        assertVerified("add_magnet_stopped",
            ops.add(ClientAddRequest(ClientAddSource.Magnet(magnet), directory, startNow = false)))
        assertVerified("remove_magnet_session_only", ops.remove(magnetFx.result.infoHashV1, deleteData = false))

        // 9. Remove + data for the exact disposable fixture only.
        assertVerified("remove_with_data", ops.remove(hash, deleteData = true))
        if (payloadRoot != null) {
            var gone = false
            for (i in 0 until 60) {
                if (fx.files.keys.none { File(finalDir, fx.name + "/" + it).exists() }) { gone = true; break }
                delay(500)
            }
            assertTrue("Payload still present after Remove + data", gone)
            stage("payload_deleted=independently_observed")
        }

        // 10. Negative controls: absent target and wrong password write nothing.
        val absent = ops.setStarted(hash, true)
        assertTrue("absent: $absent", absent is ClientOperationOutcome.Refused)
        stage("absent_target=refused")
        val bad = try { client("wrong-" + System.nanoTime()).session(); false } catch (_: ClientRefusedException) { true }
            catch (_: java.io.IOException) { true }
        assertTrue("Wrong password must not authenticate", bad)
        stage("wrong_password=refused")

        // 11. No unrelated inventory changed.
        val after = client.torrents().map { it.hash }.toSet()
        assertEquals(before, after)
        stage("inventory_after_unchanged=true duration_ms=${(System.nanoTime() - start) / 1_000_000}")
    }
}
