package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Installed MainActivity supplies its root scope to the Add screen. This
 * screen-level test checks that leaving Add cannot cancel an already dispatched
 * RPC, and that a genuinely verified new job is published while off-screen.
 * Not a claim of real-rTorrent or physical handset acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class RemoteAddNavigationLifetimeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun acceptedRemoteAddCompletesAndPublishesAfterLeavingAddScreen() {
        val hash = "a".repeat(40)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val methods = CopyOnWriteArrayList<String>()
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val registered = java.util.concurrent.atomic.AtomicBoolean(false)
        server.createContext("/RPC2") { exchange ->
            // RTorrentRemote preauthorizes authenticated writes with a HEAD
            // request. A missing response here would be a pre-write refusal,
            // not the deliberate delayed positive Add this test exercises.
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
                return@createContext
            }
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(body)?.groupValues?.get(1)
                ?: error("No XML-RPC method")
            methods += method
            if (method == "load.start") {
                registered.set(true)
                entered.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
            val result = when (method) {
                "d.multicall2" -> "<params><param><value><array><data></data></array></value></param></params>"
                "d.name" -> if (registered.get()) "<params><param><value><string>Original</string></value></param></params>"
                    else "<fault><value><struct><member><name>faultCode</name><value><int>-501</int></value></member>" +
                        "<member><name>faultString</name><value><string>Could not find info-hash.</string></value></member></struct></value></fault>"
                "directory.default", "d.directory" ->
                    "<params><param><value><string>/srv</string></value></param></params>"
                "d.hash" ->
                    "<params><param><value><string>$hash</string></value></param></params>"
                "d.is_multi_file" ->
                    "<params><param><value><int>0</int></value></param></params>"
                "d.state", "d.is_active" ->
                    "<params><param><value><int>1</int></value></param></params>"
                else -> "<params><param><value><int>0</int></value></param></params>"
            }
            val bytes = "<methodResponse>$result</methodResponse>".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val endpoint = "http://127.0.0.1:${server.address.port}/RPC2"
        val account = endpoint + "\u0000owner"
        val client = RTorrentRemote(RemoteCredentials(endpoint, "owner", ""))
        val session = RemoteAddSession(account, "/srv")
        val visible = mutableStateOf(true)
        val source = mutableStateOf("magnet:?xt=urn:btih:$hash&dn=Original")
        var settled = 0
        var newlyVerified = 0
        try {
            rule.setContent {
                val appScope = rememberCoroutineScope()
                ChunksTheme {
                    Surface {
                        if (visible.value) VerifiedAddScreen(
                            remote = client,
                            magnet = source.value,
                            destination = session.destination,
                            onMagnet = { source.value = it },
                            onDestination = { session.destination = it },
                            onBack = { visible.value = false },
                            onComplete = {},
                            onMutationStarted = { true },
                            onMutationSettled = { settled++ },
                            finishInOrderScope = account,
                            session = session,
                            attemptScope = appScope,
                            onVerifiedAdd = { newlyVerified++ }
                        )
                    }
                }
            }
            rule.waitUntil(15_000) {
                runCatching {
                    rule.onNode(hasScrollToIndexAction())
                        .performScrollToNode(hasTestTag(ChunksSemantics.ADD_SUBMIT))
                    rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertIsEnabled()
                    true
                }.getOrDefault(false)
            }
            // The surrounding LazyColumn item can be taller than the viewport.
            // Bringing the item into composition is insufficient for a touch:
            // scroll the actual enabled button into view before clicking once.
            rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT)
                .performScrollTo().assertIsEnabled().performClick()
            // Robolectric Compose runs its coroutine scheduler on the test-controlled UI loop.
            // Blocking on a Java latch here prevents the launched Add from starting.
            rule.waitUntil(15_000) { entered.count == 0L }
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Back to transfers"))
            rule.onNodeWithText("Back to transfers").assertIsEnabled().performClick()
            rule.waitForIdle()
            assertFalse(visible.value)
            release.countDown()
            rule.waitUntil(15_000) {
                settled == 1 && newlyVerified == 1 &&
                    session.coordinator.phase == UniversalAddCoordinator.Phase.VERIFIED
            }
            assertEquals(hash, session.reportedVerifiedHash)
            assertEquals(1, methods.count { it == "load.start" })
            assertFalse(methods.any { it == "load.verbose" || it == "d.start" })
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
}
