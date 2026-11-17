package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Version and identity receipts, not a simulated end-to-end Chunks connection. */
class TransmissionReadOnlyRpcTest {
    private val endpoint = "https://seedbox.example/account-one/transmission/rpc"
    private val hashA = "a".repeat(40)
    private val hashB = "b".repeat(40)

    private fun response(status: Int, json: String = "", headers: Map<String, List<String>> = emptyMap()) =
        TransmissionReadOnlyRpc.Response(status, headers, json)

    private fun legacySession(version: Int) = response(200,
        """{"result":"success","arguments":{"rpc-version":$version,"version":"4.1.3"}}""")

    private fun legacyTorrents(torrents: String) = response(200,
        """{"result":"success","arguments":{"torrents":[$torrents]}}""")

    private fun modernTorrents(id: Int, torrents: String) = response(200,
        """{"jsonrpc":"2.0","id":$id,"result":{"torrents":[$torrents]}}""")

    private fun legacyRow(hash: String) =
        """{"id":42,"hashString":"$hash","name":"Linux ISO","totalSize":100,"leftUntilDone":25,"downloadDir":"/storage/a","status":4,"rateDownload":10,"rateUpload":2}"""

    private fun modernRow(hash: String, id: Int = 42) =
        """{"id":$id,"hash_string":"$hash","name":"Linux ISO","total_size":100,"left_until_done":25,"download_dir":"/storage/a","status":4,"rate_download":10,"rate_upload":2}"""

    private class Scripted(vararg replies: TransmissionReadOnlyRpc.Response) : TransmissionReadOnlyRpc.Wire {
        val seen = mutableListOf<TransmissionReadOnlyRpc.Request>()
        private val queue = ArrayDeque(replies.toList())
        override suspend fun exchange(request: TransmissionReadOnlyRpc.Request): TransmissionReadOnlyRpc.Response {
            seen += request
            return queue.removeFirst()
        }
    }

    private fun client(script: Scripted, username: String = "alice") =
        TransmissionReadOnlyRpc(RemoteCredentials(endpoint, username, "dont-log-this-password"), script)

    @Test fun legacySessionAndFullInventoryUseOldFieldNamesWithoutAssumingNativeIdsArePortable() = runBlocking {
        val script = Scripted(legacySession(17), legacyTorrents(legacyRow(hashA)))
        val snapshot = client(script).inspect()
        assertEquals(TransmissionReadOnlyRpc.Dialect.LEGACY, snapshot.dialect)
        assertEquals(17, snapshot.rpcVersion)
        assertFalse(snapshot.challengedAuthentication)
        assertEquals(1, snapshot.torrents.size)
        assertEquals(hashA, snapshot.torrents.single().hash)
        assertEquals(75, snapshot.torrents.single().completedBytes)
        assertEquals(42L, snapshot.torrents.single().nativeId)
        assertEquals(10L, snapshot.torrents.single().downloadBytesPerSecond)
        assertTrue(JSONObject(script.seen[1].body.toString(Charsets.UTF_8))
            .getJSONObject("arguments").getJSONArray("fields").toString().contains("hashString"))
        assertEquals("torrent-get", JSONObject(script.seen[1].body.toString(Charsets.UTF_8)).getString("method"))
    }

    @Test fun modernVersionUsesJsonRpc2AndValidatesReturnedRequestId() = runBlocking {
        val script = Scripted(legacySession(19), modernTorrents(2, modernRow(hashA)))
        val snapshot = client(script).inspect()
        assertEquals(TransmissionReadOnlyRpc.Dialect.JSON_RPC_2, snapshot.dialect)
        val request = JSONObject(script.seen[1].body.toString(Charsets.UTF_8))
        assertEquals("2.0", request.getString("jsonrpc"))
        assertEquals("torrent_get", request.getString("method"))
        assertEquals(2, request.getInt("id"))
        assertTrue(request.getJSONObject("params").getJSONArray("fields").toString().contains("hash_string"))
        assertEquals(75L, snapshot.torrents.single().completedBytes)
    }

    @Test fun valid409TokenOnlyRetriesSameExactEndpointForRead() = runBlocking {
        val token = "opaque-session-token"
        val script = Scripted(
            response(409, headers = mapOf("X-Transmission-Session-Id" to listOf(token))),
            legacySession(17),
            legacyTorrents(legacyRow(hashA))
        )
        val snapshot = client(script).inspect()
        assertEquals(1, snapshot.torrents.size)
        assertEquals(listOf(null, token, token), script.seen.map { it.csrfSession })
        assertTrue(script.seen.all { it.endpoint.toString() == endpoint })
        assertTrue(script.seen.none { it.toString().contains(token) })
    }

