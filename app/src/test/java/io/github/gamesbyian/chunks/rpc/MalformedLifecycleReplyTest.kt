package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** A parser failure after a lifecycle write is inconclusive, never proof of rollback. */
class MalformedLifecycleReplyTest {
    private val hash = "a".repeat(40)

    private fun result(value: String) =
        "<methodResponse><params><param><value>$value</value></param></params></methodResponse>"

    private val invalidNumber = result("<int>not-a-number</int>")
    private val malformedXml = "<methodResponse><params><param><value><int>0</int>"
    private val ambiguousValue = result("<int>0</int><int>1</int>")

    private inner class Gateway(
        private val started: Boolean,
        private val badMethod: String,
        private val badReply: String
    ) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val applied: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endpoint: String get() = "http://127.0.0.1:${server.address.port}/RPC2"

        init {
            server.createContext("/RPC2") { exchange ->
                val request = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>").find(request)!!.groupValues[1]
                calls += method
                if (method in listOf("d.open", "d.start", "d.resume", "d.stop", "d.close")) {
                    // The synthetic engine applied the command before the gateway replied.
                    applied += method
                }
                val reply = when {
                    method == badMethod -> badReply
                    method == "d.state" || method == "d.is_active" ->
                        result("<int>${if (started) 1 else 0}</int>")
                    else -> result("<int>0</int>")
                }.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, reply.size.toLong())
                exchange.responseBody.use { it.write(reply) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private suspend fun assertUncertainWithoutRetry(
        start: Boolean, badMethod: String, badReply: String, expectedCalls: List<String>
    ) {
        Gateway(start, badMethod, badReply).use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            assertEquals("start=$start, method=$badMethod, reply=$badReply",
                LifecycleOutcome.Uncertain(start), remote.setStarted(hash, start))
            assertEquals("Abort at the first inconclusive reply, with no retry", expectedCalls, gateway.calls)
            assertEquals(expectedCalls.filter { it !in listOf("d.state", "d.is_active") }, gateway.applied)
            assertTrue("At least one command applied before the bad reply", gateway.applied.isNotEmpty())
        }
    }

    @Test fun malformedFirstWriteReplyIsUncertainEvenThoughCommandApplied() = runBlocking {
        assertUncertainWithoutRetry(true, "d.open", malformedXml, listOf("d.open"))
        assertUncertainWithoutRetry(false, "d.stop", ambiguousValue, listOf("d.stop"))
    }

    @Test fun invalidNumericReplyAtLaterWriteCannotUndoEarlierAcknowledgements() = runBlocking {
        assertUncertainWithoutRetry(true, "d.start", invalidNumber, listOf("d.open", "d.start"))
        assertUncertainWithoutRetry(false, "d.close", invalidNumber, listOf("d.stop", "d.close"))
    }

    @Test fun malformedOrNonnumericStateReadbackIsUncertainAfterAllWrites() = runBlocking {
        assertUncertainWithoutRetry(true, "d.state", ambiguousValue,
            listOf("d.open", "d.start", "d.resume", "d.state"))
        assertUncertainWithoutRetry(false, "d.state", result("<string>unknown</string>"),
            listOf("d.stop", "d.close", "d.state"))
    }

    @Test fun malformedOrNonnumericActivityReadbackNeverVerifiesLifecycleState() = runBlocking {
        assertUncertainWithoutRetry(true, "d.is_active", malformedXml,
            listOf("d.open", "d.start", "d.resume", "d.state", "d.is_active"))
        assertUncertainWithoutRetry(false, "d.is_active", result("<string>unknown</string>"),
            listOf("d.stop", "d.close", "d.state", "d.is_active"))
    }

    @Test fun lifecycleFlagsRejectFloatingPointWrongTypeAndOutOfRangeValues() = runBlocking {
        val invalidFlags = listOf("NaN", "Infinity", "-Infinity", "0.5", "1.9", "0.0", "1.0")
            .map { "<double>$it</double>" } +
            listOf("<int>2</int>", "<i8>-1</i8>", "<string>0</string>", "<boolean>0</boolean>")
        for (start in listOf(false, true)) {
            val writes = if (start) listOf("d.open", "d.start", "d.resume") else listOf("d.stop", "d.close")
            for (method in listOf("d.state", "d.is_active")) {
                val calls = writes + "d.state" + if (method == "d.is_active") listOf("d.is_active") else emptyList()
                for (flag in invalidFlags) {
                    assertUncertainWithoutRetry(start, method, result(flag), calls)
                }
            }
        }
    }

    @Test fun zeroAndOneRemainSupportedForEveryIntegerWireRepresentation() = runBlocking {
        for (start in listOf(false, true)) {
            val writes = if (start) listOf("d.open", "d.start", "d.resume") else listOf("d.stop", "d.close")
            for (method in listOf("d.state", "d.is_active")) {
                for (type in listOf("int", "i4", "i8")) {
                    Gateway(start, method, result("<$type>${if (start) 1 else 0}</$type>")).use { gateway ->
                        val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                        assertEquals(LifecycleOutcome.Verified(start), remote.setStarted(hash, start))
                        assertEquals(writes + listOf("d.state", "d.is_active"), gateway.calls)
                        assertEquals(writes, gateway.applied)
                    }
                }
            }
        }
    }
}
