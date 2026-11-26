package io.github.gamesbyian.chunks.rpc

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
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
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * D1: the endpoint lock must cover the WIRE worker's whole life. A server whose accept queue is full makes
 * a further connect() genuinely hang (verified at runtime in each test; skipped, not faked, if this kernel
 * does not stall). Cancelling a caller mid-connect must (a) never deliver a request afterwards and
 * (b) keep the lock held, so the next waiter does not start its bracket, until the worker thread ended.
 */
class DelugeRoutingConnectStallTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val server = ServerSocket(0, 1, loopback)
    private val fillers = mutableListOf<Socket>()
    private val creds = RemoteCredentials("http://127.0.0.1:${server.localPort}/stall/json", "", "pw")
    private val a = "aaaa0000aaaa0000aaaa0000aaaa0000"
    private val b = "bbbb0000bbbb0000bbbb0000bbbb0000"

    @After fun close() { fillers.forEach { runCatching { it.close() } }; server.close() }

    /** Fill the (never accepting) server's queue until a further connect really blocks. */
    private fun stallConnects(): Boolean {
        repeat(20) {
            val s = Socket()
            try { s.connect(InetSocketAddress(loopback, server.localPort), 400); fillers += s }
            catch (_: SocketTimeoutException) { s.close(); return true }
        }
        return false
    }

    /** Accept everything queued and count the bytes any accepted socket delivers. */
    private fun drainAndCountBytes(windowMs: Int): Long {
        var bytes = 0L
        server.soTimeout = 300
        val accepted = mutableListOf<Socket>()
        val end = System.nanoTime() + windowMs * 1_000_000L
        while (System.nanoTime() < end) {
            try { accepted += server.accept().also { it.soTimeout = 100 } } catch (_: SocketTimeoutException) { }
            accepted.forEach { s ->
                try { val buf = ByteArray(4096); val n = s.getInputStream().read(buf); if (n > 0) bytes += n }
                catch (_: Exception) { }
            }
        }
        accepted.forEach { runCatching { it.close() } }
        return bytes
    }

    private val body = """{"method":"auth.login","params":["pw"],"id":1}""".toByteArray()
    private fun request() = DelugeReadOnlyRpc.Request(java.net.URI(creds.endpoint), body, null)

    @Test fun cancelMidConnectNeverSendsEvenIfTheConnectCompletesLater(): Unit = runBlocking {
        assumeTrue("this kernel really stalls a connect to a full accept queue", stallConnects())
        val call = launch(Dispatchers.IO) { DelugeReadOnlyRpc.HttpWire.exchange(request()) }
        withTimeout(10_000) { while (DelugeReadOnlyRpc.HttpWire.inFlight < 1) delay(2) }
        delay(300) // the worker is now in (or about to enter) the blocked connect
        val cancelling = launch(Dispatchers.IO) { call.cancelAndJoin() }
        // Free the queue so the pending connect can complete while the cancel waits for the worker.
        val delivered = drainAndCountBytes(windowMs = 3_000)
        withTimeout(30_000) { cancelling.join() }
        assertTrue(call.isCancelled)
        assertEquals("a cancelled request that was never written must not reach the server", 0, delivered)
        assertEquals("the worker has really ended", 0, DelugeReadOnlyRpc.HttpWire.inFlight)
    }

    @Test fun nextWaiterDoesNotStartItsBracketUntilTheCancelledWorkerHasEnded(): Unit = runBlocking {
        assumeTrue("this kernel really stalls a connect to a full accept queue", stallConnects())
        val inFlightWhenWaiterStarted = AtomicLong(-1)
        val counting = DelugeReadOnlyRpc.Wire { r ->
            inFlightWhenWaiterStarted.compareAndSet(-1, DelugeReadOnlyRpc.HttpWire.inFlight.toLong())
            DelugeReadOnlyRpc.HttpWire.exchange(r)
        }
        val holderClient = DelugeClient(creds, a)
        val waiterClient = DelugeClient(creds, b, counting)
        val holder = launch(Dispatchers.IO) { holderClient.torrents() } // login: stalls in connect
        withTimeout(10_000) { while (DelugeReadOnlyRpc.HttpWire.inFlight < 1) delay(2) }
        val waiter = async(Dispatchers.IO) { runCatching { waiterClient.torrents() } }
        val owner = DelugeRoutingOwner.forEndpoint(java.net.URI(creds.endpoint))
        withTimeout(10_000) { while (owner.queued != 1) delay(2) }
        holder.cancel()
        // While the worker is still blocked in connect, the lock is still held: the waiter has not started.
        delay(500)
        assertEquals("waiter must not have started while the cancelled worker lives", -1L, inFlightWhenWaiterStarted.get())
        assertEquals(1, DelugeReadOnlyRpc.HttpWire.inFlight)
        holder.join() // returns only after the worker ended (connect timeout bounds it)
        assertTrue(holder.isCancelled)
        assertEquals("the lock moved on only after the worker ended", 0L,
            withTimeout(30_000) { while (inFlightWhenWaiterStarted.get() == -1L) delay(5); inFlightWhenWaiterStarted.get() })
        waiter.cancel(); runCatching { waiter.await() }
    }

    @Test fun cancelMidBodyNeverDeliversTheWholeRequestAndTheWorkerEnds(): Unit = runBlocking {
        // A peer that accepts but never reads: the write blocks once the socket buffers are full.
        val big = ByteArray(64 * 1024 * 1024)
        val accepted = java.util.concurrent.CompletableFuture<Socket>()
        val acceptor = Thread { accepted.complete(server.accept()) }.apply { isDaemon = true; start() }
        val call = launch(Dispatchers.IO) {
            DelugeReadOnlyRpc.HttpWire.exchange(DelugeReadOnlyRpc.Request(java.net.URI(creds.endpoint), big, null))
        }
        val peer = accepted.get(10, java.util.concurrent.TimeUnit.SECONDS)
        withTimeout(10_000) { while (DelugeReadOnlyRpc.HttpWire.inFlight < 1) delay(2) }
        delay(1_000) // let the body fill the buffers and block
        val t0 = System.nanoTime()
        withTimeout(30_000) { call.cancelAndJoin() }
        println("CHUNKS_DELUGE_ROUTING_MIDBODY: cancel_join_ms=${(System.nanoTime() - t0) / 1_000_000}")
        assertTrue(call.isCancelled)
        assertEquals("worker ended with the cancel", 0, DelugeReadOnlyRpc.HttpWire.inFlight)
        peer.soTimeout = 2_000
        var received = 0L
        try { val buf = ByteArray(65536); while (true) { val n = peer.getInputStream().read(buf); if (n < 0) break; received += n } }
        catch (_: Exception) { }
        assertTrue("only a fraction of the 64 MiB body can have been sent: $received", received < big.size)
        peer.close(); acceptor.join(1000)
    }
}
