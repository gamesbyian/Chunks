package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteBinaryStoppedAddTest {
    private val bytes = (
        "d4:info" +
        "d6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789e" +
        "e"
    ).toByteArray(Charsets.UTF_8)
    private val destination = "/home/test/Public Samples"
    private fun str(value: String) = "<value><string>" + value + "</string></value>"
    private fun integer(value: Int) = "<value><i8>" + value + "</i8></value>"
    private fun rows(hash: String): String {
        val values = listOf(str(hash), str("test.txt"), integer(5), integer(0),
            integer(0), str(destination), integer(0), integer(0), integer(0))
        return "<value><array><data><value><array><data>" +
            values.joinToString("") + "</data></array></value></data></array></value>"
    }

    private fun respondAbsentHash(exchange: com.sun.net.httpserver.HttpExchange) {
        val reply = """<methodResponse><fault><value><struct>
            <member><name>faultCode</name><value><int>-501</int></value></member>
            <member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>
            </struct></value></fault></methodResponse>""".toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, reply.size.toLong())
        exchange.responseBody.use { it.write(reply) }
    }

    @Test fun binaryTorrentLoadIsStoppedAndExactHashVerified() = runBlocking {
        val expected = TorrentMetainfoReader.preview(bytes).infoHashV1
        val methods = mutableListOf<String>()
        var returnedBytes: ByteArray? = null
        var loaded = false
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(xml)?.groupValues?.get(1).orEmpty()
            methods.add(method)
            if (method == "d.name") { respondAbsentHash(exchange); return@createContext }
            val reply = when (method) {
                "d.multicall2" ->
                    if (loaded) rows(expected) else "<value><array><data></data></array></value>"
                "load.raw_verbose" -> {
                    returnedBytes = Regex("<base64>([^<]+)</base64>").find(xml)
                        ?.groupValues?.get(1)?.let { Base64.getDecoder().decode(it) }
                    loaded = true
                    integer(0)
                }
                "d.directory" -> str(destination)
                "d.is_multi_file" -> integer(0)
                "d.state" -> integer(0)
                else -> integer(0)
            }
            val body = ("<methodResponse><params><param>" + reply +
                "</param></params></methodResponse>").toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:" + server.address.port + "/RPC2", "", ""))
            assertEquals(RemoteAddOutcome.VerifiedStopped(expected, destination),
                remote.loadStoppedTorrentBytes(bytes, destination))
            assertArrayEquals(bytes, returnedBytes)
            assertEquals(listOf("d.multicall2", "d.name", "load.raw_verbose", "d.multicall2",
                "d.directory", "d.is_multi_file", "d.state"), methods)
            assertEquals(RemoteAddOutcome.AlreadyPresent(expected),
                remote.loadStoppedTorrentBytes(bytes, destination))
            assertEquals(1, methods.count { it == "load.raw_verbose" })
        } finally {
            server.stop(0)
        }
    }

    @Test fun unsafeServerDestinationNeverSendsTorrentBytes() = runBlocking {
        val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
        try {
            remote.loadStoppedTorrentBytes(bytes, "/tmp/../outside")
            fail("Must refuse unsafe destination before any network connection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun binaryTorrentRespectsTheServerDefaultWithoutSetter() = runBlocking {
        val expected = TorrentMetainfoReader.preview(bytes).infoHashV1
        var loaded = false
        val commands = mutableListOf<String>()
        var inspectedBinary: ByteArray? = null
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(xml)?.groupValues?.get(1).orEmpty()
            commands.add(method)
            if (method == "d.name") { respondAbsentHash(exchange); return@createContext }
            val reply = when (method) {
                "d.multicall2" ->
                    if (loaded) rows(expected) else "<value><array><data></data></array></value>"
                "directory.default" -> str(destination)
                "load.raw_verbose" -> {
                    if (xml.contains("d.directory.set=")) throw AssertionError("Default path was overridden")
                    inspectedBinary = Regex("<base64>([^<]+)</base64>").find(xml)
                        ?.groupValues?.get(1)?.let { Base64.getDecoder().decode(it) }
                    loaded = true
                    integer(0)
                }
                "d.directory" -> str(destination)
                "d.is_multi_file" -> integer(0)
                "d.state" -> integer(0)
                else -> throw AssertionError("Unexpected method")
            }
            val response = ("<methodResponse><params><param>" + reply +
                "</param></params></methodResponse>").toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:" + server.address.port + "/RPC2", "", ""))
            assertEquals(RemoteAddOutcome.VerifiedStopped(expected, destination),
                remote.loadStoppedTorrentBytes(bytes, destination, useServerDefault = true))
            assertArrayEquals(bytes, inspectedBinary)
            assertEquals(listOf("d.multicall2", "d.name", "directory.default", "load.raw_verbose",
                "d.multicall2", "d.directory", "d.is_multi_file", "d.state"), commands)
        } finally { server.stop(0) }
    }

}