    @Test fun explicitBasicChallengeIsScopedAndNotExposedInRequestToString() = runBlocking {
        val script = Scripted(
            response(401, headers = mapOf("WWW-Authenticate" to listOf("Basic realm=\"test\""))),
            legacySession(17),
            legacyTorrents(legacyRow(hashA))
        )
        val snapshot = client(script).inspect()
        assertTrue(snapshot.challengedAuthentication)
        assertNull(script.seen[0].authorization)
        assertTrue(script.seen[1].authorization.orEmpty().startsWith("Basic "))
        assertNull(script.seen[2].authorization)
        assertFalse(script.seen[1].toString().contains("dont-log-this-password"))
    }

    @Test fun missingRepeatedOrMalformed409NeverBecomesAWorkingSession() = runBlocking {
        val cases = listOf(
            Scripted(response(409)),
            Scripted(response(409, headers = mapOf("X-Transmission-Session-Id" to listOf("bad\nvalue")))),
            Scripted(
                response(409, headers = mapOf("X-Transmission-Session-Id" to listOf("one"))),
                response(409, headers = mapOf("X-Transmission-Session-Id" to listOf("two")))
            )
        )
        for (script in cases) {
            try { client(script).inspect(); fail("Session challenge accepted incorrectly") }
            catch (expected: IOException) { assertFalse(expected.message.orEmpty().contains("bad\nvalue")) }
            assertTrue(script.seen.size <= 2)
        }
    }

    @Test fun rejectsRedirectAndWrongAccountResponsesWithoutTryingAnotherPath() = runBlocking {
        val script = Scripted(response(302, headers = mapOf("Location" to listOf("https://other.example/"))))
        try { client(script).inspect(); fail("Redirect accepted") }
        catch (expected: IOException) { assertEquals(1, script.seen.size) }
    }

    @Test fun rejectsIncompleteOrAmbiguousInventoryRatherThanProvingAbsence() = runBlocking {
        val bad = listOf(
            legacyTorrents(legacyRow(hashA) + "," + legacyRow(hashA)),
            modernTorrents(999, modernRow(hashA)),
            modernTorrents(2, modernRow("not-a-hash")),
            modernTorrents(2, modernRow(hashA, 5) + "," + modernRow(hashB, 5)),
            response(200, """{"jsonrpc":"2.0","id":2,"error":{"code":5,"message":"denied"}}""")
        )
        for ((index, outcome) in bad.withIndex()) {
            val sessionVersion = if (index == 0) 17 else 19
            val script = Scripted(legacySession(sessionVersion), outcome)
            try { client(script).inspect(); fail("Unsafe inventory accepted at case " + index) }
            catch (expected: IOException) { assertEquals(2, script.seen.size) }
        }
    }

    @Test fun unsafeEndpointAndMalformedVersionAreRejectedWithoutLeakingSecrets() = runBlocking {
        val invalid = listOf(
            "http://public.example/transmission/rpc",
            "https://alice:secret@host.example/transmission/rpc",
            "https://host.example/transmission/rpc?token=secret",
            "https://host.example/a%252fb/transmission/rpc",
            "https://host.example/../transmission/rpc"
        )
        for (url in invalid) {
            try {
                TransmissionReadOnlyRpc(RemoteCredentials(url, "alice", "secret"), Scripted())
                fail("Unsafe Transmission RPC endpoint accepted")
            } catch (expected: IllegalArgumentException) {
                assertFalse(expected.message.orEmpty().contains("secret"))
            }
        }
        val script = Scripted(legacySession(0))
        try { client(script).inspect(); fail("RPC version zero accepted") }
        catch (expected: IOException) { assertEquals(1, script.seen.size) }
    }

    @Test fun identityAndCountsMustBeCompleteAndUnambiguous() = runBlocking {
        val variants = listOf(
            legacyRow(hashA).replace(""""totalSize":100""", """"totalSize":-1"""),
            legacyRow(hashA).replace(""""leftUntilDone":25""", """"leftUntilDone":101"""),
            legacyRow(hashA).replace(""""hashString":"$hashA"""", """"hashString":23"""),
            legacyRow(hashA).replace(""""name":"Linux ISO"""", """"name":null""")
        )
        for (row in variants) {
            val script = Scripted(legacySession(17), legacyTorrents(row))
            try { client(script).inspect(); fail("Inconsistent torrent accepted") }
            catch (expected: IOException) { assertEquals(2, script.seen.size) }
        }
    }
}
