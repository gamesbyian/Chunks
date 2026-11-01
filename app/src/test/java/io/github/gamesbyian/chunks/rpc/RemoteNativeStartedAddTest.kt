package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The actual Android Add path uses native start-on-load, never a second Start. */
class RemoteNativeStartedAddTest {
    private val hash = "ab".repeat(20)
    private val destination = "/downloads"
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=Test"
    private val metainfo = (
        "d4:info" +
            "d6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
            "01234567890123456789e" +
            "e"
        ).toByteArray(Charsets.UTF_8)
    private fun value(s: String) = "<value><string>$s</string></value>"
    private fun num(n: Long) = "<value><i8>$n</i8></value>"
    private fun reply(exchange: HttpExchange, answer: String) {
        val xml = "<methodResponse><params><param>$answer</param></params></methodResponse>"
            .toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, xml.size.toLong())
        exchange.responseBody.use { it.write(xml) }
    }
    private fun absent(exchange: HttpExchange) {
        val body = """<methodResponse><fault><value><struct>
            <member><name>faultCode</name><value><int>-501</int></value></member>
            <member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>
            </struct></value></fault></methodResponse>""".toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun exercise(
        file: Boolean, active: Long = 1L, loseAcknowledgement: Boolean = false,
        transdroidStyle: Boolean = false, actualDirectory: String = destination
    ): Pair<RemoteAddAndStart, List<String>> = runBlocking {
        val expectedHash = if (file) TorrentMetainfoReader.preview(metainfo).infoHashV1 else hash
        val commands = mutableListOf<String>()
        var loaded = false
        var sentBytes: ByteArray? = null
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(xml)?.groupValues?.get(1).orEmpty()
            commands += method
            if (method == "d.name" && !loaded) {
                absent(exchange)
                return@createContext
            }
            if (method in setOf("load.start", "load.raw_start") && loseAcknowledgement) {
                loaded = true // Applied on the server before the reply was lost.
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
                return@createContext
            }
            val answer = when (method) {
                "d.multicall2" -> "<value><array><data></data></array></value>"
                "d.name" -> value(if (file) "test.txt" else "Test")
                "d.hash" -> value(expectedHash)
                "d.directory" -> value(actualDirectory)
                "d.is_multi_file" -> num(0)
                "d.state" -> num(1)
                "d.is_active" -> num(active)
                "load.start" -> {
                    assertFalse("File test should not send magnet", file)
                    if (transdroidStyle) {
                        assertFalse(xml.contains("d.directory.set"))
                        assertEquals(2, Regex("<param>").findAll(xml).count())
                    } else assertTrue(xml.contains("d.directory.set=/downloads"))
                    loaded = true
                    num(0)
                }
                "load.raw_start" -> {
                    assertTrue("Magnet test should not send file", file)
                    if (transdroidStyle) {
                        assertFalse(xml.contains("d.directory.set"))
                        assertEquals(2, Regex("<param>").findAll(xml).count())
                    } else assertTrue(xml.contains("d.directory.set=/downloads"))
                    sentBytes = Regex("<base64>([^<]+)</base64>")
                        .find(xml)?.groupValues?.get(1)
                        ?.let { Base64.getDecoder().decode(it) }
                    loaded = true
                    num(0)
                }
                else -> throw AssertionError("Unexpected RPC: $method")
            }
            reply(exchange, answer)
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            val plan = StoppedRemoteAddPlan.fromMagnet(
                if (file) "magnet:?xt=urn:btih:$expectedHash" else magnet, destination)
            val result = if (transdroidStyle) remote.loadStartedWithoutDirectoryCommand(
                plan, if (file) metainfo else null)
                else if (file) remote.loadStartedTorrentBytes(metainfo, destination)
                else remote.loadStarted(plan)
            if (file && !loseAcknowledgement) assertArrayEquals(metainfo, sentBytes)
            result to commands.toList()
        } finally {
            server.stop(0)
        }
    }

    @Test fun magnetUsesOneNativeStartOnLoadAndVerifiesExactHashPlaceAndActiveState() {
        val (result, commands) = exercise(file = false)
        assertEquals(RemoteAddAndStart.Started(hash), result)
        assertEquals(1, commands.count { it == "load.start" })
        assertEquals(2, commands.count { it == "d.hash" })
        assertFalse(commands.any { it in listOf("d.start", "d.resume", "load.verbose") })
    }

    @Test fun binaryUsesOneRawNativeStartWithoutReencodingPayload() {
        val expected = TorrentMetainfoReader.preview(metainfo).infoHashV1
        val (result, commands) = exercise(file = true)
        assertEquals(RemoteAddAndStart.Started(expected), result)
        assertEquals(1, commands.count { it == "load.raw_start" })
        assertEquals(2, commands.count { it == "d.hash" })
        assertFalse(commands.any { it in listOf("d.start", "d.resume", "load.raw_verbose") })
    }

    @Test fun acceptedStartedFlagWithoutActiveEngineCannotBeCalledSuccess() {
        val (result, commands) = exercise(file = false, active = 0)
        assertTrue(result is RemoteAddAndStart.StartNotVerified)
        assertEquals(1, commands.count { it == "load.start" })
        assertFalse(commands.any { it == "d.start" || it == "d.resume" })
    }

    @Test fun transdroidStyleFileAndMagnetSendExactlyTwoLoadArguments() {
        val expected = TorrentMetainfoReader.preview(metainfo).infoHashV1
        val (fileResult, fileCommands) = exercise(file = true, transdroidStyle = true)
        assertEquals(RemoteAddAndStart.Started(expected), fileResult)
        assertEquals(1, fileCommands.count { it == "load.raw_start" })
        val (magnetResult, magnetCommands) = exercise(file = false, transdroidStyle = true)
        assertEquals(RemoteAddAndStart.Started(hash), magnetResult)
        assertEquals(1, magnetCommands.count { it == "load.start" })
        assertFalse((fileCommands + magnetCommands).any {
            it in listOf("d.directory.set", "d.start", "d.resume")
        })
    }

    @Test fun transdroidStyleNeverClaimsCustomPlaceVerifiedIfServerUsesDifferentPath() {
        val (result, commands) = exercise(file = false, transdroidStyle = true,
            actualDirectory = "/another-place")
        assertTrue(result is RemoteAddAndStart.StartNotVerified)
        assertEquals(1, commands.count { it == "load.start" })
    }

    @Test fun transdroidStyleLostAcknowledgementNeverRetriesOrFallsBack() {
        val (result, commands) = exercise(file = false, transdroidStyle = true,
            loseAcknowledgement = true)
        assertEquals(RemoteAddAndStart.StartNotVerified(hash, null), result)
        assertEquals(listOf("d.multicall2", "d.name", "load.start"), commands)
    }

    @Test fun lostStartOnLoadAcknowledgementNeverReplaysMutation() {
        val (result, commands) = exercise(file = false, loseAcknowledgement = true)
        assertEquals(RemoteAddAndStart.StartNotVerified(hash, null), result)
        assertEquals(listOf("d.multicall2", "d.name", "load.start"), commands)
    }
}
