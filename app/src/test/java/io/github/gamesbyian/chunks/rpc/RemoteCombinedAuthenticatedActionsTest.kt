package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Combined-main regression: exact numbered account, a public version getter,
 * a protected read challenge, an authenticated first priority write, and
 * a one-shot Recheck on the SAME disposable localhost RPC transport.
 * All inputs are synthetic; no user server, tracker or file is contacted.
 */
class RemoteCombinedAuthenticatedActionsTest {
    private val hash = "ab".repeat(20)

    private fun rpc(result: String): String =
        "<methodResponse><params><param><value>$result</value></param></params></methodResponse>"
    private fun value(text: String) = "<value><string>$text</string></value>"
    private fun number(value: Long) = "<value><i8>$value</i8></value>"

    private inner class Fixture : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val two = Account("tenant2", "secret2", "Second Server", "/srv/tenant2")
        val three = Account("tenant3", "secret3", "Third Server", "/srv/tenant3")
        val root get() = "http://127.0.0.1:${server.address.port}"

        inner class Account(
            user: String, password: String,
            val title: String, val directory: String
        ) {
            val acceptedMethods: MutableList<String> =
                Collections.synchronizedList(mutableListOf())
            val unauthenticatedMethods: MutableList<String> =
                Collections.synchronizedList(mutableListOf())
            val authentication = "Basic " + Base64.getEncoder().encodeToString(
                "$user:$password".toByteArray(Charsets.UTF_8))
            @Volatile var priority = 1
            @Volatile var checking = false
            @Volatile var priorityWrites = 0
            @Volatile var recheckWrites = 0
            @Volatile var headRequests = 0

            fun reply(exchange: HttpExchange, status: Int, xml: String = "") {
                val bytes = xml.toByteArray(Charsets.UTF_8)
                if (status == 401) {
                    exchange.responseHeaders.set("WWW-Authenticate", "Basic realm=\"test\"")
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                } else {
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }

            fun respond(exchange: HttpExchange) {
                if (exchange.requestMethod == "HEAD") {
                    headRequests++
                    exchange.sendResponseHeaders(405, -1)
                    exchange.close()
                    return
                }
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(body)?.groupValues?.get(1).orEmpty()
                val authorized = exchange.requestHeaders.getFirst("Authorization") == authentication
                // Real reverse proxies often expose version publicly while
                // checking both read inventory and all writes.
                if (method == "system.client_version") {
                    reply(exchange, 200, rpc("<string>0.9.8</string>"))
                    return
                }
                if (!authorized) {
                    unauthenticatedMethods += method
                    reply(exchange, 401)
                    return
                }
                acceptedMethods += method
                val response = when (method) {
                    "d.multicall2" -> {
                        val fields = listOf(
                            value(hash), value(title), number(42), number(0),
                            number(0), value(directory), number(0), number(0), number(0)
                        ).joinToString("")
                        "<array><data><value><array><data>$fields</data></array></value></data></array>"
                    }
                    "f.multicall" -> {
                        val fields = value("payload.bin") + number(42) + number(priority.toLong())
                        "<array><data><value><array><data>$fields</data></array></value></data></array>"
                    }
                    "d.hash" -> "<string>$hash</string>"
                    "d.name" -> "<string>$title</string>"
                    "d.directory" -> "<string>$directory</string>"
                    "d.hashing" -> "<i8>0</i8>"
                    "d.is_hash_checking" -> number(if (checking) 1 else 0).removePrefix("<value>").removeSuffix("</value>")
                    "f.priority.set" -> {
                        assertTrue("Never target an unrelated job", body.contains("$hash:f0"))
                        priorityWrites++
                        priority = 2
                        "<i8>0</i8>"
                    }
                    "d.update_priorities" -> {
                        priorityWrites++
                        "<i8>0</i8>"
                    }
                    "d.check_hash" -> {
                        recheckWrites++
                        checking = true
                        "<i8>0</i8>"
                    }
                    else -> error("Unexpected method $method")
                }
                reply(exchange, 200, rpc(response))
            }
        }

        init {
            server.createContext("/rutorrent2/plugins/httprpc/action.php") { two.respond(it) }
            server.createContext("/rutorrent3/plugins/httprpc/action.php") { three.respond(it) }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    @Test fun numberedPublicVersionAndAcceptedReadAuthenticatePriorityThenRecheckOnce() = runBlocking {
        Fixture().use { f ->
            val remote = RTorrentRemote(RemoteCredentials(f.root + "/rutorrent2/",
                "tenant2", "secret2"))
            val inventory = remote.listTransfers("main")
            assertEquals(listOf(hash), inventory.map { it.hash })
            assertEquals("Second Server", inventory.single().name)
            assertEquals(RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED,
                remote.readAuthenticationEvidence())
            assertEquals(1, f.two.unauthenticatedMethods.count { it == "d.multicall2" })
            assertTrue(f.three.acceptedMethods.isEmpty())

            assertEquals(FilePriorityOutcome.Verified(hash, 0, 2),
                remote.setFilePriority(hash, 0, "payload.bin", 42, 1, 2))
            assertEquals(2, f.two.priorityWrites)
            assertEquals(1, f.two.acceptedMethods.count { it == "f.priority.set" })
            assertEquals(1, f.two.acceptedMethods.count { it == "d.update_priorities" })
            assertEquals(0, f.two.unauthenticatedMethods.count { it == "f.priority.set" })
            assertEquals(0, f.two.unauthenticatedMethods.count { it == "d.update_priorities" })

            assertEquals(RemoteHashRecheckOutcome.HashingObserved(hash),
                remote.requestHashRecheck(hash, "Second Server", "/srv/tenant2"))
            assertEquals(RemoteHashRecheckObservation.Checking,
                remote.observeHashRecheck(hash, "Second Server", "/srv/tenant2"))
            assertEquals(1, f.two.recheckWrites)
            assertEquals(1, f.two.acceptedMethods.count { it == "d.check_hash" })
            assertFalse(f.two.unauthenticatedMethods.contains("d.check_hash"))
            assertTrue("HEAD must not be allowed to cause a second write",
                f.two.headRequests >= 2)
            assertTrue("Sibling numbered endpoint must receive nothing",
                f.three.acceptedMethods.isEmpty() && f.three.unauthenticatedMethods.isEmpty())
        }
    }

    @Test fun wrongPrincipalWithSharedHashCannotBorrowOtherAccountChallengeOrMutate() = runBlocking {
        Fixture().use { f ->
            val legitimate = RTorrentRemote(RemoteCredentials(f.root + "/rutorrent2/",
                "tenant2", "secret2"))
            legitimate.listTransfers("main")
            val wrong = RTorrentRemote(RemoteCredentials(f.root + "/rutorrent3/",
                "tenant2", "secret2"))
            try {
                wrong.requestHashRecheck(hash, "Third Server", "/srv/tenant3")
                fail("A challenge on one numbered route cannot authorize a sibling")
            } catch (_: RpcHttpStatusException) { }
            assertEquals(0, f.two.recheckWrites)
            assertEquals(0, f.three.recheckWrites)
            assertTrue(f.three.acceptedMethods.isEmpty())
            assertTrue(f.three.unauthenticatedMethods.contains("d.hash"))
            assertFalse(f.two.acceptedMethods.contains("d.check_hash"))
        }
    }
}
