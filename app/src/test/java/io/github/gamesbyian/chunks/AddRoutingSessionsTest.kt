package io.github.gamesbyian.chunks

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
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

    @Test fun reauthenticatedProfileAndReopenedAddWaitForTheirOwnSession() {
        val probes = AtomicInteger()
        val first = CompletableDeferred("/old/default")
        val replacement = CompletableDeferred<String>()
        val reopened = CompletableDeferred<String>()
        val next = AtomicReference(first)
        fun profile(verifiedAt: Long) = CombinedReadOnlyProtocolProfile(
            id = id,
            backend = RemoteBackendKind.TRANSMISSION,
            label = "Transmission",
            verifiedAtMillis = verifiedAt,
            operations = {
                ClientOperations(TransmissionClient(
                    RemoteCredentials("https://fixture.invalid/transmission/rpc", "user", "fixture"),
                    wire = TransmissionReadOnlyRpc.Wire { request ->
                        assertEquals("session-get", JSONObject(request.body.decodeToString()).getString("method"))
                        val response = next.get()
                        probes.incrementAndGet()
                        TransmissionReadOnlyRpc.Response(200, emptyMap(),
                            JSONObject().put("result", "success").put("arguments",
                                JSONObject().put("version", "4.0.5")
                                    .put("download-dir", response.await())).toString())
                    }
                ))
            },
            inspect = { error("Add routing reads only the client session") }
        )
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
        rule.waitUntil(5_000) { probes.get() == 2 }
        rule.onNodeWithText("Unknown").assertExists()
        replacement.complete("/replacement/default")
        awaitText("/replacement/default")

        rule.runOnIdle { active.value = false }
        rule.onNodeWithText("Unknown").assertExists()
        assertEquals(2, probes.get())
        rule.runOnIdle {
            next.set(reopened)
            active.value = true
        }
        rule.waitUntil(5_000) { probes.get() == 3 }
        rule.onNodeWithText("Unknown").assertExists()
        reopened.complete("/reopened/default")
        awaitText("/reopened/default")
        assertEquals(3, probes.get())
    }
}
