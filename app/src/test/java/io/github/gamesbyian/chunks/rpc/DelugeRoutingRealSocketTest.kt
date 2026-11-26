package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real blocking sockets (com.sun.net.httpserver) in front of the shared-slot fake, using the production
 * [DelugeReadOnlyRpc.HttpWire]. A holder cancelled while its HTTP call is stuck in a blocking read must
 * release the endpoint lock promptly, not after the 15 s read timeout, and a write that may already have
 * been dispatched is only ever reported as cancellation, never as "not written", and never replayed.
 */
class DelugeRoutingRealSocketTest {
    private val a = "aaaa0000aaaa0000aaaa0000aaaa0000"
    private val b = "bbbb0000bbbb0000bbbb0000bbbb0000"
    private val ui = FakeSharedSlotWebUi(listOf(a, b), latencyMs = 0)
    private val pool = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = pool
        createContext("/sock/json") { exchange ->
            val body = exchange.requestBody.readBytes()
            val cookie = exchange.requestHeaders.getFirst("Cookie")
            // Blocks this server thread for real while the fake's gate is closed.
            val reply = runBlocking { ui.exchange(DelugeReadOnlyRpc.Request(java.net.URI("http://x/json"), body, cookie)) }
            val bytes = reply.body.toByteArray()
            reply.header("Set-Cookie").forEach { exchange.responseHeaders.add("Set-Cookie", it) }
            runCatching {
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        start()
    }
    private val creds = RemoteCredentials("http://127.0.0.1:${server.address.port}/sock/json", "", "pw")

    @After fun stop() { ui.gateRelease.complete(Unit); server.stop(0); pool.shutdownNow() }

    private suspend fun awaitQueued(n: Int) {
        val owner = DelugeRoutingOwner.forEndpoint(java.net.URI(creds.endpoint))
        withTimeout(10_000) { while (owner.queued != n) delay(2) }
    }

    @Test fun cancelledHolderStuckInABlockingReadReleasesTheLockQuicklyAndTheNextWaiterRuns(): Unit = runBlocking {
        val ca = DelugeClient(creds, a); val cb = DelugeClient(creds, b)
        ca.torrents() // real login + bind over the real socket
        ui.gateNext("core.get_torrents_status")
        val holder = launch(Dispatchers.IO) { ca.torrents() }
        withTimeout(10_000) { ui.gateEntered.await() } // the request is now blocked in the server
        val next = async(Dispatchers.IO) { cb.torrents() }
        awaitQueued(1)
        val started = System.nanoTime()
        holder.cancelAndJoin()
        val releaseMs = (System.nanoTime() - started) / 1_000_000
        val inventory = withTimeout(10_000) { next.await() } // proceeds: its own bind runs while the stuck request still hangs
        val totalMs = (System.nanoTime() - started) / 1_000_000
        println("CHUNKS_DELUGE_ROUTING_SOCKET: holder_cancel_join_ms=$releaseMs next_waiter_done_ms=$totalMs (read timeout is 15000)")
        assertTrue("cancelled holder returned in ${releaseMs}ms, before the 15 s read timeout would have elapsed", releaseMs < 14_000)
        assertTrue("next waiter proceeded in ${totalMs}ms", totalMs < 14_000)
        assertTrue(holder.isCancelled)
        assertTrue(inventory.isEmpty())
    }

    @Test fun cancelledWriteAfterDispatchIsCancellationNotNoWriteAndIsNeverReplayed(): Unit = runBlocking {
        val ca = DelugeClient(creds, a); val cb = DelugeClient(creds, b)
        ca.torrents()
        ui.gateNext("core.add_torrent_magnet")
        val hash = "%040x".format(0xfeedL)
        val write = launch(Dispatchers.IO) {
            ca.add(ClientAddRequest(ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash&dn=x"), null, false))
        }
        withTimeout(10_000) { ui.gateEntered.await() } // dispatched: the server has the request
        assertEquals(1, ui.count("core.add_torrent_magnet"))
        val started = System.nanoTime()
        write.cancelAndJoin()
        assertTrue("cancel returned before the read timeout would have elapsed", (System.nanoTime() - started) / 1_000_000 < 14_000)
        assertTrue("reported as cancellation, not as a refusal or a clean 'nothing happened'", write.isCancelled)
        // The server may still execute the dispatched request: uncertain, so it must be observable and never retried.
        ui.gateRelease.complete(Unit)
        withTimeout(10_000) { while (ui.inventories.getValue(a)[hash] == null) delay(5) }
        assertEquals("the write was sent exactly once, never replayed", 1, ui.count("core.add_torrent_magnet"))
        // Independent readback through a fresh client still works and sees the one job on A only.
        val seen = withTimeout(10_000) { DelugeClient(creds, a).torrents() }
        assertEquals(listOf(hash), seen.map { it.hash })
        assertTrue(withTimeout(10_000) { cb.torrents() }.isEmpty())
        assertEquals(1, ui.count("core.add_torrent_magnet"))
    }
}
