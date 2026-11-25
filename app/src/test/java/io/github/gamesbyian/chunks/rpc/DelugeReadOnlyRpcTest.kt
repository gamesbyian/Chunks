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
    private fun active(state: String = "Connected", host: String = daemon) =
        result(5, """["$host","$state","2.1.1"]""")
    private fun finalStatus(state: String = "Connected", host: String = daemon) =
        result(7, """["$host","$state","2.1.1"]""")
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

    @Test fun lateDelugeVectorReadCannotReplaceTheReviewedLayout() = runBlocking {
        var fileReads = 0
        var writes = 0
        val client = DelugeClient(RemoteCredentials(endpoint, "", "fixture-password"), daemon,
            DelugeReadOnlyRpc.Wire { request ->
                val call = org.json.JSONObject(request.body.toString(Charsets.UTF_8))
                val id = call.getInt("id")
                when (val method = call.getString("method")) {
                    "auth.login" -> DelugeReadOnlyRpc.Response(200,
                        """{"id":$id,"error":null,"result":true}""",
                        mapOf("Set-Cookie" to listOf("_session_id=$sid")))
                    "web.get_hosts" -> result(id, """[["$daemon","127.0.0.1",58846,"fixture"]]""")
                    "web.get_host_status" -> result(id, """["$daemon","Connected","2.1"]""")
                    "daemon.get_method_list" -> result(id, """["core.set_torrent_options"]""")
                    "web.connected" -> result(id, "true")
                    "core.get_config_value" -> result(id, """"/data"""")
                    "core.get_torrent_status" -> {
                        fileReads++
                        val secondPath = if (fileReads == 1) "root/other.bin" else "different/other.bin"
                        result(id, """{"files":[{"index":0,"path":"root/selected.bin","size":42},
                            {"index":1,"path":"$secondPath","size":84}],"file_priorities":[4,4]}""")
                    }
                    "core.set_torrent_options" -> { writes++; result(id, "null") }
                    else -> error("Unexpected fixture method $method")
                }
            })
        val reviewed = listOf(
            ClientFile(0, "root/selected.bin", 42L, null, ClientFilePriority.NORMAL),
            ClientFile(1, "root/other.bin", 84L, null, ClientFilePriority.NORMAL))
        val outcome = ClientOperations(client, readbackAttempts = 1)
            .setReviewedFilePriorities(hash, reviewed, mapOf(0 to ClientFilePriority.HIGH))
        assertTrue("$outcome", outcome is ClientOperationOutcome.Refused)
        assertEquals(2, fileReads)
        assertEquals(0, writes)
    }

    @Test fun exactRegisteredDaemonMustBeVerifiedBeforeSessionConnectAndInventoryRead() = runBlocking {
        val script = Scripted(login(), hosts(), status(), connected(), active(), update(), finalStatus())
        val snapshot = client(script).inspect()
        assertEquals(daemon, snapshot.selectedDaemonId)
        assertEquals("2.1.1", snapshot.daemonVersion)
        assertEquals(hash, snapshot.torrents.single().hash)
        assertEquals(100L, snapshot.torrents.single().totalSizeBytes)
        assertEquals(30L, snapshot.torrents.single().completedBytes)
        assertEquals(5L, snapshot.torrents.single().downloadBytesPerSecond)
        assertEquals(listOf("auth.login", "web.get_hosts", "web.get_host_status",
            "web.connect", "web.get_host_status", "web.update_ui", "web.get_host_status"),
            script.calls.map { org.json.JSONObject(it.body.toString(Charsets.UTF_8)).getString("method") })
        assertTrue(script.calls.all { it.endpoint.toString() == endpoint })
        assertNull(script.calls[0].sessionCookie)
        assertTrue(script.calls.drop(1).all { it.sessionCookie == "_session_id=$sid" })
        assertFalse(script.calls.first().toString().contains("only-web-password"))
        val selection = org.json.JSONObject(script.calls[3].body.toString(Charsets.UTF_8))
        assertEquals(daemon, selection.getJSONArray("params").getString(0))
        assertEquals("web.get_host_status",
            org.json.JSONObject(script.calls.last().body.toString(Charsets.UTF_8)).getString("method"))
    }

    @Test fun inventoryIsDiscardedWhenTheSelectedDaemonIsNotBoundAfterTheRead() = runBlocking {
        for (after in listOf(finalStatus("Online"), finalStatus(host = "another-daemon"), result(7, "true"))) {
            val script = Scripted(login(), hosts(), status(), connected(), active(), update(), after)
            try { client(script).inspect(); fail("A read that may have come from another daemon was accepted") }
            catch (expected: IOException) { assertEquals(7, script.calls.size) }
        }
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

    @Test fun unrelatedDaemonMethodsNeverImplyInventoryReadAuthorization() = runBlocking {
        val missingMethod = Scripted(login(), hosts(), status(),
            result(4, """["core.pause_torrent","core.get_session_status"]"""))
        try { client(missingMethod).inspect(); fail("Non-inventory daemon methods accepted") }
        catch (expected: IOException) { assertEquals(4, missingMethod.calls.size) }
    }

    @Test fun failureToConnectOrUnexpectedDaemonStatusNeverReadsInventory() = runBlocking {
        for (witness in listOf(active("Online"), active(host = "another-daemon"), result(5, "true"))) {
            val session = Scripted(login(), hosts(), status(), connected(), witness)
            try { client(session).inspect(); fail("An exact connected daemon was not verified") }
            catch (expected: IOException) { assertEquals(5, session.calls.size) }
        }
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
            val script = Scripted(login(), hosts(), status(), connected(), active(), body)
            try { client(script).inspect(); fail("Malformed Deluge snapshot accepted") }
            catch (expected: IOException) { assertEquals(6, script.calls.size) }
        }
    }

    @Test fun metadataPendingCanHaveUnknownCountersAndNoSavePath() = runBlocking {
        val script = Scripted(login(), hosts(), status(), connected(), active(),
            result(6, """{"torrents":{"$hash":{"name":"Pending","state":"Downloading"}}}"""), finalStatus())
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
