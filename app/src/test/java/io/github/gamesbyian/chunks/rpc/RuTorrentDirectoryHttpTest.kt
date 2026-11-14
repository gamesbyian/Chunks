package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Same-tenant read-only HTTP integration, without a real server or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuTorrentDirectoryHttpTest {
    private inner class Server : AutoCloseable {
        val modernCalls = AtomicInteger()
        val legacyCalls = AtomicInteger()
        val siblingCalls = AtomicInteger()
        var modernStatus = 404
        var modernBody = ""
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val origin get() = "http://127.0.0.1:${server.address.port}"
        val rpc get() = "$origin/tenant/rutorrent2/plugins/httprpc/action.php"
        val expected = "Basic " + Base64.getEncoder()
            .encodeToString("alice:secret".toByteArray(Charsets.UTF_8))
        val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
        private fun send(ex: HttpExchange, status: Int, json: String = "") {
            val bytes = json.toByteArray(Charsets.UTF_8)
            if (status == 401)
                ex.responseHeaders.add("WWW-Authenticate", "Basic realm=\"folder-test\"")
            if (status == 302) ex.responseHeaders.add("Location", "$origin/other-account/")
            if (status == 200) {
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } else {
                ex.sendResponseHeaders(status, -1)
                ex.close()
            }
        }
        init {
            server.createContext("/tenant/rutorrent2/plugins/_getdir/listdir.php") { ex ->
                modernCalls.incrementAndGet()
                methods.add(ex.requestMethod)
                send(ex, modernStatus, modernBody)
            }
            server.createContext("/tenant/rutorrent2/plugins/_getdir/info.php") { ex ->
                legacyCalls.incrementAndGet()
                methods.add(ex.requestMethod)
                if (ex.requestHeaders.getFirst("Authorization") != expected) {
                    send(ex, 401)
                } else {
                    val query = ex.requestURI.rawQuery.orEmpty()
                    val path = if ("basedir=" in query) URLDecoder.decode(
                        query.substringAfter("basedir="), Charsets.UTF_8.name())
                        else "/srv/alice"
                    send(ex, 200, """{"basedir":"$path","dirlist":["..","TV",".","Films"]}""")
                }
            }
            server.createContext("/other-account/") { ex ->
                siblingCalls.incrementAndGet()
                send(ex, 200, """{"path":"/other","directories":["private"]}""")
            }
            server.start()
        }
        fun remote() = RTorrentRemote(RemoteCredentials(rpc, "alice", "secret"))
        override fun close() = server.stop(0)
    }

    @Test fun legacyAuthAndRootReadComeFromExactTenantOnly() = runBlocking {
        Server().use { server ->
            val root = server.remote().browseDirectories("")
            assertTrue(root.toString(), root is RemoteDirectoryLookup.Ready)
            val ready = root as RemoteDirectoryLookup.Ready
            assertEquals("/srv/alice", ready.page.path)
            assertEquals(listOf("Films", "TV"), ready.page.folders)
            assertEquals(1, server.modernCalls.get())
            assertEquals(2, server.legacyCalls.get())
            assertEquals(0, server.siblingCalls.get())
            assertEquals(setOf("GET"), server.methods.toSet())
            val child = server.remote().browseDirectories("/srv/alice/TV")
            assertEquals("/srv/alice/TV", (child as RemoteDirectoryLookup.Ready).page.path)
        }
    }

    @Test fun modernPluginListingDoesNotTouchLegacyOrAnyMutation() = runBlocking {
        Server().use { server ->
            server.modernStatus = 200
            server.modernBody = """{"path":"/srv/alice/","directories":["Movies","TV",".."],"files":["private.bin"]}"""
            val root = server.remote().browseDirectories("")
            assertTrue(root is RemoteDirectoryLookup.Ready)
            assertEquals(listOf("Movies", "TV"), (root as RemoteDirectoryLookup.Ready).page.folders)
            assertEquals(0, server.legacyCalls.get())
            assertEquals(setOf("GET"), server.methods.toSet())
        }
    }

    @Test fun invalidRootOrRedirectNeverFallsBackToAnotherMount() = runBlocking {
        Server().use { server ->
            server.modernStatus = 200
            server.modernBody = """{"path":"/srv/other","directories":["private"]}"""
            val wrong = server.remote().browseDirectories("/srv/alice")
            assertEquals(RemoteDirectoryLookup.Reason.OUTSIDE_SCOPE,
                (wrong as RemoteDirectoryLookup.Unavailable).reason)
            assertEquals(0, server.legacyCalls.get())
            server.modernStatus = 302
            val redirect = server.remote().browseDirectories("")
            assertEquals(RemoteDirectoryLookup.Reason.CONNECTION,
                (redirect as RemoteDirectoryLookup.Unavailable).reason)
            assertEquals(0, server.legacyCalls.get())
            assertEquals(0, server.siblingCalls.get())
        }
    }
}
