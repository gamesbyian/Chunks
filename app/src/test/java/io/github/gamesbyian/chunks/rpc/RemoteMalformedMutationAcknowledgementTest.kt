package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Post-write invalid XML cannot prove rollback or authorize replay. Loopback only. */
class RemoteMalformedMutationAcknowledgementTest {
    private val hash = "ab".repeat(20)
    private val dir = "/srv/test"
    private fun value(v: String) =
        "<methodResponse><params><param><value>$v</value></param></params></methodResponse>"
    private fun str(v: String) = "<value><string>$v</string></value>"
    private fun num(v: Int) = "<value><i8>$v</i8></value>"
    private fun empty() = value("<array><data></data></array>")
    private fun transfers() = value("<array><data><value><array><data>" +
        str(hash) + str("Sample") + num(10) + num(0) + num(0) + str(dir) +
        num(0) + num(0) + num(0) + "</data></array></value></data></array>")
    private fun files() = value("<array><data><value><array><data>" +
        str("sample.bin") + num(5) + num(1) + "</data></array></value></data></array>")
    private fun absent() = """<methodResponse><fault><value><struct>
        <member><name>faultCode</name><value><int>-501</int></value></member>
        <member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>
        </struct></value></fault></methodResponse>"""
    private fun broken() = "<methodResponse><params><param><value><int>0</int>"

    private class Gateway(private val answer: (String, Int) -> String) : AutoCloseable {
        private val http = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val url: String get() = "http://127.0.0.1:${http.address.port}/RPC2"
        init {
            http.createContext("/RPC2") { exchange ->
                val xml = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(xml)?.groupValues?.get(1).orEmpty()
                val index = synchronized(calls) {
                    calls.add(method)
                    calls.count { it == method }
                }
                val bytes = answer(method, index).toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            http.start()
        }
        override fun close() = http.stop(0)
    }

    private fun remote(g: Gateway) = RTorrentRemote(RemoteCredentials(g.url, "", ""))
    private fun plan() = StoppedRemoteAddPlan.fromMagnet("magnet:?xt=urn:btih:$hash", dir)

    @Test fun malformedAddAckDoesNotReplay() = runBlocking {
        Gateway { method, _ -> when (method) {
            "d.multicall2" -> empty()
            "d.name" -> absent()
            "load.verbose" -> broken()
            else -> error("Unexpected: $method")
        } }.use { g ->
            assertEquals(RemoteAddOutcome.Uncertain(hash), remote(g).loadStopped(plan()))
            assertEquals(listOf("d.multicall2", "d.name", "load.verbose"), g.calls)
        }
    }

    @Test fun malformedPostAddReadbackDoesNotReplay() = runBlocking {
        Gateway { method, count -> when (method) {
            "d.multicall2" -> if (count == 1) empty() else broken()
            "d.name" -> absent()
            "load.verbose" -> value("<i8>0</i8>")
            else -> error("Unexpected: $method")
        } }.use { g ->
            assertEquals(RemoteAddOutcome.Uncertain(hash), remote(g).loadStopped(plan()))
            assertEquals(listOf("d.multicall2", "d.name", "load.verbose", "d.multicall2"), g.calls)
        }
    }

    @Test fun malformedEraseAckOrReadbackDoesNotReplay() = runBlocking {
        for (badAck in listOf(true, false)) {
            Gateway { method, count -> when (method) {
                "d.multicall2" -> transfers()
                "d.hash" -> if (!badAck && count == 2) broken() else value("<string>$hash</string>")
                "d.erase" -> if (badAck) broken() else value("<i8>0</i8>")
                else -> error("Unexpected: $method")
            } }.use { g ->
                assertEquals(SessionEraseOutcome.Uncertain(hash),
                    remote(g).eraseFromSession(hash, "Sample", dir))
                assertEquals(1, g.calls.count { it == "d.erase" })
                assertEquals(if (badAck) 1 else 2, g.calls.count { it == "d.hash" })
            }
        }
    }

    @Test fun malformedPriorityAckOrReadbackDoesNotReplay() = runBlocking {
        for (badAck in listOf(true, false)) {
            Gateway { method, count -> when (method) {
                "f.multicall" -> if (!badAck && count == 2) broken() else files()
                "f.priority.set" -> if (badAck) broken() else value("<i8>0</i8>")
                "d.update_priorities" -> value("<i8>0</i8>")
                else -> error("Unexpected: $method")
            } }.use { g ->
                assertEquals(FilePriorityOutcome.Uncertain(hash, 0),
                    remote(g).setFilePriority(hash, 0, "sample.bin", 5, 1, 2))
                assertEquals(1, g.calls.count { it == "f.priority.set" })
                assertEquals(if (badAck) 0 else 1,
                    g.calls.count { it == "d.update_priorities" })
            }
        }
    }

    @Test fun malformedPreflightNeverSendsAdd() = runBlocking {
        Gateway { method, _ -> if (method == "d.multicall2") broken()
            else error("Unexpected mutation: $method")
        }.use { g ->
            try {
                remote(g).loadStopped(plan())
                fail("Malformed inventory must fail closed")
            } catch (_: Exception) {
                assertEquals(listOf("d.multicall2"), g.calls)
            }
        }
    }
}
