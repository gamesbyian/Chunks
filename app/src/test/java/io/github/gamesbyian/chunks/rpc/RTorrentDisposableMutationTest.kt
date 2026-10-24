package io.github.gamesbyian.chunks.rpc

import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real rTorrent 0.9.x integration test running ONLY against localhost ephemeral
 * rTorrent + SCGI/nginx. Never point at a user seedbox or non-loopback server.
 *
 * Test data need not exist for a stopped transfer; the ephemeral process and
 * data root are destroyed with its isolated CI runner.
 */
class RTorrentDisposableMutationTest {
    private fun temporaryRemote(): RTorrentRemote {
        val address = System.getenv("CHUNKS_DISPOSABLE_RT_URL").orEmpty()
        assumeTrue("Real integration only when disposable test rig is enabled", address.isNotBlank())
        val uri = URI(address)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 18080 &&
            uri.path == "/RPC2" && uri.rawQuery == null && uri.userInfo == null) {
            "Refusing to run mutation test outside the disposable loopback rig"
        }
        return RTorrentRemote(RemoteCredentials(address, "", ""))
    }

    private val bytes = (
        "d4:info" +
        "d6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789e" +
        "e"
    ).toByteArray(Charsets.UTF_8)

    @Test fun stoppedBinaryAddAndHashBoundedLifecycleWorkOnRealEngine() = runBlocking {
        val rtorrent = temporaryRemote()
        val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
        val target = System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR").orEmpty()
        require(target.startsWith("/") && target.contains("chunks-data")) {
            "No disposable data root was configured"
        }

        assertTrue("No duplicate fixture should exist in this clean real engine",
            rtorrent.listTransfers("main").none { it.hash.equals(hash, true) })

        // One real binary metainfo Add. No retry if it is uncertain or pending.
        val first = rtorrent.loadStoppedTorrentBytes(bytes, target)
        assertTrue("RPC must not leave a divergent location or uncertain mutation: $first",
            first is RemoteAddOutcome.VerifiedStopped ||
                first is RemoteAddOutcome.PendingVerification)

        // The rTorrent event loop can complete the load on a later tick.
        var observed: RemoteTransfer? = null
        for (index in 1..15) {
            observed = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }
            if (observed != null) break
            Thread.sleep(400)
        }
        assertNotNull("Actual rTorrent never listed loaded test hash", observed)
        assertFalse("New test torrent must not auto-start", observed!!.started)
        assertTrue("Existing data must not be sent to a default directory",
            observed.directory.startsWith(target))

        val second = rtorrent.loadStoppedTorrentBytes(bytes, target)
        assertEquals("Duplicate Add must be refused without mutation",
            RemoteAddOutcome.AlreadyPresent(hash), second)

        // A real engine may perform this transition asynchronously.
        val start = rtorrent.setStarted(hash, true)
        assertTrue("Start should be acknowledged with sane state", start is LifecycleOutcome.Verified ||
            start is LifecycleOutcome.Pending)
        var running = false
        for (index in 1..15) {
            running = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }?.started == true
            if (running) break
            Thread.sleep(400)
        }
        assertTrue("Real rTorrent did not enter started state", running)

        val stop = rtorrent.setStarted(hash, false)
        assertTrue("Stop should not have uncertain transport outcome", stop is LifecycleOutcome.Verified ||
            stop is LifecycleOutcome.Pending)
        var stopped = false
        for (index in 1..15) {
            stopped = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }?.started == false
            if (stopped) break
            Thread.sleep(400)
        }
        assertTrue("Real rTorrent did not return to stopped state", stopped)
    }
}
