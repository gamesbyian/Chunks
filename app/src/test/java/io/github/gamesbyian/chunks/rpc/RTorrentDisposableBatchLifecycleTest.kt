package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.RemoteBatchLifecycle
import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real batch Start/Stop on the disposable loopback rTorrent rig only. */
class RTorrentDisposableBatchLifecycleTest {
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

    @Test fun batchStartsThenStopsExactlyTheSelectedRealTorrents() = runBlocking {
        val (rtorrent, root) = remote()
        val parent = File(root, "batch-" + System.nanoTime()).apply { mkdirs() }
        val names = listOf("batch-a-${System.nanoTime()}.txt", "batch-b-${System.nanoTime()}.txt")
        val hashes = names.map { name ->
            File(parent, name).writeText(name)
            val bytes = torrent(name)
            val added = rtorrent.loadStoppedTorrentBytes(bytes, parent.absolutePath)
            assertTrue("$added", added is RemoteAddOutcome.VerifiedStopped ||
                added is RemoteAddOutcome.PendingVerification)
            TorrentMetainfoReader.preview(bytes).infoHashV1
        }
        suspend fun rows() = rtorrent.listTransfers("main").filter { row ->
            hashes.any { it.equals(row.hash, ignoreCase = true) } }
        repeat(30) { if (rows().size < 2) Thread.sleep(300) }
        assertEquals(2, rows().size)
        val before = rtorrent.listTransfers("main").filterNot { row ->
            hashes.any { it.equals(row.hash, ignoreCase = true) } }

        val started = RemoteBatchLifecycle.run(rows().map { RemoteBatchLifecycle.Target(it.hash, it.started) },
            true, rtorrent::setStarted)
        assertFalse(started.halted)
        assertEquals(2, started.verified.size + started.pending.size)
        repeat(30) { if (!rows().all { it.started }) Thread.sleep(300) }
        assertTrue("Both selected torrents must read back started", rows().all { it.started })

        val stopped = RemoteBatchLifecycle.run(rows().map { RemoteBatchLifecycle.Target(it.hash, it.started) },
            false, rtorrent::setStarted)
        assertFalse(stopped.halted)
        repeat(30) { if (rows().any { it.started }) Thread.sleep(300) }
        assertTrue("Both selected torrents must read back stopped", rows().none { it.started })

        // Unselected transfers keep their exact state.
        val after = rtorrent.listTransfers("main").filterNot { row ->
            hashes.any { it.equals(row.hash, ignoreCase = true) } }
        assertEquals(before.map { it.hash to it.started }.toSet(), after.map { it.hash to it.started }.toSet())
    }
}
