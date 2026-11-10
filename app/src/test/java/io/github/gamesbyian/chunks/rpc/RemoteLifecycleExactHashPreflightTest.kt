package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * A syntactically valid hash from an old list is not a fresh session
 * identity. Run against a real localhost HTTP transport, with no fixture
 * writes outside this disposable process.
 */
class RemoteLifecycleExactHashPreflightTest {
    private val hash = "a".repeat(40)
    private val otherHash = "b".repeat(40)

    private enum class Scenario { MATCH, MISSING, WRONG_FIRST, WRONG_AFTER_WRITE }

    private inner class Gateway(private val scenario: Scenario) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var mutations = 0
        private var hashReads = 0
        val endpoint: String get() = "http://127.0.0.1:${server.address.port}/RPC2"

        init {
            server.createContext("/RPC2") { exchange ->
                val xml = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(xml)?.groupValues?.get(1).orEmpty()
                methods += method
                val value = when (method) {
                    "d.hash" -> {
                        hashReads++
                        when (scenario) {
                            Scenario.MISSING -> null
                            Scenario.WRONG_FIRST -> "<string>$otherHash</string>"
                            Scenario.WRONG_AFTER_WRITE ->
                                "<string>${if (hashReads > 1) otherHash else hash}</string>"
                            Scenario.MATCH -> "<string>$hash</string>"
                        }
                    }
                    "d.open", "d.resume", "d.close" -> {
                        mutations++
                        "<int>0</int>"
                    }
                    "d.start", "d.stop" -> {
                        mutations++
                        "<int>0</int>"
                    }
                    "d.state", "d.is_active" -> "<int>1</int>"
                    else -> error("Unexpected RPC method $method")
                }
                val reply = if (value == null) {
                    """<methodResponse><fault><value><struct>
                      <member><name>faultCode</name><value><int>-503</int></value></member>
                      <member><name>faultString</name><value><string>Could not find info-hash</string></value></member>
                    </struct></value></fault></methodResponse>"""
                } else "<methodResponse><params><param><value>$value</value>" +
                    "</param></params></methodResponse>"
                val bytes = reply.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    @Test fun absentOrContradictoryHashRefusesBeforeAnyLifecycleMutation() = runBlocking {
        for (scenario in listOf(Scenario.MISSING, Scenario.WRONG_FIRST)) {
            Gateway(scenario).use { gateway ->
                val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                try {
                    remote.setStarted(hash, true)
                    fail("Wrong/absent session must refuse without a write")
                } catch (expected: Exception) {
                    assertTrue(expected is RpcFault || expected is IllegalStateException)
                }
                assertEquals(listOf("d.hash"), gateway.methods)
                assertEquals(0, gateway.mutations)
            }
        }
    }

    @Test fun validHashBracketsVerifiedLifecycleWithTwoIndependentReads() = runBlocking {
        Gateway(Scenario.MATCH).use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            assertEquals(LifecycleOutcome.Verified(true), remote.setStarted(hash, true))
            assertEquals(
                listOf("d.hash", "d.open", "d.start", "d.resume", "d.state",
                    "d.is_active", "d.hash"), gateway.methods)
            assertEquals(3, gateway.mutations)
        }
    }

    @Test fun hashChangingAfterAWriteCannotProduceVerifiedStateOrReplay() = runBlocking {
        Gateway(Scenario.WRONG_AFTER_WRITE).use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            assertEquals(LifecycleOutcome.Uncertain(true), remote.setStarted(hash, true))
            assertEquals(
                listOf("d.hash", "d.open", "d.start", "d.resume", "d.state",
                    "d.is_active", "d.hash"), gateway.methods)
            assertEquals("No second write after contradictory readback", 3, gateway.mutations)
        }
    }
}
