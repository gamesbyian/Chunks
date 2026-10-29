package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
class ChunksServerRenderTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private var server: ServerSocket? = null

    @After fun stop() { server?.close() }

    private fun str(s: String) = "<value><string>" + s.replace("&", "&amp;").replace("<", "&lt;") + "</string></value>"
    private fun num(n: Long) = "<value><i8>$n</i8></value>"
    private fun array(rows: List<String>) = "<value><array><data>" + rows.joinToString("") + "</data></array></value>"

    private fun inventory(view: String): String {
        val rows = listOf(
            listOf(str("ab".repeat(20)), str("Our Planet S01 (2160p)"), num(38_600_000_000), num(12_400_000_000),
                num(1), str("/home/dev/TV"), num(52_800_000), num(1_400_000), num(410)),
            listOf(str("cd".repeat(20)), str("Creative Assets Collection"), num(8_600_000_000), num(8_600_000_000),
                num(0), str("/home/dev/Public"), num(0), num(0), num(1300)),
            listOf(str("ef".repeat(20)), str("Sci Fi Pack (2024)"), num(31_400_000_000), num(18_200_000_000),
                num(0), str("/home/dev/Movies"), num(0), num(0), num(750)))
        val shown = when (view) { "started" -> rows.take(1); "stopped" -> rows.drop(1); else -> rows }
        return array(shown.map(::array))
    }

    private fun answer(request: String): String {
        val method = Regex("<methodName>([^<]+)</methodName>").find(request)?.groupValues?.get(1).orEmpty()
        return when (method) {
            "system.client_version" -> str("0.9.8")
            "d.multicall2" -> inventory(when {
                request.contains("<string>started</string>") -> "started"
                request.contains("<string>stopped</string>") -> "stopped"
                else -> "main"
            })
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

    @Test fun serverTransfers() {
        val listening = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also { server = it }
        Thread { while (!listening.isClosed) try { serve(listening.accept()) } catch (_: Exception) {} }
            .apply { isDaemon = true; start() }
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address")
            .performTextInput("http://127.0.0.1:${listening.localPort}/RPC2")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connect to rTorrent"))
        rule.onNodeWithText("Connect to rTorrent").performClick()
        try {
            rule.waitUntil(20_000) { rule.onAllNodesWithText("3 transfers", substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            rule.onRoot().captureRoboImage("build/chunks-visual/server-timeout.png")
            throw timeout
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/server.png")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Our Planet S01 (2160p)"))
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/server-cards.png")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Sci Fi Pack (2024)"))
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/server-scrolled.png")
    }
}
