package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * A malicious or inconsistent XML-RPC gateway cannot turn fractional,
 * negative, out-of-range, or 32-bit-wrapped values into an accepted snapshot.
 * Each test server is loopback-only. No mutation may be sent.
 */
class RemoteNumericInventoryIntegrityTest {
    private val hash = "ab".repeat(20)
    private fun text(value: String) = "<value><string>$value</string></value>"
    private fun integer(value: Long) = "<value><i8>$value</i8></value>"
    private fun fractional(value: String) = "<value><double>$value</double></value>"
    private fun arrayRow(values: List<String>) =
        "<array><data><value><array><data>${values.joinToString("")}</data></array></value></data></array>"
    private fun rpc(value: String) =
        "<methodResponse><params><param><value>$value</value></param></params></methodResponse>"

    private class Gateway(private val response: (String) -> String) : AutoCloseable {
        private val http = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endpoint get() = "http://127.0.0.1:${http.address.port}/RPC2"

        init {
            http.createContext("/RPC2") { exchange ->
                val xml = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(xml)?.groupValues?.get(1).orEmpty()
                methods += method
                val reply = response(method).toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, reply.size.toLong())
                exchange.responseBody.use { it.write(reply) }
            }
            http.start()
        }

        override fun close() = http.stop(0)
    }

    private fun validTransfer() = listOf(
        text(hash), text("sample"), integer(100), integer(50), integer(1),
        text("/srv/fixtures"), integer(2), integer(3), integer(1500)
    )
    private fun validFile() = listOf(text("sample.bin"), integer(50), integer(1))

    @Test fun remoteTransferRefusesInvalidCountersAndState() = runBlocking {
        val mutations = listOf(
            2 to integer(-1),
            2 to fractional("100.5"),
            2 to fractional("NaN"),
            3 to integer(-1),
            3 to integer(101),
            4 to integer(-1),
            4 to integer(2),
            4 to fractional("0.5"),
            6 to integer(-1),
            7 to fractional("2.5"),
            8 to integer(-1)
        )
        for ((position, badValue) in mutations) {
            val values = validTransfer().toMutableList().also { it[position] = badValue }
            Gateway { method ->
                assertEquals("Only complete read-only inventory is permitted", "d.multicall2", method)
                rpc(arrayRow(values))
            }.use { gateway ->
                try {
                    RTorrentRemote(RemoteCredentials(gateway.endpoint, "", "")).listTransfers()
                    fail("Invalid transfer field $position was accepted: $badValue")
                } catch (_: IllegalArgumentException) {
                    assertEquals(listOf("d.multicall2"), gateway.methods)
                }
            }
        }
    }

    @Test fun fileInventoryRefusesTruncationAndOverflowBeforeAnyPriorityWrite() = runBlocking {
        val mutations = listOf(
            0 to text(""),
            0 to text("a".repeat(4097)),
            1 to integer(-1),
            1 to fractional("10.9"),
            2 to integer(-1),
            2 to integer(3),
            2 to integer(4_294_967_298L),
            2 to fractional("2.0")
        )
        for ((position, badValue) in mutations) {
            val values = validFile().toMutableList().also { it[position] = badValue }
            Gateway { method ->
                assertEquals("Unexpected write or second query", "f.multicall", method)
                rpc(arrayRow(values))
            }.use { gateway ->
                try {
                    RTorrentRemote(RemoteCredentials(gateway.endpoint, "", "")).listFiles(hash)
                    fail("Invalid remote file field $position was accepted")
                } catch (_: IllegalArgumentException) {
                    assertEquals(listOf("f.multicall"), gateway.methods)
                }
            }
        }
    }

    @Test fun standardIntI4AndI8WireIntegersRemainReadable() = runBlocking {
        // Older XML-RPC gateways use <int> or <i4> instead of <i8>.
        // The codec must normalize all three without admitting <double>.
        val wire = listOf("int", "i4", "i8")
        for (tag in wire) {
            fun value(n: Int) = "<value><$tag>$n</$tag></value>"
            val transfer = validTransfer().toMutableList().also {
                it[2] = value(100)
                it[3] = value(50)
                it[4] = value(1)
                it[6] = value(2)
                it[7] = value(3)
                it[8] = value(1500)
            }
            val file = validFile().toMutableList().also {
                it[1] = value(50)
                it[2] = value(1)
            }
            Gateway { method -> when (method) {
                "d.multicall2" -> rpc(arrayRow(transfer))
                "f.multicall" -> rpc(arrayRow(file))
                else -> error("Unexpected method $method")
            } }.use { gateway ->
                val client = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                val item = client.listTransfers().single()
                assertEquals("Wire $tag size", 100L, item.sizeBytes)
                assertEquals("Wire $tag progress", 50L, item.completedBytes)
                assertTrue("Wire $tag started", item.started)
                assertEquals("Wire $tag rate", 2L, item.downBytesPerSecond)
                val selected = client.listFiles(hash).single()
                assertEquals("Wire $tag file size", 50L, selected.sizeBytes)
                assertEquals("Wire $tag priority", 1, selected.priority)
                assertEquals(listOf("d.multicall2", "f.multicall"), gateway.methods)
            }
        }
    }

    @Test fun legitimateLegacyIntegralFieldsRemainReadable() = runBlocking {
        Gateway { method -> when (method) {
            "d.multicall2" -> rpc(arrayRow(validTransfer()))
            "f.multicall" -> rpc(arrayRow(validFile()))
            else -> error("Unexpected method $method")
        } }.use { gateway ->
            val client = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            val transfer = client.listTransfers().single()
            assertEquals(hash, transfer.hash)
            assertEquals(100L, transfer.sizeBytes)
            assertEquals(50L, transfer.completedBytes)
            assertEquals(true, transfer.started)
            assertEquals(2L, transfer.downBytesPerSecond)
            assertEquals(1500L, transfer.ratioThousandths)
            assertEquals(RemoteFile("sample.bin", 50, 1), client.listFiles(hash).single())
            assertEquals(listOf("d.multicall2", "f.multicall"), gateway.methods)
        }
    }
}
