package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant control `rtorrent-add-swallows-cancellation`: cancelling a rTorrent Add after the
 * load was dispatched propagates cancellation. It never becomes a returned outcome, and the
 * non-idempotent load is never replayed. Loopback gateway only.
 */
class RemoteAddCancellationTest {
    private val hash = "ab".repeat(20)
    private val dir = "/srv/test"
    private fun value(v: String) =
        "<methodResponse><params><param><value>$v</value></param></params></methodResponse>"
    private fun absent() = """<methodResponse><fault><value><struct>
        <member><name>faultCode</name><value><int>-501</int></value></member>
        <member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>
        </struct></value></fault></methodResponse>"""

    @Test fun cancellingAfterTheLoadWasDispatchedPropagatesAndNeverReplays() {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val loadSeen = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        http.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(xml)?.groupValues?.get(1).orEmpty()
            calls.add(method)
            if (method == "load.verbose") { loadSeen.countDown(); release.await(20, TimeUnit.SECONDS) }
            val reply = when (method) {
                "d.multicall2" -> value("<array><data></data></array>")
                "d.name" -> absent()
                else -> value("<int>0</int>")
            }.toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        http.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:${http.address.port}/RPC2", "", ""))
            var returned: RemoteAddOutcome? = null
            runBlocking {
                val job = launch(Dispatchers.IO) {
                    returned = remote.loadStopped(StoppedRemoteAddPlan.fromMagnet("magnet:?xt=urn:btih:$hash", dir))
                }
                assertTrue("the load was dispatched", loadSeen.await(15, TimeUnit.SECONDS))
                job.cancel()
                release.countDown()
                job.join()
                assertTrue(job.isCancelled)
            }
            assertNull("a cancelled Add returns no outcome (it propagates): $returned", returned)
            assertEquals("the load is never replayed", 1, calls.count { it == "load.verbose" })
        } finally {
            release.countDown()
            http.stop(0)
        }
    }
}
