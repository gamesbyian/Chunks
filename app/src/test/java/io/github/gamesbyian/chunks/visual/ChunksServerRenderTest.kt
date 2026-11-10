package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.MainActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM render of the real server screens against a loopback read-only
 * XML-RPC fixture with SYNTHETIC transfers (same shape as the API35 atlas).
 * Visual review only; never a server or device acceptance claim.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
open class ChunksServerRenderTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private var server: ServerSocket? = null

    @After fun stop() { server?.close() }

    private fun str(s: String) = "<value><string>" + s.replace("&", "&amp;").replace("<", "&lt;") + "</string></value>"
    private fun num(n: Long) = "<value><i8>$n</i8></value>"
    private fun array(rows: List<String>) = "<value><array><data>" + rows.joinToString("") + "</data></array></value>"

    private fun inventory(view: String, withMetadata: Boolean = false): String {
        val rows = listOf(
            listOf(str("ab".repeat(20)), str("Our Planet S01 (2160p)"), num(38_600_000_000), num(12_400_000_000),
                num(1), str("/home/dev/TV"), num(52_800_000), num(1_400_000), num(410)),
            listOf(str("cd".repeat(20)), str("Creative Assets Collection"), num(8_600_000_000), num(8_600_000_000),
                num(0), str("/home/dev/Public"), num(0), num(0), num(1300)),
            listOf(str("ef".repeat(20)), str("Sci Fi Pack (2024)"), num(31_400_000_000), num(18_200_000_000),
                num(0), str("/home/dev/Movies"), num(0), num(0), num(750)))
        val shown = when (view) { "started" -> rows.take(1); "stopped" -> rows.drop(1); else -> rows }
        return array(shown.map { array(if (withMetadata) it + num(0) else it) })
    }

    private fun answer(request: String): String {
        val method = Regex("<methodName>([^<]+)</methodName>").find(request)?.groupValues?.get(1).orEmpty()
        return when (method) {
            "system.client_version" -> str("0.9.8")
            "d.multicall2" -> inventory(when {
                request.contains("<string>started</string>") -> "started"
                request.contains("<string>stopped</string>") -> "stopped"
                else -> "main"
            }, request.contains("<string>d.is_meta=</string>"))
            "f.multicall" -> array((0 until 6).map { array(listOf(str("Season 1/Episode $it.mkv"), num(1_000_000L + it), num(1))) })
            else -> return "<?xml version=\"1.0\"?><methodResponse><fault><value><struct>" +
                "<member><name>faultCode</name><value><int>-506</int></value></member>" +
                "<member><name>faultString</name><value><string>not in fixture</string></value></member>" +
                "</struct></value></fault></methodResponse>"
        }.let { "<?xml version=\"1.0\"?><methodResponse><params><param>$it</param></params></methodResponse>" }
    }

    private fun serve(socket: Socket) { socket.use {
        val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
        reader.readLine() ?: return
        var length = 0
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(":").trim().toInt()
        }
        val body = CharArray(length).also { chars -> var read = 0; while (read < length) read += reader.read(chars, read, length - read) }
        val result = answer(String(body)).toByteArray()
        it.getOutputStream().apply {
            write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${result.size}\r\nConnection: close\r\n\r\n").toByteArray())
            write(result); flush()
        }
    } }

    protected open val prefix = "server"

    private fun connectToFixture() {
        val listening = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also { server = it }
        Thread { while (!listening.isClosed) try { serve(listening.accept()) } catch (_: Exception) {} }
            .apply { isDaemon = true; start() }
        // Connect explicitly through the phone-first route, with no shortcut
        // into the authenticated server screen.
        rule.onNodeWithContentDescription("Connections").assertExists().performClick()
        // The Connect list composes lazily; at large text the field starts below the fold.
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Seedbox hostname, ruTorrent URL or RPC address"))
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address")
            .performTextInput("http://127.0.0.1:${listening.localPort}/RPC2")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connect to rTorrent"))
        rule.onNodeWithText("Connect to rTorrent").performClick()
        try {
            rule.waitUntil(20_000) { rule.onAllNodesWithText("3 transfers", substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            rule.onRoot().captureRoboImage("build/chunks-visual/$prefix-timeout.png")
            throw timeout
        }
    }

    /**
     * The installed atlas step that fills rTorrent Add: with Options
     * destinations shown on the Add route, the magnet and custom-folder fields
     * stay editable and the Add list keeps most of the screen.
     */
    @Test fun serverAddKeepsItsFieldsEditableBelowDestinations() {
        connectToFixture()
        rule.waitForIdle()
        rule.onNodeWithText("+ Add").performClick()
        // The chooser scrolls vertically at 320dp / 200% text.
        rule.onNodeWithText("On ", substring = true).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Add a transfer").assertExists()
        val addList = rule.onNode(hasScrollToIndexAction())
        addList.performScrollToNode(hasText("Magnet or torrent link"))
        rule.onNodeWithText("Magnet or torrent link").performTextReplacement("magnet:?xt=urn:btih:" + "ab".repeat(20))
        addList.performScrollToNode(hasText("Custom server folder (optional)"))
        rule.onNodeWithText("Custom server folder (optional)").performTextReplacement("/srv/one-off")
        rule.onNode(hasText("/srv/one-off")).assertExists()
        // The same next steps as the installed atlas: review the one-off folder.
        
        
        rule.waitForIdle()
        addList.performScrollToNode(hasText("Destination: /srv/one-off (custom parent folder)"))
        rule.onNodeWithText("Destination: /srv/one-off (custom parent folder)").assertExists()
        // The destination strip must not take over the Add screen.
        val list = addList.getUnclippedBoundsInRoot()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val listHeight = list.bottom - list.top
        val rootHeight = root.bottom - root.top
        org.junit.Assert.assertTrue("Add list height $listHeight vs screen $rootHeight",
            listHeight >= rootHeight * 0.5f)
        rule.onRoot().captureRoboImage("build/chunks-visual/$prefix-add.png")
    }

    /** Every fully visible, enabled text on the connected server list, its cards and Options reads at 4.5:1. */
    @Test fun serverListTextMeetsContrast() {
        connectToFixture()
        val density = rule.activity.resources.displayMetrics.density
        val low = ArrayList<String>()
        fun sweep(tag: String) {
            rule.waitForIdle()
            val (measured, found) = rule.lowContrastText(tag, rule.onRoot().fetchSemanticsNode().size.height, density)
            org.junit.Assert.assertTrue("$tag measured nothing", measured > 3)
            low += found
        }
        sweep("$prefix-top")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Our Planet S01 (2160p)"))
        sweep("$prefix-cards")
        rule.onNodeWithContentDescription("Options").performClick()
        sweep("$prefix-options")
        org.junit.Assert.assertTrue("Text below 4.5:1: $low", low.isEmpty())
    }

    @Test fun serverTransfers() {
        connectToFixture()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/$prefix.png")
        val density = rule.activity.resources.displayMetrics.density
        val small = rule.smallTouchTargets(density).toMutableList()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Our Planet S01 (2160p)"))
        rule.waitForIdle()
        small += rule.smallTouchTargets(density)
        org.junit.Assert.assertTrue("rTorrent touch targets under 48dp: ${small.distinct()}", small.isEmpty())
        rule.onRoot().captureRoboImage("build/chunks-visual/$prefix-cards.png")
        // Batch selection is a local disclosure on the owning server list.
        // Options now owns app settings and exact-account server destinations.
        // This disclosure is above the current transfer in the lazy list.
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Batch & tools"))
        rule.onNodeWithText("Batch & tools").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Select transfers for a batch"))
        rule.onNodeWithText("Select transfers for a batch").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Collection name for selected transfers"))
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/$prefix-batch.png")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Exit batch selection"))
        rule.onNodeWithText("Exit batch selection").performClick()
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Sci Fi Pack (2024)"))
        rule.onRoot().captureRoboImage("build/chunks-visual/$prefix-scrolled.png")

        // The root four-destination footer is the only global navigation bar.
        rule.onNodeWithContentDescription("Options").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Connected server tools").performScrollTo().assertIsDisplayed()
        for (label in listOf("Server folders & destinations", "Server collections",
                "Manage connections and environments", "Create a torrent…",
                "Review ZIP archives…", "Privacy & authentication")) {
            rule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        rule.onRoot().captureRoboImage("build/chunks-visual/$prefix-options.png")

        rule.onNodeWithText("Server folders & destinations").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/$prefix-tab-places.png")
        rule.onNodeWithContentDescription("Options").performClick()

        rule.onNodeWithText("Server collections").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/$prefix-tab-organize.png")
        rule.onNodeWithContentDescription("Options").performClick()

        rule.onNodeWithText("Manage connections and environments").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/$prefix-tab-server.png")
        rule.onNodeWithContentDescription("Options").assertIsDisplayed()
    }
}

/** The same server screens at Android's 200% font scale. */
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 2.0f)
class ChunksServerLargeTextRenderTest : ChunksServerRenderTest() {
    override val prefix = "server-200pct"
}

/** The reviewed overflow case: 320dp at 200% font with wrapped Options and privacy controls. */
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi", fontScale = 2.0f)
class ChunksServerNarrowLargeTextRenderTest : ChunksServerRenderTest() {
    override val prefix = "server-320-200pct"
}
