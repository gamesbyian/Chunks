package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * A fresh Connect must not save an account that saved-profile restoration
 * would immediately refuse. This is a localhost challenged HTTP gateway, not
 * a live rTorrent/peer or installed-Android acceptance test.
 */
class RemoteVersionEvidenceTest {
    private class Gateway(private val reportedVersion: String) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endpoint: String get() = "http://127.0.0.1:${server.address.port}/RPC2"
        private val accepted = "Basic " + Base64.getEncoder().encodeToString(
            "alice:secret".toByteArray(Charsets.UTF_8))

        init {
            server.createContext("/RPC2") { exchange ->
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(body)?.groupValues?.get(1).orEmpty()
                methods += method
                if (exchange.requestHeaders.getFirst("Authorization") != accepted) {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"version\"")
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                } else {
                    val value = when (method) {
                        "system.client_version" -> "<string>$reportedVersion</string>"
                        "d.multicall2" -> "<array><data/></array>"
                        else -> error("Unexpected remote operation: $method")
                    }
                    val reply = ("<methodResponse><params><param><value>" +
                        value + "</value></param></params></methodResponse>")
                        .toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, reply.size.toLong())
                    exchange.responseBody.use { it.write(reply) }
                }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private suspend fun connectVersion(endpoint: String): String =
        RemoteSmartConnect.connect(endpoint, "alice", "secret") { credentials ->
            val remote = RTorrentRemote(credentials)
            val version = remote.version()
            remote.listTransfersWithMetadata("main")
            requireRemoteConnectIdentityEvidence(credentials, remote)
            version
        }.response

    @Test fun blankOverlongOrControlVersionCannotAuthenticateFreshConnection() = runBlocking {
        for (reported in listOf("", "   ", "a".repeat(121), "0.9.8\n")) {
            Gateway(reported).use { gateway ->
                try {
                    connectVersion(gateway.endpoint)
                    fail("A malformed version must never become a verified Connect result")
                } catch (failed: RemoteSmartConnect.ConnectionFailed) {
                    assertEquals(RemoteSmartConnect.Category.PROTOCOL, failed.category)
                    assertEquals(1, failed.tested)
                    assertEquals(1, failed.attempts.size)
                }
                assertTrue("Stop before showing an apparently live inventory",
                    gateway.methods.all { it == "system.client_version" })
            }
        }
    }

    @Test fun ordinaryChallengedVersionAndEmptyMainViewStillConnect() = runBlocking {
        Gateway("0.9.8").use { gateway ->
            assertEquals("0.9.8", connectVersion(gateway.endpoint))
            assertEquals(setOf("system.client_version", "d.multicall2"),
                gateway.methods.toSet())
        }
    }
}
