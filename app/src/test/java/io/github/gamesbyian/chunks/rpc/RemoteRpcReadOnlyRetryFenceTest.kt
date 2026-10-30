package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** HTTP 401 never permits replay of an unknown or disguised RPC mutation. */
class RemoteRpcReadOnlyRetryFenceTest {
    private val hash = "a".repeat(40)
    private val challenge = """Basic realm="Fixture""""
    private val ok = "<methodResponse><params><param><value><i8>0</i8></value></param></params></methodResponse>"

    private class Gateway(
        private val answer: (String, Int, com.sun.net.httpserver.HttpExchange) -> Unit
    ) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endpoint get() = "http://127.0.0.1:${server.address.port}/RPC2"

        init {
            server.createContext("/RPC2") { e ->
                val body = e.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(body)?.groupValues?.get(1).orEmpty()
                val count = synchronized(methods) {
                    methods += method
                    methods.count { it == method }
                }
                answer(method, count, e)
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    private fun respond(e: com.sun.net.httpserver.HttpExchange, status: Int, body: String = "") {
        if (status == 401) e.responseHeaders.add("WWW-Authenticate", challenge)
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (status == 401) {
            e.sendResponseHeaders(401, -1)
            e.close()
        } else {
            e.sendResponseHeaders(status, bytes.size.toLong())
            e.responseBody.use { it.write(bytes) }
        }
    }

    private fun testOneDispatch(method: String, params: List<Any>) = runBlocking {
        Gateway { name, _, e ->
            when (name) {
                "system.client_version" -> respond(e, 401)
                method -> respond(e, 401) // simulate server applying the write before sending 401
                else -> error("Unexpected RPC $name")
            }
        }.use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret"))
            try {
                remote.call(method, params)
                fail("A post-apply HTTP 401 is uncertain, never a successful mutation")
            } catch (failure: IOException) {
                assertTrue(failure.message.orEmpty().contains("401"))
            }
            assertEquals(listOf("system.client_version", method), gateway.methods.toList())
        }
    }

    @Test fun unknownMethodCannotInheritReadOnlyChallengeRetry() {
        testOneDispatch("d.pause", listOf(hash))
        testOneDispatch("custom.alter_state", listOf(hash))
    }

    @Test fun embeddedMutationsCannotExploitReadOnlyMulticallRetry() {
        testOneDispatch("system.multicall", listOf(listOf(
            mapOf("methodName" to "d.erase", "params" to listOf(hash))
        )))
        testOneDispatch("d.multicall2", listOf("", "main", "d.hash=", "d.erase="))
        testOneDispatch("f.multicall", listOf(hash, "", "f.path=", "f.priority.set="))
        testOneDispatch("t.multicall", listOf(hash, "", "t.is_enabled=", "t.erase="))
    }

    @Test fun knownImmutableGetterCanStillRetryAnAuthenticationChallenge() = runBlocking {
        Gateway { name, count, e ->
            when (name) {
                "d.name" -> if (count == 1) respond(e, 401) else respond(e, 200, ok)
                else -> error("Unexpected RPC $name")
            }
        }.use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret"))
            assertEquals(0L, remote.call("d.name", listOf(hash)))
            assertEquals(listOf("d.name", "d.name"), gateway.methods.toList())
        }
    }

    @Test fun getterOnlyPagedMulticallRetainsReadOnlyChallengeRetry() = runBlocking {
        Gateway { name, count, e ->
            when (name) {
                "system.multicall" -> if (count == 1) respond(e, 401) else respond(e, 200, ok)
                else -> error("Unexpected RPC $name")
            }
        }.use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret"))
            val getters = listOf(mapOf("methodName" to "d.hash", "params" to listOf(hash)))
            assertEquals(0L, remote.call("system.multicall", listOf(getters)))
            assertEquals(listOf("system.multicall", "system.multicall"), gateway.methods.toList())
        }
    }
}
