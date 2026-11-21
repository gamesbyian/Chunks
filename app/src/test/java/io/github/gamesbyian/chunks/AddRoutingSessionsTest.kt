package io.github.gamesbyian.chunks

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.TransmissionClient
import io.github.gamesbyian.chunks.rpc.TransmissionReadOnlyRpc
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AddRoutingSessionsTest {
    @get:Rule val rule = createComposeRule()
    private val id = "a".repeat(64)

    private fun client(
        profileId: String,
        verifiedAt: Long,
        defaultFolder: suspend () -> String
    ) = CombinedReadOnlyProtocolProfile(
        id = profileId,
        backend = RemoteBackendKind.TRANSMISSION,
        label = "Transmission",
        verifiedAtMillis = verifiedAt,
        operations = {
            ClientOperations(TransmissionClient(
                RemoteCredentials("https://fixture.invalid/transmission/rpc", "user", "fixture"),
                wire = TransmissionReadOnlyRpc.Wire { request ->
                    assertEquals("session-get", JSONObject(request.body.decodeToString()).getString("method"))
                    TransmissionReadOnlyRpc.Response(200, emptyMap(),
                        JSONObject().put("result", "success").put("arguments",
                            JSONObject().put("version", "4.0.5")
                                .put("download-dir", defaultFolder())).toString())
                }
            ))
        },
        inspect = { error("Add routing reads only the client session") }
    )

    @Test fun reauthenticatedProfileAndReopenedAddWaitForTheirOwnSession() {
        val probes = AtomicInteger()
        val first = CompletableDeferred("/old/default")
        val replacement = CompletableDeferred<String>()
        val reopened = CompletableDeferred<String>()
        val next = AtomicReference(first)
        fun profile(verifiedAt: Long) = client(id, verifiedAt) {
            val response = next.get()
            probes.incrementAndGet()
            response.await()
        }
        val active = mutableStateOf(true)
        val client = mutableStateOf(profile(1))
        rule.setContent {
            val sessions = rememberAddRoutingSessions(active.value, listOf(client.value))
            BasicText(sessions[id]?.defaultDownloadDirectory ?: "Unknown")
        }
        fun awaitText(text: String) = rule.waitUntil(5_000) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        awaitText("/old/default")

        rule.runOnIdle {
            next.set(replacement)
            client.value = profile(2)
        }
        rule.onNodeWithText("Unknown").assertExists()
        rule.waitUntil(5_000) { probes.get() == 2 }
        replacement.complete("/replacement/default")
        awaitText("/replacement/default")

        rule.runOnIdle { active.value = false }
        rule.onNodeWithText("Unknown").assertExists()
        assertEquals(2, probes.get())
        rule.runOnIdle {
            next.set(reopened)
            active.value = true
        }
        rule.onNodeWithText("Unknown").assertExists()
        rule.waitUntil(5_000) { probes.get() == 3 }
        reopened.complete("/reopened/default")
        awaitText("/reopened/default")
        assertEquals(3, probes.get())
    }

    @Test fun oneUnansweredClientDoesNotDelayAnotherClientsDefault() {
        val slowResponse = CompletableDeferred<String>()
        val slowProbes = AtomicInteger()
        val fastId = "b".repeat(64)
        val profiles = listOf(
            client(id, 1L) { slowProbes.incrementAndGet(); slowResponse.await() },
            client(fastId, 1L) { "/fast/default" }
        )
        rule.setContent {
            val sessions = rememberAddRoutingSessions(enabled = true, profiles = profiles)
            BasicText(sessions[id]?.defaultDownloadDirectory ?: "Waiting for first client")
            BasicText(sessions[fastId]?.defaultDownloadDirectory ?: "Waiting for second client")
        }
        rule.waitUntil(5_000) {
            slowProbes.get() == 1 && rule.onAllNodesWithText("/fast/default").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Waiting for first client").assertExists()
        slowResponse.complete("/slow/default")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("/slow/default").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("/fast/default").assertExists()
    }

    @Test fun aChangedRTorrentSessionNeedsItsOwnValidDefault() {
        FakeRTorrent(directoryDefault = "/rTorrent/ready").use { first ->
            FakeRTorrent(directoryDefault = "/rTorrent//unusable").use { second ->
                val remote = mutableStateOf(RTorrentRemote(RemoteCredentials(first.endpoint, "", "")))
                rule.setContent {
                    BasicText(rememberRTorrentRoutingDefault(true, remote.value) ?: "Default unavailable")
                }
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithText("/rTorrent/ready").fetchSemanticsNodes().isNotEmpty()
                }
                val replacement = RTorrentRemote(RemoteCredentials(second.endpoint, "", ""))
                rule.runOnIdle { remote.value = replacement }
                // Synchronize the replacement with the rendered UI before
                // polling the independent fake-server request record.
                rule.onNodeWithText("Default unavailable").assertExists()
                rule.waitUntil(5_000) { second.methods.contains("directory.default") }
                rule.onNodeWithText("/rTorrent/ready").assertDoesNotExist()
            }
        }
    }
}
