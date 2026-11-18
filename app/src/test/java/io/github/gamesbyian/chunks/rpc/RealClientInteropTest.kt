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
            RemoteBackendKind.RTORRENT -> RTorrentClientAdapter(RTorrentRemote(credentials))
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
            // A dead loopback tracker: rTorrent with DHT off silently drops a
            // trackerless .torrent (load.raw_verbose still answers 0).
            trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")),
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
    @Test fun rtorrent() = journey(RemoteBackendKind.RTORRENT)

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
        if (kind != RemoteBackendKind.RTORRENT) {
            val connected = io.github.gamesbyian.chunks.RemoteProtocolConnect.connect(
                kind, env("URL"), env("USER"), env("PASS"), env("DAEMON_ID"))
            assertEquals(kind, connected.read.backend)
            assertTrue(connected.read.snapshot.canClaimCompleteLiveInventory)
            assertTrue(connected.read.snapshot.transfers.any { it.key.infoHash == hash })
            stage("app_connect=verified_inventory_contains_fixture")
        }

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

        // 9. Remove + data for the exact disposable fixture only; where the
        //    client cannot delete data, session Remove must leave files intact.
        if (ClientCapability.REMOVE_WITH_DATA !in session.capabilities) {
            val refused = ops.remove(hash, deleteData = true)
            assertTrue("unsupported Remove + data: $refused", refused is ClientOperationOutcome.Refused)
            assertVerified("remove_session_only", ops.remove(hash, deleteData = false))
            if (payloadRoot != null) {
                assertTrue("Session Remove must keep payload files",
                    fx.files.keys.all { File(finalDir, fx.name + "/" + it).isFile })
                stage("payload_kept=independently_observed")
            }
        } else {
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

    private fun envOf(k: RemoteBackendKind, name: String) =
        System.getenv("CHUNKS_INTEROP_" + k.name + "_" + name).orEmpty()

    private fun clientOf(k: RemoteBackendKind): RemoteTorrentClient {
        val saved = if (this::kind.isInitialized) kind else null
        kind = k
        try { return client() } finally { if (saved != null) kind = saved }
    }

    /**
     * Job-only Move across real engines, as a ring over every locally
     * available client (Transmission → qBittorrent → Deluge → rTorrent → …).
     * The payload exists ONLY on the source; the target receives the job
     * (exported .torrent or the job's own magnet), downloads it from the
     * source over real BitTorrent via a loopback tracker, and must verify
     * every piece. The source is then retired session-only with its files
     * still on disk.
     */
    @Test fun crossClientJobMoveTransfersRealBytes() = runBlocking {
        val tracker = System.getenv("CHUNKS_INTEROP_TRACKER").orEmpty()
        val order = System.getenv("CHUNKS_INTEROP_RING").orEmpty().split(',').filter { it.isNotBlank() }
            .map { RemoteBackendKind.valueOf(it.trim()) }.ifEmpty {
                // Every engine is both source and target. On loopback neither
                // rTorrent nor Transmission dials the other, so rTorrent hands
                // to Deluge and Deluge to Transmission instead.
                listOf(RemoteBackendKind.TRANSMISSION, RemoteBackendKind.QBITTORRENT,
                    RemoteBackendKind.RTORRENT, RemoteBackendKind.DELUGE)
            }
        val ring = order.filter {
            envOf(it, "URL").isNotBlank() && envOf(it, "PAYLOAD_DIR").isNotBlank()
        }
        assumeTrue("Needs two local real clients and the loopback tracker", tracker.isNotBlank() && ring.size >= 2)
        val pairs = ring.indices.map { ring[it] to ring[(it + 1) % ring.size] }
        for ((from, to) in pairs) {
            kind = from
            val src = clientOf(from)
            val dst = clientOf(to)
            val srcOps = RemoteClientOperations(src, 60, 500)
            val dstOps = RemoteClientOperations(dst, 60, 500)
            val random = SecureRandom()
            val name = "chunks-move-" + from.name.lowercase() + "-" + to.name.lowercase() + "-" +
                java.lang.Long.toString(System.nanoTime() and Long.MAX_VALUE, 36)
            val payload = ByteArray(200 * 1024 + 77).also(random::nextBytes)
            val built = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
                listOf(TorrentMetainfoBuilder.Entry(listOf(name), payload.size.toLong()) { payload.inputStream() }),
                trackerTiers = listOf(listOf(tracker)), pieceLength = 16 * 1024))
            val hash = built.infoHashV1
            val srcDir = File(envOf(from, "PAYLOAD_DIR"), "move-src-" + hash.take(10)).apply { mkdirs() }
            File(srcDir, name).writeBytes(payload)
            val dstDir = File(envOf(to, "PAYLOAD_DIR"), "move-dst-" + hash.take(10)).apply { mkdirs() }
            val tag = from.name + "->" + to.name

            assertVerified("$tag source_add", srcOps.add(ClientAddRequest(
                ClientAddSource.TorrentFile(built.torrent), srcDir.absolutePath, startNow = true)))
            var seeding = false
            for (i in 0 until 120) {
                val t = src.torrent(hash)
                if (t?.runState == ClientRunState.SEEDING) { seeding = true; break }
                if (i == 10) srcOps.recheck(hash)
                delay(500)
            }
            assertTrue("$tag: source never verified its payload", seeding)

            val transfer = ClientJobTransfer(srcOps, src, dstOps, dst,
                sourceClientId = "interop-$tag-source", targetClientId = "interop-$tag-target")
            val copied = transfer.copy(hash, dstDir.absolutePath, startOnTarget = true)
            assertTrue("$tag copy: $copied", copied is ClientJobTransfer.CopyResult.Copied)
            println("CHUNKS_INTEROP: MOVE $tag job_copied=verified via=" +
                if ((copied as ClientJobTransfer.CopyResult.Copied).viaMetainfo) "metainfo" else "magnet")

            var received = false
            for (i in 0 until 240) {
                // Loopback discovery: some clients only learn of a new peer
                // on their next announce. Ask both sides periodically.
                if (i % 10 == 2) {
                    runCatching { src.reannounce(hash) }
                    runCatching { dst.reannounce(hash) }
                }
                val t = dst.torrent(hash)
                if (t != null && t.runState == ClientRunState.SEEDING && t.completedBytes == payload.size.toLong()) {
                    received = true; break
                }
                delay(500)
            }
            assertTrue("$tag: target never downloaded and verified the payload from the source", received)
            assertTrue("$tag: target bytes differ", File(dstDir, name).readBytes().contentEquals(payload))
            println("CHUNKS_INTEROP: MOVE $tag target_downloaded_from_source=true pieces=${built.pieceCount} bytes_identical=true")

            assertVerified("$tag retire_source_session_only", transfer.retireSource(hash, copied as ClientJobTransfer.CopyResult.Copied))
            assertTrue("$tag: source payload must remain", File(srcDir, name).isFile)
            println("CHUNKS_INTEROP: MOVE $tag source_files_kept=independently_observed")

            // Disposable cleanup of the exact fixture on the target only.
            val caps = dstOps.session().capabilities
            dstOps.remove(hash, deleteData = ClientCapability.REMOVE_WITH_DATA in caps)
        }
    }
}
