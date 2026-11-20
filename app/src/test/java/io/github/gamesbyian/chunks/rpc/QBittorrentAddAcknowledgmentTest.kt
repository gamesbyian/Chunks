package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.UniversalAddAdmission
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddLifecycle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QBittorrentAddAcknowledgmentTest {
    private val hash = "1a".repeat(20)
    private val request = ClientAddRequest(ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash"),
        "/chosen", startNow = false)

    private inner class Wire(private val addReply: String) : QBittorrentReadOnlyRpc.Wire {
        var adds = 0
        var inventoryReads = 0
        override suspend fun exchange(request: QBittorrentReadOnlyRpc.Request): QBittorrentReadOnlyRpc.Response {
            val text = when (request.url.path.substringAfter("/api/v2/")) {
                "auth/login" -> return QBittorrentReadOnlyRpc.Response(200, "Ok.",
                    mapOf("Set-Cookie" to listOf("SID=fictional-session-000123")))
                "app/version" -> "v5.1.4"
                "app/webapiVersion" -> "2.11.4"
                "app/defaultSavePath" -> "/default"
                "torrents/info" -> {
                    inventoryReads++
                    if (adds == 0) "[]"
                    else """[{"hash":"$hash","name":"Fixture","state":"pausedDL","save_path":"/chosen"}]"""
                }
                "torrents/add" -> {
                    adds++
                    addReply
                }
                else -> error("Unexpected qBittorrent RPC")
            }
            return QBittorrentReadOnlyRpc.Response(200, text)
        }
    }

    private fun added(id: String) =
        """{"success_count":1,"failure_count":0,"pending_count":0,"added_torrent_ids":["$id"]}"""

    @Test fun missingMalformedOrConflictingAcknowledgmentCannotProveNewRegistration() = runBlocking {
        val replies = listOf("", "<html>Gateway response</html>", added("ef".repeat(20)),
            """{"success_count":1}""",
            """{"success_count":0,"failure_count":0,"pending_count":1,"added_torrent_ids":[]}""")
        for (reply in replies) {
            val wire = Wire(reply)
            val client = QBittorrentClient(RemoteCredentials("http://127.0.0.1/", "fixture", "disposable"), wire)
            val coordinator = UniversalAddCoordinator()
            val intent = ProtocolUniversalAddAdapter.intent("exact-qbt", request, "/default")
            val ticket = requireNotNull(UniversalAddAdmission.authorize(coordinator, intent,
                EnvironmentCapabilities.unprobed(intent.target.environment), EnvironmentAvailability.LIVE,
                acquireMutationGate = { true }, submittedAtEpochMillis = 1_000).ticket)
            val result = ProtocolUniversalAddAdapter.submit(coordinator, ticket,
                ClientOperations(client, readbackAttempts = 1, stoppedSettleReads = 0), request) { 2_000 }
            assertTrue(result is ClientOperationOutcome.Uncertain)
            assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, coordinator.phase)
            assertEquals(1, wire.inventoryReads)
            assertEquals(1, wire.adds)
            UniversalAddLifecycle.recheck(coordinator, ProtocolAddObserver(client) { 3_000 })
            assertEquals(UniversalAddCoordinator.Phase.SESSION_CONFIRMED, coordinator.phase)
            assertEquals(2, wire.inventoryReads)
            assertEquals(1, wire.adds)
        }
    }

    @Test fun supportedRepliesStillNeedIndependentExactReadback() = runBlocking {
        for (reply in listOf("Ok.", added(hash.uppercase()))) {
            val wire = Wire(reply)
            val client = QBittorrentClient(RemoteCredentials("http://127.0.0.1/", "fixture", "disposable"), wire)
            val result = ClientOperations(client, readbackAttempts = 1, stoppedSettleReads = 0).add(request)
            assertTrue(result is ClientOperationOutcome.Verified)
            assertEquals(1, wire.adds)
            assertEquals(2, wire.inventoryReads)
        }
    }
}
