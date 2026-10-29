package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.BatchServerCopy
import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Batch Copy Transfer to server against the disposable loopback rTorrent rig only. */
class RTorrentDisposableBatchCopyTest {
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

    private fun torrent(name: String): ByteArray {
        val payload = name.toByteArray()
        val piece = java.security.MessageDigest.getInstance("SHA-1").digest(payload)
        val info = "d6:lengthi${payload.size}e4:name${name.length}:${name}12:piece lengthi16384e6:pieces20:"
            .toByteArray(Charsets.ISO_8859_1) + piece + "e".toByteArray()
        return "d8:announce27:http://127.0.0.1:9/announce4:info".toByteArray() + info + "e".toByteArray()
    }

    @Test fun batchAddsStoppedJobsAtTheServerDefaultAndSkipsOnesAlreadyThere() = runBlocking {
        val (rtorrent, _) = remote()
        val stamp = System.nanoTime()
        val definitions = listOf("copy-a-$stamp.txt", "copy-b-$stamp.txt").associate { name ->
            val bytes = torrent(name)
            TorrentMetainfoReader.preview(bytes).infoHashV1.lowercase() to (name to bytes)
        }
        val (firstHash, first) = definitions.entries.first().let { it.key to it.value }
        val place = requireNotNull(rtorrent.defaultDirectory())
        // One is already on the server: the batch must not add it again.
        val pre = rtorrent.loadStoppedTorrentBytes(first.second, place, useServerDefault = true)
        assertTrue("$pre", pre is RemoteAddOutcome.VerifiedStopped || pre is RemoteAddOutcome.PendingVerification)
        repeat(30) {
            if (rtorrent.listTransfers("main").none { it.hash.equals(firstHash, true) }) Thread.sleep(300)
        }
        val before = rtorrent.listTransfers("main").map { it.hash.lowercase() to it.started }.toSet()

        val report = BatchServerCopy.run(
            definitions.map { (hash, value) -> BatchServerCopy.Item(hash, value.first) },
            exportTorrent = { hash -> definitions[hash.lowercase()]?.second },
            addStopped = { bytes ->
                rtorrent.loadStoppedTorrentBytes(bytes, requireNotNull(rtorrent.defaultDirectory()),
                    useServerDefault = true)
            })
        assertEquals(listOf(BatchServerCopy.Result.ALREADY_ON_SERVER, BatchServerCopy.Result.ADDED),
            report.results.map { it.second })
        val rows = rtorrent.listTransfers("main").filter { it.hash.lowercase() in definitions.keys }
        assertEquals(2, rows.size)
        assertTrue("Copied jobs are stopped", rows.none { it.started })
        assertTrue("At the server default Place", rows.all { it.directory.trimEnd('/') == place.trimEnd('/') })
        // Nothing else on the server changed state.
        val after = rtorrent.listTransfers("main").filter { it.hash.lowercase() !in definitions.keys }
            .map { it.hash.lowercase() to it.started }.toSet()
        assertEquals(before.filter { it.first !in definitions.keys }.toSet(), after)
    }
}
