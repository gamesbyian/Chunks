package io.github.gamesbyian.chunks

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Universal Add holds ONE source (docs/UX.md, docs/ADD-SOURCE-INTAKE.md): the newest explicit
 * choice wins. Drives the actual MainActivity against a loopback read-only rTorrent fixture
 * and the real Android document picker contract. No Add is submitted and no write RPC may
 * reach the fixture. JVM/Robolectric evidence only, never device or real-peer acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class UniversalAddSourcePrecedenceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private var listening: ServerSocket? = null
    private val methods = CopyOnWriteArrayList<String>()

    @After fun stop() { listening?.close() }

    private fun str(s: String) = "<value><string>$s</string></value>"

    private fun answer(request: String): String {
        val method = Regex("<methodName>([^<]+)</methodName>").find(request)?.groupValues?.get(1).orEmpty()
        methods += method
        val value = when (method) {
            "system.client_version" -> str("0.9.8")
            "directory.default" -> str("/srv")
            "d.multicall2" -> "<value><array><data></data></array></value>"
            else -> return "<?xml version=\"1.0\"?><methodResponse><fault><value><struct>" +
                "<member><name>faultCode</name><value><int>-506</int></value></member>" +
                "<member><name>faultString</name><value><string>not in fixture</string></value></member>" +
                "</struct></value></fault></methodResponse>"
        }
        return "<?xml version=\"1.0\"?><methodResponse><params><param>$value</param></params></methodResponse>"
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
        val body = CharArray(length).also { chars ->
            var read = 0
            while (read < length) read += reader.read(chars, read, length - read)
        }
        val result = answer(String(body)).toByteArray()
        it.getOutputStream().apply {
            write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${result.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray())
            write(result); flush()
        }
    } }

    private fun list(): SemanticsNodeInteraction = rule.onNode(hasScrollToIndexAction())

    private fun waitForText(text: String) = rule.waitUntil(30_000) {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun connect() {
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also { listening = it }
        Thread { while (!socket.isClosed) try { serve(socket.accept()) } catch (_: Exception) {} }
            .apply { isDaemon = true; start() }
        rule.onNodeWithContentDescription("Connections").performClick()
        list().performScrollToNode(hasText("Seedbox hostname, ruTorrent URL or RPC address"))
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address")
            .performTextInput("http://127.0.0.1:${socket.localPort}/RPC2")
        list().performScrollToNode(hasText("Connect to rTorrent"))
        rule.onNodeWithText("Connect to rTorrent").performClick()
        waitForText("Continue to Connections")
        rule.onNodeWithText("Continue to Connections").performClick()
        rule.waitForIdle()
    }

    /** A real v1 single-file metainfo; its infohash is computed by the production reader. */
    private fun b(text: String) = "${text.toByteArray().size}:$text"
    private val document = ("d8:announce" + b("https://tracker.example/announce") + "4:infod6:lengthi5e" +
        "4:name" + b("newest.mkv") + "12:piece lengthi16384e6:pieces20:01234567890123456789ee").toByteArray()

    private fun pickTorrentThroughAndroidPicker() {
        val uri = Uri.parse("content://chunks.test.documents/newest.torrent")
        shadowOf(rule.activity.contentResolver).registerInputStream(uri, document.inputStream())
        list().performScrollToNode(hasText("Open .torrent"))
        rule.onNodeWithText("Open .torrent").performClick()
        rule.waitForIdle()
        val started = shadowOf(rule.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        shadowOf(rule.activity).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(uri))
        waitForText("newest.mkv")
    }

    private fun assertNoWriteRpc() = assertFalse("Choosing sources/targets must not write: $methods",
        methods.any { it.startsWith("load.") || it == "d.start" || it == "d.erase" })

    /**
     * Regression for the #1649 defect class: an older typed magnet must not silently replace
     * a .torrent the person chose afterwards when the Add target changes. Before the fix,
     * re-selecting the server target discarded the file in favour of the stale magnet.
     */
    @Test fun torrentChosenAfterTypedMagnetSurvivesAddTargetSwitches() {
        connect()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_REMOTE).performScrollTo().performClick()
        rule.waitForIdle()
        list().performScrollToNode(hasTestTag(ChunksSemantics.ADD_SOURCE_FIELD))
        rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD)
            .performTextReplacement("magnet:?xt=urn:btih:" + "c".repeat(40) + "&dn=OlderTypedMagnet")
        waitForText("Magnet for: OlderTypedMagnet")

        pickTorrentThroughAndroidPicker()
        // The newest explicit choice is the one source; the older text no longer competes.
        assertTrue(rule.onAllNodesWithText("Magnet for: OlderTypedMagnet")
            .fetchSemanticsNodes().isEmpty())

        // Change target to this device and back. Neither switch may resurrect the old magnet.
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_DEVICE).performScrollTo().performClick()
        rule.waitForIdle()
        waitForText("newest.mkv")
        assertTrue("Phone target must not show the superseded magnet",
            rule.onAllNodesWithText("OlderTypedMagnet", substring = true).fetchSemanticsNodes().isEmpty())

        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_REMOTE).performScrollTo().performClick()
        rule.waitForIdle()
        waitForText("newest.mkv")
        assertTrue("Server target must still review the chosen .torrent, not the old magnet",
            rule.onAllNodesWithText("OlderTypedMagnet", substring = true).fetchSemanticsNodes().isEmpty())
        assertNoWriteRpc()
    }

    /** The same ordering on the device target: its newer .torrent is what the server reviews. */
    @Test fun torrentOpenedOnDeviceAfterTypedMagnetIsWhatTheServerReviews() {
        connect()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_DEVICE).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD).performScrollTo()
            .performTextReplacement("magnet:?xt=urn:btih:" + "e".repeat(40) + "&dn=OlderPhoneMagnet")
        waitForText("OlderPhoneMagnet")
        val uri = Uri.parse("content://chunks.test.documents/phone.torrent")
        shadowOf(rule.activity.contentResolver).registerInputStream(uri, document.inputStream())
        rule.onNodeWithText("Open .torrent").performScrollTo().performClick()
        rule.waitForIdle()
        val started = shadowOf(rule.activity).nextStartedActivityForResult
        shadowOf(rule.activity).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(uri))
        waitForText("newest.mkv")

        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_REMOTE).performScrollTo().performClick()
        rule.waitForIdle()
        waitForText("newest.mkv")
        assertTrue("The server must review the newer .torrent, not the superseded magnet",
            rule.onAllNodesWithText("OlderPhoneMagnet", substring = true).fetchSemanticsNodes().isEmpty())
        assertNoWriteRpc()
    }

    /**
     * When both a typed magnet and a .torrent exist, an explicit `.torrent file` choice is
     * retained across a target round trip, exactly as the Add screen itself retains it
     * when the text is unchanged since that choice.
     */
    @Test fun explicitFileChoiceOverSameTextSurvivesTargetRoundTrip() {
        connect()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_REMOTE).performScrollTo().performClick()
        rule.waitForIdle()
        pickTorrentThroughAndroidPicker()
        // A newer typed magnet becomes the source; the file remains an explicit alternative.
        list().performScrollToNode(hasTestTag(ChunksSemantics.ADD_SOURCE_FIELD))
        rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD)
            .performTextReplacement("magnet:?xt=urn:btih:" + "d".repeat(40) + "&dn=NewerTypedMagnet")
        waitForText("Magnet for: NewerTypedMagnet")
        list().performScrollToNode(hasText(".torrent file"))
        rule.onNodeWithText(".torrent file").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_DEVICE).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_REMOTE).performScrollTo().performClick()
        rule.waitForIdle()
        list().performScrollToNode(hasText(".torrent file"))
        rule.onNodeWithText(".torrent file").assertIsSelected()
        assertNoWriteRpc()
    }
}
