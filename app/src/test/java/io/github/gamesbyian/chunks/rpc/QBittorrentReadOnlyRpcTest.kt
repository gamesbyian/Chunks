package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Wire-level fixtures. Live authentication and shared UI are separate acceptance tiers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QBittorrentReadOnlyRpcTest {
    private val root = "https://seedbox.example/clients/secondary/"
    private val hash = "a".repeat(40)
    private val sid = "safeSessionIdentifier0123456789"

    private fun loginOk() = QBittorrentReadOnlyRpc.Response(
        200, "Ok.", mapOf("Set-Cookie" to listOf("SID=$sid; path=/; HttpOnly"))
    )
    private fun ok(body: String) = QBittorrentReadOnlyRpc.Response(200, body)
    private fun row(hash: String) = """
        {"hash":"$hash","name":"Sample","state":"stalledDL","size":100,
         "total_size":200,"completed":40,"dlspeed":8,"upspeed":4,
         "save_path":"/srv/seedbox"}
    """.trimIndent()

    private class Scripted(vararg responses: QBittorrentReadOnlyRpc.Response) : QBittorrentReadOnlyRpc.Wire {
        val calls = mutableListOf<QBittorrentReadOnlyRpc.Request>()
        private val pending = ArrayDeque(responses.toList())
        override suspend fun exchange(request: QBittorrentReadOnlyRpc.Request): QBittorrentReadOnlyRpc.Response {
            calls += request
            return pending.removeFirst()
        }
    }

    private fun client(script: Scripted, url: String = root) =
        QBittorrentReadOnlyRpc(RemoteCredentials(url, "alice", "top-secret+password"), script)

    @Test fun oneLoginAndCompleteReadOnlyInventoryStayAtOneVerifiedOrigin() = runBlocking {
        val script = Scripted(loginOk(), ok("v5.0.2"), ok("2.9.3"), ok("[" + row(hash) + "]"))
        val snapshot = client(script).inspect()
        assertEquals("v5.0.2", snapshot.applicationVersion)
        assertEquals("2.9.3", snapshot.webApiVersion)
        assertEquals(hash, snapshot.torrents.single().hash)
        assertEquals(100L, snapshot.torrents.single().selectedSizeBytes)
        assertEquals(200L, snapshot.torrents.single().totalSizeBytes)
        assertEquals(40L, snapshot.torrents.single().completedBytes)
        assertEquals("stalledDL", snapshot.torrents.single().state)
        assertEquals(listOf("auth/login", "app/version", "app/webapiVersion", "torrents/info"),
            script.calls.map { it.url.path.substringAfter("/api/v2/") })
        assertEquals(listOf("POST", "GET", "GET", "GET"), script.calls.map { it.method })
        assertNull(script.calls.first().cookie)
        assertEquals(listOf("SID=$sid", "SID=$sid", "SID=$sid"),
            script.calls.drop(1).map { it.cookie })
        assertTrue(script.calls.all { it.url.host == "seedbox.example" &&
            it.url.path.startsWith("/clients/secondary/api/v2/") &&
            it.origin == "https://seedbox.example" })
        assertTrue(script.calls[0].body!!.toString(Charsets.UTF_8).contains("password=top-secret%2Bpassword"))
        assertFalse(script.calls[0].toString().contains("top-secret"))
        assertFalse(script.calls[1].toString().contains(sid))
        assertNull(script.calls.last().url.rawQuery)
    }

    @Test fun modernPortQualifiedCookieIsRetainedAtExactClientWithoutFallback() = runBlocking {
        val login = QBittorrentReadOnlyRpc.Response(200, "Ok.",
            mapOf("Set-Cookie" to listOf("QBT_SID_8080=" + sid + "; Path=/; HttpOnly")))
        val script = Scripted(login, ok("v5.3.0"), ok("2.14"), ok("[" + row(hash) + "]"))
        val inventory = client(script).inspect()
        assertEquals(1, inventory.torrents.size)
        assertEquals(listOf("QBT_SID_8080=" + sid, "QBT_SID_8080=" + sid, "QBT_SID_8080=" + sid),
            script.calls.drop(1).map { it.cookie })
        assertTrue(script.calls.drop(1).all { it.url.host == "seedbox.example" })
    }

    /** Real qBittorrent 4.6.3 issues base64 SIDs (observed "Me4t...+IQEw..."). */
    @Test fun base64SessionIdFromRealQBittorrentIsAccepted() = runBlocking {
        val real = "Me4t0iZ67a+IQEwSgboYi+L6e6Q7/AeZ"
        val script = Scripted(QBittorrentReadOnlyRpc.Response(200, "Ok.",
            mapOf("Set-Cookie" to listOf("SID=$real; HttpOnly; path=/; SameSite=Strict"))),
            ok("v4.6.3"), ok("2.9.3"), ok("[]"))
        assertTrue(client(script).inspect().torrents.isEmpty())
        assertEquals("SID=$real", script.calls[1].cookie)
    }

    /** Real Seedhost qBittorrent (2026-11-18, run 44218290539) answered login with 204 No Content. */
    @Test fun noContentLoginWithOneSessionCookieIsAccepted() = runBlocking {
        val script = Scripted(QBittorrentReadOnlyRpc.Response(204, "",
            mapOf("Set-Cookie" to listOf("SID=$sid; HttpOnly; path=/"))), ok("v5.1.2"), ok("2.11.4"), ok("[]"))
        assertEquals("v5.1.2", client(script).inspect().applicationVersion)
        // 204 without a session cookie still never authorizes a read.
        val noCookie = Scripted(QBittorrentReadOnlyRpc.Response(204, ""))
        assertThrows(IOException::class.java) { runBlocking { client(noCookie).inspect() } }
        assertEquals(1, noCookie.calls.size)
    }

    @Test fun http200CanBeFailedLoginAndNeverAuthorizeRead() = runBlocking {
        val failed = listOf(
            QBittorrentReadOnlyRpc.Response(200, "Fails."),
            QBittorrentReadOnlyRpc.Response(403, ""),
            QBittorrentReadOnlyRpc.Response(200, "Ok."),
            QBittorrentReadOnlyRpc.Response(200, "Ok.", mapOf("Set-Cookie" to listOf("SID=short"))),
            QBittorrentReadOnlyRpc.Response(200, "Ok.", mapOf("Set-Cookie" to listOf("QBT_SID_bad=" + sid))),
            QBittorrentReadOnlyRpc.Response(200, "Ok.", mapOf("Set-Cookie" to listOf("QBT_SID_8080=" + sid, "SID=" + sid))),
            QBittorrentReadOnlyRpc.Response(200, "Ok.", mapOf("Set-Cookie" to listOf(
                "SID=$sid; path=/", "SID=secondSession01234567890; path=/")))
        )
        for (failure in failed) {
            val script = Scripted(failure)
            try { client(script).inspect(); fail("Unverified login accepted") }
            catch (expected: IOException) { assertEquals(1, script.calls.size) }
        }
    }

    @Test fun redirectsAndExpiredSessionsNeverTriggerCrossAccountOrLoginRetry() = runBlocking {
        val redirect = Scripted(QBittorrentReadOnlyRpc.Response(302, ""))
        try { client(redirect).inspect(); fail("Login redirect accepted") }
        catch (expected: IOException) { assertEquals(1, redirect.calls.size) }
        val expired = Scripted(loginOk(), ok("v5"), QBittorrentReadOnlyRpc.Response(401, ""))
        try { client(expired).inspect(); fail("Expired SID accepted") }
        catch (expected: IOException) {
            assertEquals(3, expired.calls.size)
            assertEquals("GET", expired.calls.last().method)
        }
    }

    @Test fun unknownOptionalCountersStayUnknownAndInventoryIsNeverImplicitlyFiltered() = runBlocking {
        val sparse = """{"hash":"$hash","name":"Pending","state":"metaDL","size":0}"""
        val script = Scripted(loginOk(), ok("v5"), ok("2.9"), ok("[$sparse]"))
        val snapshot = client(script).inspect()
        assertNull(snapshot.torrents.single().completedBytes)
        assertNull(snapshot.torrents.single().totalSizeBytes)
        assertNull(snapshot.torrents.single().reportedSavePath)
        assertEquals("torrents/info", script.calls.last().url.path.substringAfter("/api/v2/"))
        assertNull(script.calls.last().url.rawQuery)
    }

    @Test fun duplicatesMalformedRowsAndNonNumericCountersFailClosed() = runBlocking {
        val invalid = listOf(
            "[" + row(hash) + "," + row(hash) + "]",
            """{"torrents":[]}""",
            """[{"hash":"invalid","name":"x","size":1,"state":"stalledDL"}]""",
            """[{"hash":"$hash","name":"x","size":-1,"state":"stalledDL"}]""",
            """[{"hash":"$hash","name":"x","size":"500","state":"stalledDL"}]""",
            """[{"hash":"$hash","name":"x","size":1,"state":null}]"""
        )
        for (body in invalid) {
            val script = Scripted(loginOk(), ok("v5"), ok("2.9"), ok(body))
            try { client(script).inspect(); fail("Unsafe inventory accepted") }
            catch (expected: IOException) { assertEquals(4, script.calls.size) }
        }
    }

    @Test fun dangerousEndpointsAreRejectedBeforeSendingCredentials() {
        val urls = listOf(
            "http://public.example/qbittorrent/",
            "https://alice:secret@seedbox.example/qbittorrent/",
            "https://seedbox.example/qbittorrent/?key=secret",
            "https://seedbox.example/qbittorrent/%2Fwrong/",
            "https://seedbox.example/qbittorrent/%252fother/",
            "https://seedbox.example/qbittorrent/../other/",
            "https://seedbox.example/qbittorrent"
        )
        for (url in urls) {
            try { client(Scripted(), url); fail("Unsafe qBittorrent root accepted") }
            catch (expected: IllegalArgumentException) {
                assertFalse(expected.message.orEmpty().contains("secret"))
                assertFalse(expected.message.orEmpty().contains(url))
            }
        }
    }
}
