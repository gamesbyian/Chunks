package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Data-integrity: exact native-default observations; synthetic read-only gateway, not peer proof. */
class NativeDefaultObservationTest {
    private val hash = "ab".repeat(20)
    private fun fixture(directory: String, multi: Boolean = false, metadata: Boolean = false,
                        check: suspend (RTorrentRemote, List<String>) -> Unit) = runBlocking {
        val methods = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/RPC2") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(request)!!.groupValues[1]
            methods += method
            val value = when (method) {
                "d.multicall2" -> "<array><data></data></array>"
                "d.hash" -> "<string>$hash</string>"
                "d.name" -> "<string>Fixture</string>"
                "d.directory" -> "<string>$directory</string>"
                "system.cwd" -> "<string>/server/work</string>"
                "d.is_multi_file" -> "<i8>${if (multi) 1 else 0}</i8>"
                "d.is_meta" -> "<i8>${if (metadata) 1 else 0}</i8>"
                "d.state", "d.is_active", "d.down.total", "d.completed_bytes", "d.peers_connected", "d.down.rate" -> "<i8>0</i8>"
                "d.size_bytes" -> "<i8>100</i8>"
                else -> error("Unexpected mutation or getter: $method")
            }
            val bytes = "<methodResponse><params><param><value>$value</value></param></params></methodResponse>".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { check(RTorrentRemote(RemoteCredentials("http://127.0.0.1:${server.address.port}/RPC2", "", "")), methods) }
        finally { server.stop(0) }
    }
    private fun plan(parent: String = "") = StoppedRemoteAddPlan.fromMagnetAtServerDefault("magnet:?xt=urn:btih:$hash", parent)

    @Test fun aRootDefaultKeepsOneSeparatorForMultiFileReadback() = fixture("/Fixture", multi = true) { remote, _ ->
        assertEquals(RemoteAddOutcome.VerifiedStopped(hash, "/Fixture"), remote.reconcileStoppedAdd(plan("/")))
    }

    @Test fun anUnknownNativePreviewCannotInventAWrongPlaceContradiction() = fixture("/server/work") { remote, methods ->
        assertNull(remote.readExactAddContradiction(plan()))
        assertFalse(methods.any { it.startsWith("load.") || it in setOf("d.start", "d.erase") })
    }

    @Test fun relativeDotDotCannotBeLexicallyCertifiedAcrossUnknownServerSymlinks() = fixture("link/../payload") { remote, methods ->
        assertFalse(remote.reconcileStoppedAdd(plan()) is RemoteAddOutcome.VerifiedStopped)
        assertFalse(methods.contains("system.cwd"))
    }

    @Test fun magnetMetadataCanBeReportedWithoutCertifyingItsScratchFolderAsPayload() = fixture("/session/scratch", metadata = true) { remote, methods ->
        val progress = remote.readAddProgress(plan())
        assertTrue(progress.registered)
        assertEquals(true, progress.metadataPending)
        assertFalse("Metadata status does not claim an actual payload Place", methods.contains("d.directory"))
    }
}
