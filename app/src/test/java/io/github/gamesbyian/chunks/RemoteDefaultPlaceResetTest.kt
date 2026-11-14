package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A remote Add Place override is a one-off draft, not an rTorrent setting.
 * Presentation test only: it does not claim a verified server destination.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi", fontScale = 2.0f)
class RemoteDefaultPlaceResetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun productionScreenResetRequiresReviewOfTheReportedDefaultAtLargeText() {
        val server = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val methods = java.util.concurrent.ConcurrentLinkedQueue<String>()
        server.createContext("/RPC2") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(request)?.groupValues?.get(1).orEmpty()
            methods.add(method)
            if (method != "directory.default") {
                exchange.sendResponseHeaders(400, -1)
                exchange.close()
                return@createContext
            }
            val response = ("<methodResponse><params><param><value><string>/downloads</string>" +
                "</value></param></params></methodResponse>").toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            val destination = mutableStateOf("/srv/one-off")
            rule.setContent {
                ChunksTheme { Surface {
                    VerifiedAddScreen(remote,
                        magnet = "magnet:?xt=urn:btih:" + "ab".repeat(20),
                        destination = destination.value, onMagnet = {},
                        onDestination = { destination.value = it },
                        onBack = {}, onComplete = {},
                        onMutationStarted = { error("Review must never mutate a server") },
                        onMutationSettled = {})
                } }
            }
            val list = rule.onNode(hasScrollToIndexAction())
            
            
            list.performScrollToNode(hasText("Destination: /srv/one-off (custom parent folder)"))
            rule.onNodeWithText("Destination: /srv/one-off (custom parent folder)").assertExists()
            list.performScrollToNode(hasTestTag(ChunksSemantics.ADD_REMOTE_SAVE_TO))
            rule.onNodeWithTag(ChunksSemantics.ADD_REMOTE_SAVE_TO).assertExists()
            rule.onNodeWithText("Save to: /srv/one-off (this Add only)").assertExists()
            list.performScrollToNode(hasText("Custom server folder (optional)"))
            rule.waitUntil(15_000) {
                rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(
                    "Use rTorrent default save folder for this Add")).fetchSemanticsNodes().isNotEmpty()
            }
            list.performScrollToNode(androidx.compose.ui.test.hasContentDescription(
                "Use rTorrent default save folder for this Add"))
            rule.onNodeWithContentDescription("Use rTorrent default save folder for this Add").performClick()
            rule.runOnIdle { assertEquals("", destination.value) }
            
            
            list.performScrollToNode(hasText(
                "Destination: /downloads (server default; no directory override)"))
            rule.onNodeWithText("Destination: /downloads (server default; no directory override)")
                .assertExists()
            list.performScrollToNode(hasTestTag(ChunksSemantics.ADD_REMOTE_SAVE_TO))
            rule.onNodeWithText("Save to: /downloads (rTorrent default)").assertExists()
            rule.onNodeWithText("About server folders").assertDoesNotExist()
            assertEquals("Preview and Reset must issue only the default read",
                listOf("directory.default"), methods.toList())
        } finally {
            server.stop(0)
        }
    }

    @Test fun resetIsDiscoverableAndOnlyClearsDraftOverride() {
        val draft = mutableStateOf("/server/custom")
        var changes = 0
        rule.setContent {
            ChunksTheme { Surface {
                RemoteDefaultPlaceResetControl(
                    show = draft.value.isNotBlank(),
                    enabled = true,
                    onReset = { changes++; draft.value = "" })
            } }
        }
        rule.onNodeWithContentDescription("Use rTorrent default save folder for this Add")
            .performClick()
        assertEquals(1, changes)
        assertEquals("", draft.value)
        rule.onNodeWithText("Reset to rTorrent default folder").assertDoesNotExist()
    }

    @Test fun unavailableServerDefaultDoesNotOfferAnActionableReset() {
        rule.setContent {
            ChunksTheme { Surface {
                RemoteDefaultPlaceResetControl(show = false, enabled = true, onReset = {
                    error("Cannot reset when rTorrent has not reported a default")
                })
            } }
        }
        rule.onNodeWithText("Reset to rTorrent default folder").assertDoesNotExist()
    }
}
