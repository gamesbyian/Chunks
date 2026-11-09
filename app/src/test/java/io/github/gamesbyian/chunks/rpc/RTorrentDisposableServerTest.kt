package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Only run against ephemeral loopback rTorrent in a disposable CI job. */
class RTorrentDisposableServerTest {
    @Test fun realLegacyServerReadOnlyCompatibility() = runBlocking {
        val endpoint = System.getenv("CHUNKS_DISPOSABLE_RT_URL")
        assumeTrue("Only enable in ephemeral CI", !endpoint.isNullOrBlank())
        require(endpoint!!.startsWith("http://127.0.0.1:")) {
            "This acceptance test must never contact a real remote server"
        }
        val client = RTorrentRemote(RemoteCredentials(endpoint, "", ""))
        val version = client.version()
        assertTrue(version.isNotBlank())
        assertTrue(client.listTransfers().isEmpty())
        assertEquals(version, client.probe().version)
    }
    /**
     * Challenge a named identity on a numbered HTTP route while forwarding
     * ONLY read-only XML-RPC to the actual disposable rTorrent engine.
     * Production HTTP Basic negotiation, real XML-RPC, no protected host.
     */
    @Test fun authenticatedNumberedProxyReadbackUsesOnlyTheReviewedPrincipal() = runBlocking {
        val upstream = System.getenv("CHUNKS_DISPOSABLE_RT_URL")
        assumeTrue("Only run against disposable loopback rTorrent", !upstream.isNullOrBlank())
        val parsed = URI(upstream)
        require(parsed.scheme == "http" && parsed.host == "127.0.0.1" &&
            parsed.path == "/RPC2" && parsed.port in 1..65535 &&
            parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null) {
            "The authenticated fixture may forward only to the local disposable RPC"
        }

        val route = "/rutorrent3/RPC2"
        val principal = "disposable-route-three"
        val password = "throwaway-only"
        val expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
            "$principal:$password".toByteArray(Charsets.UTF_8))
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val forwarded = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)

        fun unauthorized(exchange: HttpExchange) {
            exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"route-three\"")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }

        server.createContext(route) { exchange ->
            val body = exchange.requestBody.use { it.readBytes() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(body.toString(Charsets.UTF_8))?.groupValues?.get(1).orEmpty()
            seen += method
            if (exchange.requestHeaders.getFirst("Authorization") != expectedAuth) {
                unauthorized(exchange)
                return@createContext
            }
            // Mutations are refused even if a future test changes its caller.
            if (exchange.requestMethod != "POST" ||
                method !in setOf("system.client_version", "d.multicall2")) {
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
                return@createContext
            }
            val proxy = parsed.toURL().openConnection() as HttpURLConnection
            try {
                proxy.requestMethod = "POST"
                proxy.connectTimeout = 8_000
                proxy.readTimeout = 15_000
                proxy.instanceFollowRedirects = false
                proxy.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                proxy.doOutput = true
                proxy.setFixedLengthStreamingMode(body.size)
                proxy.outputStream.use { it.write(body) }
                val status = proxy.responseCode
                if (status !in 200..299 || proxy.contentLengthLong > 4_194_304L) {
                    exchange.sendResponseHeaders(502, -1)
                    exchange.close()
                    return@createContext
                }
                val bytes = proxy.inputStream.use { it.readNBytes(4_194_305) }
                if (bytes.size > 4_194_304) {
                    exchange.sendResponseHeaders(502, -1)
                    exchange.close()
                    return@createContext
                }
                forwarded += method
                exchange.responseHeaders.set("Content-Type", "text/xml")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                proxy.disconnect()
            }
        }
        server.start()
        try {
            val gateway = "http://127.0.0.1:" + server.address.port + route
            val verifiedCredentials = RemoteCredentials(gateway, principal, password)
            val authenticated = RTorrentRemote(verifiedCredentials)
            assertTrue(authenticated.version().isNotBlank())
            assertTrue("The authenticated empty-server read must be genuine",
                authenticated.listTransfers("main").isEmpty())
            assertEquals(RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED,
                authenticated.readAuthenticationEvidence())
            requireRemoteConnectIdentityEvidence(verifiedCredentials, authenticated)
            assertTrue(authenticated.addAccountScope().contains(route))
            assertEquals(listOf("system.client_version", "d.multicall2"), forwarded)

            val denied = RTorrentRemote(RemoteCredentials(gateway, principal, "wrong"))
            val denial = assertThrows(RpcHttpStatusException::class.java) {
                runBlocking { denied.version() }
            }
            assertEquals(401, denial.status)
            assertEquals(RemoteReadAuthEvidence.NO_READ_CHALLENGE,
                denied.readAuthenticationEvidence())
            assertEquals("Wrong principal never reached the engine", 2, forwarded.size)

            // A sibling numbered instance has no gateway route or authority.
            val sibling = gateway.replace("/rutorrent3/", "/rutorrent2/")
            val routeError = assertThrows(RpcHttpStatusException::class.java) {
                runBlocking {
                    RTorrentRemote(RemoteCredentials(sibling, principal, password)).version()
                }
            }
            assertEquals(404, routeError.status)
            assertEquals(2, forwarded.size)
            assertTrue("Only two read-only XML-RPC calls reached the real server",
                forwarded.all { it in setOf("system.client_version", "d.multicall2") })
            assertEquals(6, seen.size)
        } finally {
            server.stop(0)
        }
    }

}
