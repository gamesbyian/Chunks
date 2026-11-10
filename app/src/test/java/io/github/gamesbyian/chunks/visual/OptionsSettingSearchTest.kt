package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.gamesbyian.chunks.MainActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Options search on the real authenticated server screen finds settings that
 * live inside existing screens and opens only those screens. Loopback
 * read-only fixture with one synthetic transfer; never a server acceptance claim.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-xxhdpi", fontScale = 2.0f)
class OptionsSettingSearchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private var server: ServerSocket? = null
    private val methods = ConcurrentLinkedQueue<String>()

    @After fun stop() { server?.close() }

    private fun str(s: String) = "<value><string>$s</string></value>"
    private fun num(n: Long) = "<value><i8>$n</i8></value>"
    private fun array(rows: List<String>) = "<value><array><data>" + rows.joinToString("") + "</data></array></value>"

    private fun answer(request: String): String {
        val method = Regex("<methodName>([^<]+)</methodName>").find(request)?.groupValues?.get(1).orEmpty()
        methods.add(method)
        val meta = request.contains("<string>d.is_meta=</string>")
        val row = listOf(str("ab".repeat(20)), str("Fixture transfer"), num(1_000), num(500),
            num(0), str("/home/dev"), num(0), num(0), num(10)) + if (meta) listOf(num(0)) else emptyList()
        return when (method) {
            "system.client_version" -> str("0.9.8")
            "d.multicall2" -> array(listOf(array(row)))
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

    private fun connectAndOpenOptions() {
        val listening = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also { server = it }
        Thread { while (!listening.isClosed) try { serve(listening.accept()) } catch (_: Exception) {} }
            .apply { isDaemon = true; start() }
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Seedbox hostname, ruTorrent URL or RPC address"))
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address")
            .performTextInput("http://127.0.0.1:${listening.localPort}/RPC2")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connect to rTorrent"))
        rule.onNodeWithText("Connect to rTorrent").performClick()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("Continue to Connections").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Continue to Connections").performClick()
        // Options is app-wide and independent from a legacy server-only list.
        rule.onNodeWithContentDescription("Options").performClick()
        rule.waitForIdle()
    }

    private val search get() = rule.onNode(hasSetTextAction() and hasText("Search options"))

    private val mutation = Regex("(^load\\.|\\.(start|stop|erase|close|open|pause|resume|check_hash)$|\\.set$|\\.set\\.)")
    private fun readOnly() = assertTrue("search must never mutate: $methods", methods.none { mutation.containsMatchIn(it) })

    @Test fun seedingSearchFindsSeedingRulesAndOpensIt() {
        connectAndOpenOptions()
        search.performTextInput("seeding ratio")
        rule.waitForIdle()
        rule.onNodeWithText("No matching option. Clear search to see all settings and tools.").assertDoesNotExist()
        rule.onNodeWithText("Ratio and seeding-time rules · opens Seeding rules").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("What these rules do today").performScrollTo()
        rule.onNode(hasSetTextAction() and hasText("Search options")).assertDoesNotExist()
        readOnly()
    }

    @Test fun speedAndPrivacyWordsPointToWhereTheyLive() {
        connectAndOpenOptions()
        search.performTextInput("speed")
        rule.waitForIdle()
        rule.onNodeWithText("Download and upload limits for this device · in Options")
            .performScrollTo()
        search.performTextReplacement("lock")
        rule.waitForIdle()
        rule.onNodeWithText("Lock and hide transfer names · in Options")
            .performScrollTo().performClick()
        rule.waitForIdle()
        // Tapping only clears the search; the panel stays open with Privacy at its end.
        rule.onNode(hasSetTextAction() and hasText("Search options")).assertExists()
        rule.onNodeWithText("Privacy & authentication").performScrollTo()
        readOnly()
    }

    @Test fun unknownWordsStillSayNothingMatches() {
        connectAndOpenOptions()
        search.performTextInput("paywall")
        rule.waitForIdle()
        rule.onNodeWithText("No matching option. Clear search to see all settings and tools.").performScrollTo()
    }
}
