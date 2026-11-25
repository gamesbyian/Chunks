package io.github.gamesbyian.chunks

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A2611-02 production wiring: MainActivity provides RemoteSessionOwner through
 * viewModels(), so a real Activity recreation keeps the authenticated session.
 * Loopback fixture only; no real-engine acceptance claim.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class RemoteSessionActivityRecreationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var fixture: com.sun.net.httpserver.HttpServer

    @Before fun startFixture() {
        fixture = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        fixture.createContext("/RPC2") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(body)?.groupValues?.get(1).orEmpty()
            requests += method
            val reply = when (method) {
                "system.client_version" -> "<params><param><value><string>0.9.8</string></value></param></params>"
                "d.multicall2" -> "<params><param><value><array><data></data></array></value></param></params>"
                else -> "<fault><value><struct><member><name>faultCode</name><value><int>1</int></value></member>" +
                    "<member><name>faultString</name><value><string>Unavailable</string></value></member></struct></value></fault>"
            }
            val bytes = "<methodResponse>$reply</methodResponse>".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        fixture.start()
    }

    @After fun stopFixture() = fixture.stop(0)

    private fun owner(): RemoteSessionOwner {
        lateinit var found: RemoteSessionOwner
        rule.activityRule.scenario.onActivity { found = ViewModelProvider(it)[RemoteSessionOwner::class.java] }
        return found
    }

    @Test fun activityRecreationKeepsTheSessionThroughTheActivityViewModel() {
        rule.onNodeWithTag(ChunksSemantics.FOOTER_CONNECTIONS).performClick()
        rule.openConnectionFormIfNeeded(allowConnected = false)
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address")
            .performTextInput("http://127.0.0.1:${fixture.address.port}/RPC2")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(ChunksSemantics.CONNECTION_CONNECT))
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_CONNECT).performClick()
        rule.waitUntil(15_000) { owner().server.value != null }
        val before = owner()
        val remote = assertNotNull(before.server.value).let { before.server.value }
        val connects = requests.count { it == "system.client_version" }

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()

        val after = owner()
        assertSame("the Activity's ViewModelStore keeps the one owner", before, after)
        assertSame("the same live session after recreation", remote, after.server.value)
        assertEquals("no reconnect", connects, requests.count { it == "system.client_version" })
    }
}
