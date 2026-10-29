package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.BatchMoveRemoval
import io.github.gamesbyian.chunks.model.TransferMove
import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Server-side batch Move removal on the disposable loopback rTorrent rig only. */
class RTorrentDisposableBatchMoveTest {
    private fun remote(): Pair<RTorrentRemote, File> {
        val address = System.getenv("CHUNKS_DISPOSABLE_RT_URL").orEmpty()
        assumeTrue("Runs only inside the disposable rTorrent rig", address.isNotBlank())
        val uri = URI(address)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 18080 &&
            uri.path == "/RPC2" && uri.rawQuery == null && uri.userInfo == null) {
            "Refusing to mutate outside the disposable loopback rig"
        }
        val target = File(System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR").orEmpty())
        require(target.isAbsolute && target.path.contains("chunks-data") && target.isDirectory)
        return RTorrentRemote(RemoteCredentials(address, "", "")) to target
    }

    private fun torrent(name: String, payload: ByteArray): ByteArray {
        val piece = java.security.MessageDigest.getInstance("SHA-1").digest(payload)
        val info = "d6:lengthi${payload.size}e4:name${name.length}:${name}12:piece lengthi16384e6:pieces20:"
            .toByteArray(Charsets.ISO_8859_1) + piece + "e".toByteArray()
        return "d8:announce27:http://127.0.0.1:9/announce4:info".toByteArray() + info + "e".toByteArray()
    }

    @Test fun removesOnlyConfirmedServerJobsAndKeepsTheirFiles() = runBlocking {
        val (rtorrent, root) = remote()
        val stamp = System.nanoTime()
        val records = (1..2).map { n ->
            val name = "move-$n-$stamp.txt"
            val payload = name.toByteArray()
            File(root, name).writeBytes(payload)
            val bytes = torrent(name, payload)
            val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
            val added = rtorrent.loadStoppedTorrentBytes(bytes, root.absolutePath)
            assertTrue("$added", added is RemoteAddOutcome.VerifiedStopped || added is RemoteAddOutcome.PendingVerification)
            TransferMove.start(TransferMove.Direction.SERVER_TO_PHONE, hash, name, "a".repeat(64), "rig")
                .copy(stage = TransferMove.Stage.TARGET_VERIFIED)
        }
        repeat(30) {
            if (rtorrent.listTransfers("main").count { r -> records.any { it.infoHash.equals(r.hash, true) } } < 2)
                Thread.sleep(300)
        }
        val forgotten = mutableListOf<String>()
        // The second item's "phone job" is gone: its server job must be kept.
        val report = BatchMoveRemoval.run(records,
            observeTarget = { h -> if (h == records[0].infoHash) TransferMove.Observation.Present else TransferMove.Observation.Absent },
            removeSource = { h ->
                val t = rtorrent.listTransfers("main").first { it.hash.equals(h, true) }
                rtorrent.eraseFromSession(t.hash, t.name, t.directory)
            },
            observeSource = { h -> if (rtorrent.listTransfers("main").any { it.hash.equals(h, true) })
                TransferMove.Observation.Present else TransferMove.Observation.Absent },
            save = { true }, forget = { forgotten += it.infoHash })
        assertEquals(listOf(BatchMoveRemoval.Result.REMOVED, BatchMoveRemoval.Result.TARGET_NOT_CONFIRMED),
            report.results.map { it.second })
        val listed = rtorrent.listTransfers("main").map { it.hash.lowercase() }
        assertTrue("first job erased from the session", records[0].infoHash !in listed)
        assertTrue("second job kept", records[1].infoHash in listed)
        assertTrue("payload of the removed job still on disk", File(root, records[0].name).exists())
        assertEquals(listOf(records[0].infoHash), forgotten)
    }
}
