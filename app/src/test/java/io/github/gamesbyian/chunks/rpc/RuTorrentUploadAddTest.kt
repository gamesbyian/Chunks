package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Loopback ruTorrent (HTTP-RPC plugin + upload form) fixture; no real server. */
class RuTorrentUploadAddTest {
    private val bytes = ("d4:infod6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789ee").toByteArray(Charsets.ISO_8859_1)
    private val hash = TorrentMetainfoReader.preview(bytes).infoHashV1

    private fun value(xml: String) = "<methodResponse><params><param><value>$xml</value></param></params></methodResponse>"

    private fun unknownHashFault(): String = """<methodResponse><fault><value><struct>
        <member><name>faultCode</name><value><int>-501</int></value></member>
        <member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>
        </struct></value></fault></methodResponse>"""

    private class Server(val retain: Boolean, val uploadStatus: Int = 200) : AutoCloseable {
        val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val uploads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var loaded = false
        val base get() = "http://127.0.0.1:${server.address.port}/rutorrent/"
        override fun close() = server.stop(0)
    }

    private fun server(retain: Boolean, uploadStatus: Int = 200): Server = Server(retain, uploadStatus).apply {
        server.createContext("/rutorrent/php/addtorrent.php") { exchange ->
            val body = String(exchange.requestBody.readBytes(), Charsets.ISO_8859_1)
            uploads += body
            if (retain && uploadStatus == 200) loaded = true
            val reply = "{\"result\":\"Success\"}".toByteArray()
            exchange.sendResponseHeaders(uploadStatus, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.createContext("/rutorrent/plugins/httprpc/action.php") { exchange ->
            val request = exchange.requestBody.bufferedReader().readText()
            val reply = when {
                request.contains("<methodName>d.name</methodName>") -> unknownHashFault()
                request.contains("<methodName>d.multicall2</methodName>") -> value("<array><data>" + (if (loaded)
                    "<value><array><data><value><string>${hash.uppercase()}</string></value><value><string>test.txt</string></value>" +
                    "<value><i8>5</i8></value><value><i8>0</i8></value><value><i8>0</i8></value><value><string>/home/dev/downloads</string></value>" +
                    "<value><i8>0</i8></value><value><i8>0</i8></value><value><i8>0</i8></value></data></array></value>" else "") + "</data></array>")
                request.contains("<methodName>d.directory</methodName>") -> value("<string>/home/dev/downloads</string>")
                request.contains("<methodName>d.is_multi_file</methodName>") -> value("<i8>0</i8>")
                request.contains("<methodName>d.state</methodName>") -> value("<i8>0</i8>")
                else -> "<methodResponse><fault><value><struct><member><name>faultCode</name><value><int>-506</int></value></member>" +
                    "<member><name>faultString</name><value><string>no</string></value></member></struct></value></fault></methodResponse>"
            }.toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
    }

    @Test fun uploadUrlRetainsEncodedHostedAccountPrefix() {
        val remote = RTorrentRemote(RemoteCredentials(
            "https://box.test/my%20account/rutorrent/", "", ""))
        assertEquals(
            "https://box.test/my%20account/rutorrent/php/addtorrent.php",
            remote.ruTorrentUploadAddress().toString()
        )
        val percent = RTorrentRemote(RemoteCredentials(
            "https://box.test/%25members/rutorrent/", "", ""))
        assertEquals(
            "https://box.test/%25members/rutorrent/php/addtorrent.php",
            percent.ruTorrentUploadAddress().toString()
        )
        // An ordinary direct RPC endpoint must not invent a ruTorrent upload.
        assertNull(RTorrentRemote(RemoteCredentials(
            "https://box.test/my%20account/RPC2", "", "")).ruTorrentUploadAddress())
    }

    @Test fun uploadsStoppedIntoTheChosenFolderAndVerifiesByReadback() = runBlocking {
        server(retain = true).use { fixture ->
            val remote = RTorrentRemote(RemoteCredentials(fixture.base, "", ""))
            assertTrue(remote.ruTorrentUploadAddress().toString().endsWith("/rutorrent/php/addtorrent.php"))
            val outcome = remote.loadStoppedViaRuTorrentUpload(bytes, "/home/dev/downloads")
            assertEquals(RemoteAddOutcome.VerifiedStopped(hash, "/home/dev/downloads/test.txt".substringBeforeLast('/')), outcome)
            val form = fixture.uploads.single()
            assertTrue(form.contains("name=\"dir_edit\"\r\n\r\n/home/dev/downloads\r\n"))
            assertTrue(form.contains("name=\"torrents_start_stopped\"\r\n\r\n1\r\n"))
            assertTrue(form.contains("name=\"torrent_file\"; filename=\"chunks.torrent\""))
            assertTrue(form.contains(String(bytes, Charsets.ISO_8859_1)))
            // A duplicate is refused before any second upload.
            assertEquals(RemoteAddOutcome.AlreadyPresent(hash), remote.loadStoppedViaRuTorrentUpload(bytes, "/home/dev/downloads"))
            assertEquals(1, fixture.uploads.size)
        }
    }

    @Test fun acknowledgedButNotRetainedIsNeverReportedAsSuccess() = runBlocking {
        server(retain = false).use { fixture ->
            val remote = RTorrentRemote(RemoteCredentials(fixture.base, "", ""))
            assertEquals(RemoteAddOutcome.PendingVerification(hash), remote.loadStoppedViaRuTorrentUpload(bytes, "/home/dev/downloads"))
            assertEquals("No automatic retry", 1, fixture.uploads.size)
        }
    }

    @Test fun serverErrorAfterSendingIsUncertainAndPlainRpcGatewaysHaveNoUploadPath() = runBlocking {
        server(retain = false, uploadStatus = 500).use { fixture ->
            val remote = RTorrentRemote(RemoteCredentials(fixture.base, "", ""))
            assertEquals(RemoteAddOutcome.Uncertain(hash), remote.loadStoppedViaRuTorrentUpload(bytes, "/home/dev/downloads"))
        }
        assertNull(RTorrentRemote(RemoteCredentials("http://127.0.0.1:9/RPC2", "", "")).ruTorrentUploadAddress())
    }
}
