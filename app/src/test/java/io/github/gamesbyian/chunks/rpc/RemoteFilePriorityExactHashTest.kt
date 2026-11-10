package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The file list alone cannot prove the selected rTorrent session is still
 * present. Exercise the actual loopback HTTP/XML-RPC adapter rather than a
 * mocked coordinator; no real user account or data is touched.
 */
class RemoteFilePriorityExactHashTest {
    private val hash = "a".repeat(40)
    private val differentHash = "b".repeat(40)
    private enum class Scenario {
        MATCH, MISSING_BEFORE, WRONG_BEFORE, MALFORMED_BEFORE,
        LATE_PATH, LATE_SIZE, LATE_PRIORITY, LATE_HASH,
        MISSING_AFTER, WRONG_AFTER, MALFORMED_AFTER
    }

    private inner class Gateway(private val scenario: Scenario) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var mutations = 0
        @Volatile var priority = 1
        private var hashReads = 0
        private var fileReads = 0
        val endpoint: String get() = "http://127.0.0.1:" + server.address.port + "/RPC2"

        init {
            server.createContext("/RPC2") { exchange ->
                val request = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(request)?.groupValues?.get(1).orEmpty()
                methods += method
                val value: String? = when (method) {
                    "f.multicall" -> {
                        fileReads++
                        val changed = fileReads > 1 && mutations == 0
                        val observedPath = if (scenario == Scenario.LATE_PATH && changed)
                            "different.bin" else "payload.bin"
                        val observedSize = if (scenario == Scenario.LATE_SIZE && changed)
                            43 else 42
                        val observedPriority = if (scenario == Scenario.LATE_PRIORITY && changed)
                            0 else priority
                        "<array><data><value><array><data>" +
                            "<value><string>$observedPath</string></value>" +
                            "<value><i8>$observedSize</i8></value>" +
                            "<value><i8>$observedPriority</i8></value>" +
                            "</data></array></value></data></array>"
                    }
                    "d.hash" -> {
                        hashReads++
                        when (scenario) {
                            Scenario.MISSING_BEFORE -> null
                            Scenario.WRONG_BEFORE -> "<string>" + differentHash + "</string>"
                            Scenario.MALFORMED_BEFORE -> "<i8>12</i8>"
                            Scenario.MISSING_AFTER -> if (mutations > 0) null else "<string>" + hash + "</string>"
                            Scenario.WRONG_AFTER -> if (mutations > 0)
                                "<string>" + differentHash + "</string>" else "<string>" + hash + "</string>"
                            Scenario.MALFORMED_AFTER -> if (mutations > 0)
                                "<i8>12</i8>" else "<string>" + hash + "</string>"
                            Scenario.LATE_HASH -> if (hashReads > 1)
                                "<string>" + differentHash + "</string>" else "<string>" + hash + "</string>"
                            else -> "<string>" + hash + "</string>"
                        }
                    }
                    "f.priority.set" -> {
                        assertTrue("Wrong file identifier", request.contains(hash + ":f0"))
                        mutations++
                        priority = 2
                        "<i8>0</i8>"
                    }
                    "d.update_priorities" -> {
                        mutations++
                        "<i8>0</i8>"
                    }
                    else -> error("Unexpected RPC: " + method)
                }
                val reply = if (value == null) {
                    """<methodResponse><fault><value><struct>
                        <member><name>faultCode</name><value><int>-501</int></value></member>
                        <member><name>faultString</name><value><string>Could not find info-hash</string></value></member>
                        </struct></value></fault></methodResponse>"""
                } else {
                    "<methodResponse><params><param><value>" + value +
                        "</value></param></params></methodResponse>"
                }
                val bytes = reply.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private fun remote(g: Gateway) = RTorrentRemote(RemoteCredentials(g.endpoint, "", ""))

    @Test fun absentWrongOrMalformedSessionRefusesBeforePriorityWrite() = runBlocking {
        for (scenario in listOf(Scenario.MISSING_BEFORE, Scenario.WRONG_BEFORE,
                Scenario.MALFORMED_BEFORE)) {
            Gateway(scenario).use { g ->
                try {
                    remote(g).setFilePriority(hash, 0, "payload.bin", 42, 1, 2)
                    fail("Session identity must be verified before changing any file")
                } catch (expected: Exception) {
                    assertTrue(expected is RpcFault || expected is IllegalStateException)
                }
                assertEquals(listOf("f.multicall", "d.hash"), g.methods)
                assertEquals(0, g.mutations)
                assertEquals(1, g.priority)
            }
        }
    }

    @Test fun changedFileAtSameHashRefusesBeforeEitherPriorityMutation() = runBlocking {
        for (scenario in listOf(Scenario.LATE_PATH, Scenario.LATE_SIZE, Scenario.LATE_PRIORITY,
                Scenario.LATE_HASH)) {
            Gateway(scenario).use { g ->
                try {
                    remote(g).setFilePriority(hash, 0, "payload.bin", 42, 1, 2)
                    fail("Stale file or session identity must refuse writes: $scenario")
                } catch (_: IllegalStateException) { }
                assertEquals("No write on stale reviewed file", 0, g.mutations)
                assertFalse(g.methods.contains("f.priority.set"))
                assertFalse(g.methods.contains("d.update_priorities"))
                assertEquals("A second file read must catch the changed index/priority", 2,
                    g.methods.count { it == "f.multicall" })
                assertEquals("Prewrite refusal must not modify old priority", 1, g.priority)
            }
        }
    }

    @Test fun contradictedOrUnavailablePostWriteHashIsUncertainAndNeverReplayed() = runBlocking {
        for (scenario in listOf(Scenario.MISSING_AFTER, Scenario.WRONG_AFTER,
                Scenario.MALFORMED_AFTER)) {
            Gateway(scenario).use { g ->
                assertEquals(FilePriorityOutcome.Uncertain(hash, 0),
                    remote(g).setFilePriority(hash, 0, "payload.bin", 42, 1, 2))
                assertEquals(listOf("f.multicall", "d.hash", "f.multicall", "d.hash",
                    "f.priority.set", "d.update_priorities", "f.multicall", "d.hash"), g.methods)
                assertEquals("One priority write and one update; never replay", 2, g.mutations)
                assertEquals("A prior mutation may have applied", 2, g.priority)
            }
        }
    }

    @Test fun twoMatchingHashWitnessesPermitFreshVerifiedPriority() = runBlocking {
        Gateway(Scenario.MATCH).use { g ->
            assertEquals(FilePriorityOutcome.Verified(hash, 0, 2),
                remote(g).setFilePriority(hash, 0, "payload.bin", 42, 1, 2))
            assertEquals(listOf("f.multicall", "d.hash", "f.multicall", "d.hash",
                    "f.priority.set", "d.update_priorities", "f.multicall", "d.hash"), g.methods)
            assertEquals(2, g.mutations)
        }
    }
}
