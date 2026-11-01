package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Real loopback HTTP and XML-RPC boundaries for large inventory recovery.
 * Fixed-size system.multicall pages can exceed a reverse proxy's reply budget
 * even though the exact same getters work in smaller read-only batches.
 * No protected host, credentials or mutation calls are involved.
 */
class RemoteAdaptivePagingTest {
    private val hashes = (1..250).map { it.toString(16).padStart(40, '0') }

    private fun rpc(value: String): String =
        "<methodResponse><params><param><value>$value</value></param></params></methodResponse>"

    private fun string(value: String): String = "<value><string>$value</string></value>"
    private fun integer(value: Long): String = "<value><i8>$value</i8></value>"

    private class Gateway(
        private val identities: List<String>,
        private val irreducibleHash: String? = null,
        private val changeViewAtEnd: Boolean = false
    ) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val pageSizes: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        private var viewReads = 0
        val endpoint get() = "http://127.0.0.1:${server.address.port}/RPC2"

        init {
            server.createContext("/RPC2") { exchange ->
                val request = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(request)?.groupValues?.get(1).orEmpty()
                methods.add(method)
                val response: String?
                when (method) {
                    // Force the ordinary adapter to select its large-list path.
                    "d.multicall2" -> response = null
                    "download_list" -> {
                        viewReads++
                        val current = if (changeViewAtEnd && viewReads > 1)
                            identities.dropLast(1) else identities
                        response = rpc("<array><data>" +
                            current.joinToString("") { "<value><string>$it</string></value>" } +
                            "</data></array>")
                    }
                    "system.multicall" -> {
                        val requestedMethods = Regex(
                            "<name>methodName</name><value><string>(d\\.[^<]+)</string>"
                        ).findAll(request).map { it.groupValues[1] }.toList()
                        val requestedHashes = Regex(
                            "<name>params</name><value><array><data><value><string>([a-f0-9]{40})</string>"
                        ).findAll(request).map { it.groupValues[1] }.toList()
                        val count = requestedHashes.size
                        pageSizes.add(count / 9)
                        if (requestedMethods.size != count ||
                            requestedMethods.isEmpty() || count % 9 != 0 ||
                            count > 125 * 9 ||
                            (irreducibleHash != null && requestedHashes.contains(irreducibleHash))) {
                            response = null
                        } else {
                            val fields = requestedMethods.zip(requestedHashes).joinToString("") { (field, hash) ->
                                val value = when (field) {
                                    "d.hash" -> "<string>$hash</string>"
                                    "d.name" -> "<string>sample-$hash</string>"
                                    "d.size_bytes" -> "<i8>1024</i8>"
                                    "d.completed_bytes" -> "<i8>512</i8>"
                                    "d.state" -> "<i8>1</i8>"
                                    "d.directory" -> "<string>/srv/fixtures</string>"
                                    "d.down.rate" -> "<i8>12</i8>"
                                    "d.up.rate" -> "<i8>3</i8>"
                                    "d.ratio" -> "<i8>500</i8>"
                                    else -> "<string>unexpected</string>"
                                }
                                "<value><array><data><value>$value</value></data></array></value>"
                            }
                            response = rpc("<array><data>$fields</data></array>")
                        }
                    }
                    else -> response = rpc("<string>Unexpected method</string>")
                }
                if (response == null) {
                    // This simulates an accurately advertised over-budget
                    // HTTP response, without allocating or transferring 4 MiB.
                    exchange.sendResponseHeaders(200, 4_194_305L)
                    exchange.close()
                } else {
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    @Test fun oversizedFullPageIsReassembledInOriginalHashOrder() = runBlocking {
        Gateway(hashes).use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            val rows = remote.listTransfers("main")
            assertEquals(hashes, rows.map { it.hash })
            assertTrue(rows.all { it.name == "sample-${it.hash}" })
            assertTrue(rows.all { it.sizeBytes == 1024L && it.completedBytes == 512L })
            assertTrue(rows.all { it.started && it.downBytesPerSecond == 12L })
            assertEquals(listOf(1, 250, 125, 125), gateway.pageSizes.toList())
            assertEquals(2, gateway.methods.count { it == "download_list" })
            assertTrue(gateway.methods.all {
                it == "d.multicall2" || it == "download_list" || it == "system.multicall"
            })
        }
    }

    @Test fun unsplittableSingleHashNeverReturnsPartialSnapshot() = runBlocking {
        Gateway(hashes, irreducibleHash = hashes.last()).use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            try {
                remote.listTransfers("main")
                fail("A single oversized transfer cannot be silently discarded")
            } catch (_: RpcReplyTooLarge) {
                assertTrue(gateway.pageSizes.contains(1))
                assertEquals(1, gateway.methods.count { it == "download_list" })
            }
            assertTrue(gateway.methods.none { it.startsWith("d.erase") || it.startsWith("d.start") })
        }
    }

    @Test fun changedMembershipAfterSplitRefusesCompleteInventoryClaim() = runBlocking {
        Gateway(hashes, changeViewAtEnd = true).use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            try {
                remote.listTransfers("main")
                fail("A changing main view cannot be published as a complete snapshot")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("membership changed"))
            }
            assertEquals(2, gateway.methods.count { it == "download_list" })
        }
    }
}
