package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JSON-RPC fixtures only; no fixture can claim live daemon credentials or torrent mutations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DelugeReadOnlyRpcTest {
    private val endpoint = "https://seedbox.example/account/deluge/json"
    private val daemon = "host-01"
    private val hash = "a".repeat(40)
    private val sid = "serverSessionIdentifier1234567890"

    private fun result(id: Int, jsonValue: String) =
        DelugeReadOnlyRpc.Response(200, """{"id":$id,"error":null,"result":$jsonValue}""")

    private fun login() = DelugeReadOnlyRpc.Response(
        200, """{"id":1,"error":null,"result":true}""",
        mapOf("Set-Cookie" to listOf("_session_id=$sid; Path=/json; HttpOnly"))
    )
    private fun hosts() = result(2, """[["$daemon","127.0.0.1",58846,"localclient"]]""")
    private fun status(state: String = "Online") = result(3, """["$daemon","$state","2.1.1"]""")
    private fun connected() = result(4, """["core.get_torrents_status","core.add_torrent_magnet"]""")
    private fun update() = result(6, """{"torrents":{"$hash":{"name":"Linux","state":"Downloading","total_size":100,"total_done":30,"download_payload_rate":5,"upload_payload_rate":2,"save_path":"/home/seed"}}}""")

    private class Scripted(vararg responses: DelugeReadOnlyRpc.Response) : DelugeReadOnlyRpc.Wire {
        val calls = mutableListOf<DelugeReadOnlyRpc.Request>()
        private val pending = ArrayDeque(responses.toList())
        override suspend fun exchange(request: DelugeReadOnlyRpc.Request): DelugeReadOnlyRpc.Response {
            calls += request
            return pending.removeFirst()
        }
    }

    private fun client(script: Scripted, host: String = daemon, url: String = endpoint) =
        DelugeReadOnlyRpc(RemoteCredentials(url, "", "only-web-password"), host, script)

    @Test fun exactRegisteredDaemonMustBeVerifiedBeforeSessionConnectAndInventoryRead() = runBlocking {
        val script = Scripted(login(), hosts(), status(), connected(), result(5, "true"), update())
        val snapshot = client(script).inspect()
        assertEquals(daemon, snapshot.selectedDaemonId)
        assertEquals("2.1.1", snapshot.daemonVersion)
        assertEquals(hash, snapshot.torrents.single().hash)
        assertEquals(100L, snapshot.torrents.single().totalSizeBytes)
        assertEquals(30L, snapshot.torrents.single().completedBytes)
        assertEquals(5L, snapshot.torrents.single().downloadBytesPerSecond)
        assertEquals(listOf("auth.login", "web.get_hosts", "web.get_host_status",
            "web.connect", "web.connected", "web.update_ui"),
            script.calls.map { org.json.JSONObject(it.body.toString(Charsets.UTF_8)).getString("method") })
        assertTrue(script.calls.all { it.endpoint.toString() == endpoint })
        assertNull(script.calls[0].sessionCookie)
        assertTrue(script.calls.drop(1).all { it.sessionCookie == "_session_id=$sid" })
        assertFalse(script.calls.first().toString().contains("only-web-password"))
        val selection = org.json.JSONObject(script.calls[3].body.toString(Charsets.UTF_8))
        assertEquals(daemon, selection.getJSONArray("params").getString(0))
        assertEquals("web.update_ui",
            org.json.JSONObject(script.calls.last().body.toString(Charsets.UTF_8)).getString("method"))
    }

    @Test fun refusedLoginOrMissingCookieNeverProceedsToHostList() = runBlocking {
        val responses = listOf(
            result(1, "false"),
            result(1, "true"),
            DelugeReadOnlyRpc.Response(200, """{"id":1,"error":{"code":1},"result":null}"""),
            DelugeReadOnlyRpc.Response(302, "")
        )
        for (reply in responses) {
            val script = Scripted(reply)
            try { client(script).inspect(); fail("Unverified Deluge web login accepted") }
            catch (expected: IOException) { assertEquals(1, script.calls.size) }
        }
    }

    @Test fun missingDuplicateAndOfflineHostsAreRejectedBeforeAnySessionSelection() = runBlocking {
        val missing = Scripted(login(), result(2, """[["other-host","127.0.0.1",58846,"local"]]"""))
        try { client(missing).inspect(); fail("Wrong daemon selected") }
        catch (expected: IOException) { assertEquals(2, missing.calls.size) }
        val duplicate = Scripted(login(),
            result(2, """[["$daemon","127.0.0.1",58846,"a"],["$daemon","127.0.0.1",58847,"b"]]"""))
        try { client(duplicate).inspect(); fail("Ambiguous daemon selected") }
        catch (expected: IOException) { assertEquals(2, duplicate.calls.size) }
        val offline = Scripted(login(), hosts(), status("Offline"))
        try { client(offline).inspect(); fail("Offline daemon selected") }
        catch (expected: IOException) { assertEquals(3, offline.calls.size) }
    }

    @Test fun failureToConnectOrUnexpectedDaemonStatusNeverReadsInventory() = runBlocking {
        val session = Scripted(login(), hosts(), status(), connected(), result(5, "false"))
        try { client(session).inspect(); fail("Disconnected daemon accepted") }
        catch (expected: IOException) { assertEquals(5, session.calls.size) }
        val wrongHost = Scripted(login(), hosts(), result(3, """["another-daemon","Online","2.1"]"""))
        try { client(wrongHost).inspect(); fail("Cross-daemon status accepted") }
        catch (expected: IOException) { assertEquals(3, wrongHost.calls.size) }
    }

    @Test fun duplicateHashMalformedResultAndUnboundedCountersFailClosed() = runBlocking {
        val cases = listOf(
            result(6, """{"torrents":{"not-a-hash":{"name":"A","state":"Down"}}}"""),
            result(6, """{"torrents":{"$hash":{"name":"A","state":"Down","total_size":-1}}}"""),
            result(6, """{"torrents":{"$hash":{"name":"A","state":"Down","total_size":"55"}}}"""),
            result(6, """{"unexpected":"no torrents field"}"""),
            result(7, """{"torrents":{}}""")
        )
        for (body in cases) {
            val script = Scripted(login(), hosts(), status(), connected(), result(5, "true"), body)
            try { client(script).inspect(); fail("Malformed Deluge snapshot accepted") }
            catch (expected: IOException) { assertEquals(6, script.calls.size) }
        }
    }

    @Test fun metadataPendingCanHaveUnknownCountersAndNoSavePath() = runBlocking {
        val script = Scripted(login(), hosts(), status(), connected(), result(5, "true"),
            result(6, """{"torrents":{"$hash":{"name":"Pending","state":"Downloading"}}}"""))
        val row = client(script).inspect().torrents.single()
        assertNull(row.totalSizeBytes)
        assertNull(row.completedBytes)
        assertNull(row.reportedSavePath)
    }

    @Test fun unsafeJsonEndpointsAreRejectedBeforeLogin() {
        val bad = listOf(
            "http://public.example/deluge/json",
            "https://alice:secret@seedbox.example/deluge/json",
            "https://seedbox.example/deluge/json?token=secret",
            "https://seedbox.example/a%252fother/json",
            "https://seedbox.example/deluge/../json",
            "https://seedbox.example/deluge/"
        )
        for (url in bad) {
            try { client(Scripted(), url = url); fail("Unsafe Deluge endpoint accepted") }
            catch (expected: IllegalArgumentException) {
                assertFalse(expected.message.orEmpty().contains("secret"))
                assertFalse(expected.message.orEmpty().contains(url))
            }
        }
        try { client(Scripted(), host = "other/host"); fail("Arbitrary daemon ID accepted") }
        catch (expected: IllegalArgumentException) { }
    }
}
