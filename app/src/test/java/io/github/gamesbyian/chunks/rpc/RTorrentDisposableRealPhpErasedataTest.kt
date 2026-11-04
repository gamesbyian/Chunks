package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Production adapter against a real, pinned ruTorrent HTTP-RPC/erasedata PHP
 * server plus disposable rTorrent. The workflow independently witnesses
 * physical deletion only after invoking the upstream collector.
 */
class RTorrentDisposableRealPhpErasedataTest {
    @Test fun productionAdapterRequestsExactPhpEraseWithoutClaimingFilesGone() = runBlocking {
        val url = System.getenv("CHUNKS_DISPOSABLE_RUTORRENT_URL").orEmpty()
        val torrentPath = System.getenv("CHUNKS_DISPOSABLE_PHP_TORRENT").orEmpty()
        val destination = System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR").orEmpty()
        assumeTrue("Only run inside the disposable PHP integration rig", url.isNotBlank())
        require(url == "http://127.0.0.1:18082/rutorrent1/plugins/httprpc/action.php")
        require(destination.startsWith("/tmp/") || destination.startsWith("/home/runner/work/_temp/"))
        require(destination.endsWith("/chunks-data"))
        require(torrentPath.endsWith("/chunks-erasedata-adapter-positive.torrent"))
        val expected = TorrentMetainfoReader.preview(Files.readAllBytes(Paths.get(torrentPath)))
        val hash = expected.infoHashV1
        val rtorrent = RTorrentRemote(RemoteCredentials(url, "", ""))
        assertTrue("Pinned ruTorrent HTTP endpoint must support exact plugin form",
            rtorrent.supportsRuTorrentDeleteData())
        val matching = rtorrent.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
        assertNotNull("The staged exact fixture must be visible through actual PHP httprpc",
            matching)
        assertEquals("chunks-erasedata-adapter-positive.bin", matching!!.name)
        assertEquals(destination, matching.directory)
        assertEquals("A mismatched review cannot delete an existing server job",
            RemoteDeleteDataOutcome.Refused,
            rtorrent.removeWithDataViaRuTorrent(hash, "different name", destination))
        assertNotNull("A refused mutation must leave the exact session intact",
            rtorrent.lookupName(hash))
        val result = rtorrent.removeWithDataViaRuTorrent(hash, matching.name, matching.directory)
        val sessionAfter = rtorrent.lookupName(hash)
        assertEquals("PHP must stage cleanup and remove the exact rTorrent session; " +
            "adapter result=$result, fresh session readback=$sessionAfter",
            RemoteDeleteDataOutcome.QueuedForCleanup(hash), result)
        assertNull("Only exact session absence can justify queued cleanup", sessionAfter)
        // Do not claim physical byte deletion here. The independent Python
        // fixture invokes upstream update.php and checks target/canary bytes.
    }
}
