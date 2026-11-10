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
 * Same-origin numbered ruTorrent installations are independent remote engines.
 * A shared infohash never permits a profile's credentials or commands to migrate
 * to its sibling instance. Entire fixture runs on one disposable loopback port.
 */
class RemoteNumberedProfileIsolationTest {
    private val hash = "aB".repeat(20)

    private class Fixture : AutoCloseable {
        private val expectedHash = "aB".repeat(20)
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val mutations2: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val mutations3: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val authorized2: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val authorized3: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val unauthorized2: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val unauthorized3: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        @Volatile var started2 = false
        @Volatile var started3 = false
        val root get() = "http://127.0.0.1:${server.address.port}/"

        init {
            addProfile("rutorrent2", "tenant2", "secret2", mutations2,
                authorized2, unauthorized2, { started2 }, { started2 = it })
            addProfile("rutorrent3", "tenant3", "secret3", mutations3,
                authorized3, unauthorized3, { started3 }, { started3 = it })
            server.start()
        }

        private fun addProfile(
            number: String, user: String, password: String,
            mutations: MutableList<String>, authorized: MutableList<String>,
            unauthorized: MutableList<String?>,
            started: () -> Boolean, setStarted: (Boolean) -> Unit
        ) {
            val required = "Basic " + Base64.getEncoder().encodeToString(
                "$user:$password".toByteArray(Charsets.UTF_8))
            server.createContext("/$number/plugins/httprpc/action.php") { exchange ->
                val request = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val auth = exchange.requestHeaders.getFirst("Authorization")
                if (auth != required) {
                    unauthorized.add(auth)
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"fixture-$number\"")
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                } else {
                    val method = Regex("<methodName>([^<]+)</methodName>")
                        .find(request)?.groupValues?.get(1).orEmpty()
                    authorized.add(method)
                    val result = when (method) {
                        "d.open", "d.resume", "d.close" -> 0
                        "d.start" -> { mutations.add(method); setStarted(true); 0 }
                        "d.stop" -> { mutations.add(method); setStarted(false); 0 }
                        "d.state", "d.is_active" -> if (started()) 1 else 0
                        else -> -1
                    }
                    val payload = if (method == "d.hash")
                        "<string>$expectedHash</string>" else "<int>$result</int>"
                    val body = ("<methodResponse><params><param><value>$payload" +
                        "</value></param></params></methodResponse>").toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
        }

        override fun close() = server.stop(0)
    }

    @Test fun wrongHashIsRefusedBeforeAnyWriteOnTheAuthenticatedInstance() = runBlocking {
        Fixture().use { fixture ->
            val remote = RTorrentRemote(RemoteCredentials(
                fixture.root + "rutorrent2/", "tenant2", "secret2"))
            try {
                remote.setStarted("c".repeat(40), true)
                fail("A valid-looking but different hash must not reach d.open")
            } catch (_: IllegalStateException) { }
            assertEquals(listOf("d.hash"), fixture.authorized2)
            assertTrue(fixture.authorized3.isEmpty())
            assertTrue(fixture.mutations2.isEmpty())
            assertTrue(fixture.mutations3.isEmpty())
            assertFalse(fixture.started2)
            assertFalse(fixture.started3)
        }
    }

    @Test fun rejectedAccountCannotProbeOrMutateSiblingNumberedInstance() = runBlocking {
        Fixture().use { fixture ->
            val wrong = RTorrentRemote(RemoteCredentials(
                fixture.root + "rutorrent3/", "tenant2", "secret2"))
            try {
                wrong.setStarted(hash, true)
                fail("The wrong principal must never mutate the same hash")
            } catch (_: io.github.gamesbyian.chunks.rpc.RpcHttpStatusException) { }
            assertTrue("No authenticated method should reach the wrong instance",
                fixture.authorized3.isEmpty())
            assertTrue("There must be no fallback to the sibling account",
                fixture.authorized2.isEmpty())
            assertTrue(fixture.mutations2.isEmpty())
            assertTrue(fixture.mutations3.isEmpty())
        }
    }

    @Test fun sameInfohashRemainsIsolatedAcrossNumberedRpcMounts() = runBlocking {
        Fixture().use { fixture ->
            val two = RTorrentRemote(RemoteCredentials(
                fixture.root + "rutorrent2/", "tenant2", "secret2"))
            val three = RTorrentRemote(RemoteCredentials(
                fixture.root + "rutorrent3/", "tenant3", "secret3"))

            assertEquals(LifecycleOutcome.Verified(true), two.setStarted(hash, true))
            assertEquals(listOf("d.start"), fixture.mutations2)
            assertTrue(fixture.mutations3.isEmpty())
            assertTrue(fixture.authorized3.isEmpty())
            assertTrue(fixture.started2)
            assertFalse(fixture.started3)

            assertEquals(LifecycleOutcome.Verified(false), three.setStarted(hash, false))
            assertEquals(listOf("d.stop"), fixture.mutations3)
            assertEquals(listOf("d.start"), fixture.mutations2)
            assertTrue(fixture.started2)
            assertFalse(fixture.started3)

            assertEquals(listOf("d.hash", "d.open", "d.start", "d.resume",
                "d.state", "d.is_active", "d.hash"), fixture.authorized2)
            // A stopped server may read both the persistent and active flags.
            // Keep every readback on profile 3, even when profile 2 holds the same hash.
            assertEquals(listOf("d.hash", "d.stop", "d.close", "d.state",
                "d.is_active", "d.hash"), fixture.authorized3)
            assertTrue("second profile must never receive first profile credentials",
                fixture.unauthorized3.all { it == null })
            assertTrue("first profile must never receive second profile credentials",
                fixture.unauthorized2.all { it == null })
            assertEquals(7, fixture.unauthorized2.size)
            assertEquals(6, fixture.unauthorized3.size)
        }
    }
}
