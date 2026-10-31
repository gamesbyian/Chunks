package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** HEAD is an optional credential-free discovery step, never a mutation retry. */
class RemoteWriteHeadChallengeTest {
    private val hash = "a".repeat(40)
    private val basic = """Basic realm="write""""
    private val digest = """Digest realm="write", nonce="one", qop="auth", algorithm=SHA-256"""
    private val ok = "<methodResponse><params><param><value><i8>1</i8></value></param></params></methodResponse>"
    private data class Request(val http: String, val method: String, val auth: String?)

    private class Gateway(private val answer: (HttpExchange, String) -> Unit) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
        val endpoint get() = "http://127.0.0.1:${server.address.port}/RPC2"
        init {
            server.createContext("/RPC2") { exchange ->
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>").find(body)?.groupValues?.get(1).orEmpty()
                requests += Request(exchange.requestMethod, method, exchange.requestHeaders.getFirst("Authorization"))
                answer(exchange, method)
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    private fun reply(e: HttpExchange, status: Int, body: String = "", challenge: String? = null) {
        if (challenge != null) e.responseHeaders.add("WWW-Authenticate", challenge)
        if (e.requestMethod == "HEAD" || status == 401 || status in 300..399) {
            e.sendResponseHeaders(status, -1)
            e.close()
        } else {
            val bytes = body.toByteArray(Charsets.UTF_8)
            e.sendResponseHeaders(status, bytes.size.toLong())
            e.responseBody.use { it.write(bytes) }
        }
    }

    @Test fun sameResourceHeadChallengeAuthorizesExactlyOneWriteAndReadback() = runBlocking {
        for (challenge in listOf(basic, digest)) {
            var active = false
            Gateway { e, method ->
                when {
                    e.requestMethod == "HEAD" -> reply(e, 401, challenge = challenge)
                    method == "system.client_version" -> reply(e, 200, ok)
                    method == "d.start" -> {
                        val authorization = e.requestHeaders.getFirst("Authorization").orEmpty()
                        val accepted = if (challenge == basic) {
                            authorization == "Basic " + Base64.getEncoder().encodeToString("alice:secret".toByteArray())
                        } else {
                            authorization.startsWith("Digest ") && authorization.contains("uri=\"/RPC2\"")
                        }
                        if (accepted) { active = true; reply(e, 200, ok) } else reply(e, 401, challenge = challenge)
                    }
                    method == "d.is_active" -> reply(e, 200, if (active) ok else ok.replace("<i8>1</i8>", "<i8>0</i8>"))
                    else -> error("Unexpected request")
                }
            }.use { gateway ->
                val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret"))
                assertEquals(1L, remote.call("d.start", listOf(hash)))
                assertEquals(1L, remote.call("d.is_active", listOf(hash)))
                assertEquals(listOf("system.client_version", "", "d.start", "d.is_active"), gateway.requests.map { it.method })
                assertEquals("HEAD", gateway.requests[1].http)
                assertNull(gateway.requests[1].auth)
                assertEquals(1, gateway.requests.count { it.method == "d.start" })
            }
        }
    }

    @Test fun publicOrUnsupportedHeadNeverDisclosesCredentialsOrRetriesWrite() = runBlocking {
        for (headStatus in listOf(200, 400, 403, 404, 405, 415, 501)) {
            Gateway { e, method -> when {
                e.requestMethod == "HEAD" -> reply(e, headStatus)
                method == "system.client_version" -> reply(e, 200, ok)
                method == "d.start" -> reply(e, 401, challenge = digest)
                else -> error("Unexpected request")
            } }.use { gateway ->
                try {
                    RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret")).call("d.start", listOf(hash))
                    fail("The unauthenticated write is uncertain")
                } catch (_: IOException) {
                    assertEquals(1, gateway.requests.count { it.method == "d.start" })
                    assertTrue(gateway.requests.all { it.auth == null })
                }
            }
        }
    }

    @Test fun forbiddenHeadDoesNotBlockAWorkingAnonymousPost() = runBlocking {
        Gateway { e, method -> when {
            e.requestMethod == "HEAD" -> reply(e, 403)
            method == "system.client_version" || method == "d.start" -> reply(e, 200, ok)
            else -> error("Unexpected request")
        } }.use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret"))
            assertEquals(1L, remote.call("d.start", listOf(hash)))
            assertEquals(1, gateway.requests.count { it.method == "d.start" })
            assertTrue(gateway.requests.all { it.auth == null })
        }
    }

    @Test fun headRateLimitAndProxyAuthenticationStopBeforeMutation() = runBlocking {
        for (status in listOf(407, 429)) {
            Gateway { e, method -> when {
                e.requestMethod == "HEAD" -> reply(e, status)
                method == "system.client_version" -> reply(e, 200, ok)
                else -> error("Write must not be sent")
            } }.use { gateway ->
                try {
                    RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret")).call("d.start", listOf(hash))
                    fail("Preflight must stop")
                } catch (_: IOException) {
                    assertEquals(listOf("system.client_version", ""), gateway.requests.map { it.method })
                    assertTrue(gateway.requests.all { it.auth == null })
                }
            }
        }
    }

    @Test fun postApplyChallengeAfterHeadDiscoveryIsNeverReplayed() = runBlocking {
        var applied = false
        Gateway { e, method -> when {
            e.requestMethod == "HEAD" -> reply(e, 401, challenge = basic)
            method == "system.client_version" -> reply(e, 200, ok)
            method == "d.start" -> { applied = true; reply(e, 401, challenge = basic) }
            method == "d.is_active" -> reply(e, 200, if (applied) ok else ok.replace("<i8>1</i8>", "<i8>0</i8>"))
            else -> error("Unexpected request")
        } }.use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret"))
            try {
                remote.call("d.start", listOf(hash))
                fail("A post-apply 401 is uncertain")
            } catch (_: IOException) {
                assertEquals(1L, remote.call("d.is_active", listOf(hash)))
                assertEquals(1, gateway.requests.count { it.method == "d.start" })
            }
        }
    }

    @Test fun redirectAndMalformedDigestCannotTriggerWriteOrBasicFallback() = runBlocking {
        for (status in listOf(302, 401)) {
            Gateway { e, method -> when {
                e.requestMethod == "HEAD" -> {
                    if (status == 302) e.responseHeaders.add("Location", "http://127.0.0.1:1/elsewhere")
                    reply(e, status, challenge = if (status == 401) "Basic realm=\"x\", Digest realm=\"x\", nonce=\"n\", algorithm=SHA-512" else null)
                }
                method == "system.client_version" -> reply(e, 200, ok)
                else -> error("Write must not be sent")
            } }.use { gateway ->
                try {
                    RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret")).call("d.start", listOf(hash))
                    fail("Unsafe preflight must fail")
                } catch (_: IOException) {
                    assertEquals(listOf("system.client_version", ""), gateway.requests.map { it.method })
                    assertTrue(gateway.requests.all { it.auth == null })
                }
            }
        }
    }
}
