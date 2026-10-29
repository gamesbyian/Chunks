package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * A server-side XML-RPC fault may arrive after a mutation was applied.
 * It cannot authorize retries or prove that Add/Erase was rolled back.
 * These fixtures never contact real servers or alter disk payloads.
 */
class RemoteMutationRpcFaultTest {
    private val hash = "ab".repeat(20)
    private val destination = "/home/dev/fixture"
    private val name = "Test sample"
    private val metainfo = ("d4:infod6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789ee").toByteArray(Charsets.ISO_8859_1)

    private fun value(xml: String) =
        "<methodResponse><params><param><value>$xml</value></param></params></methodResponse>"

    private fun rpcFault(code: Int, message: String): String =
        """<methodResponse><fault><value><struct>
            <member><name>faultCode</name><value><int>$code</int></value></member>
            <member><name>faultString</name><value><string>$message</string></value></member>
            </struct></value></fault></methodResponse>"""

    private fun torrentRow(): String =
        "<value><array><data>" +
            "<value><string>$hash</string></value>" +
            "<value><string>$name</string></value>" +
            "<value><i8>10</i8></value><value><i8>5</i8></value>" +
            "<value><int>0</int></value>" +
            "<value><string>$destination</string></value>" +
            "<value><i8>0</i8></value><value><i8>0</i8></value>" +
            "<value><i8>0</i8></value></data></array></value>"

    private inner class Gateway(private val existingForErase: Boolean) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endpoint: String get() = "http://127.0.0.1:${server.address.port}/RPC2"
        @Volatile var writeApplied = false

        init {
            server.createContext("/RPC2") { exchange ->
                val request = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(request)?.groupValues?.get(1).orEmpty()
                calls.add(method)
                val response = when (method) {
                    "d.multicall2" -> value("<array><data>" +
                        if (existingForErase) torrentRow() + "</data></array>"
                        else "</data></array>")
                    "d.hash" -> value("<string>$hash</string>")
                    "d.name" -> rpcFault(-501, "Could not find info-hash.")
                    "load.verbose", "load.raw_verbose", "d.erase" -> {
                        writeApplied = true
                        rpcFault(-503, "Backend reported fault after submitting write")
                    }
                    else -> rpcFault(-32601, "Unexpected RPC method")
                }.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    @Test fun addServerFaultAfterSubmissionRemainsUncertainWithNoRetry() = runBlocking {
        Gateway(existingForErase = false).use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            val plan = StoppedRemoteAddPlan.fromMagnet(
                "magnet:?xt=urn:btih:$hash", destination)
            assertEquals(RemoteAddOutcome.Uncertain(hash), remote.loadStopped(plan))
            assertTrue(gateway.writeApplied)
            assertEquals(1, gateway.calls.count { it == "load.verbose" })
            assertFalse(gateway.calls.contains("load.raw_verbose"))
            assertFalse(gateway.calls.contains("d.erase"))
        }
    }

    @Test fun metainfoServerFaultAfterSubmissionRemainsUncertainWithNoRetry() = runBlocking {
        Gateway(existingForErase = false).use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            val expectedHash = TorrentMetainfoReader.preview(metainfo).infoHashV1
            assertEquals(RemoteAddOutcome.Uncertain(expectedHash),
                remote.loadStoppedTorrentBytes(metainfo, destination))
            assertTrue(gateway.writeApplied)
            assertEquals(1, gateway.calls.count { it == "load.raw_verbose" })
            assertFalse(gateway.calls.contains("load.verbose"))
            assertFalse(gateway.calls.contains("d.erase"))
        }
    }

    @Test fun eraseServerFaultAfterSubmissionIsUncertainWithoutSecondErase() = runBlocking {
        Gateway(existingForErase = true).use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            assertEquals(SessionEraseOutcome.Uncertain(hash),
                remote.eraseFromSession(hash, name, destination))
            assertTrue(gateway.writeApplied)
            assertEquals(listOf("d.multicall2", "d.hash", "d.erase"), gateway.calls)
        }
    }
}
