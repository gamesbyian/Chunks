package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Copy files to this phone" against a loopback XML-RPC fake: reads only, then hands off. */
@RunWith(AndroidJUnit4::class)
class ServerToPhoneCopyControlInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "B63EAA9720EE797667EF58EA35EF7D81AB5D1C16"
    private val methods: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile private var isPrivate = 0
    private var server: ServerSocket? = null

    @After fun stop() { server?.close() }

    private fun answer(method: String): String = when (method) {
        "network.local_address" -> "<value><string></string></value>"
        "network.listen.port" -> "<value><i8>6890</i8></value>"
        "d.is_private" -> "<value><i8>$isPrivate</i8></value>"
        "d.is_active" -> "<value><i8>1</i8></value>"
        else -> error("Unexpected command from a read-only check: $method")
    }

    private fun startLoopback(): String {
        val listening = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also { server = it }
        Thread {
            while (!listening.isClosed) {
                val socket = try { listening.accept() } catch (_: Exception) { break }
                socket.use {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                    reader.readLine() ?: return@use
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: return@use
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(":").trim().toInt()
                    }
                    val body = CharArray(length).also { chars ->
                        var read = 0
                        while (read < length) read += reader.read(chars, read, length - read).coerceAtLeast(0)
                    }
                    val method = Regex("<methodName>([^<]+)</methodName>").find(String(body))?.groupValues?.get(1).orEmpty()
                    methods += method
                    val xml = ("<?xml version=\"1.0\"?><methodResponse><params><param>" + answer(method) +
                        "</param></params></methodResponse>").toByteArray()
                    it.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${xml.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray())
                        write(xml); flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return "http://127.0.0.1:${listening.localPort}/RPC2"
    }

    private fun show(transfer: RemoteTransfer, onReview: (String) -> Unit = {}) {
        val remote = RTorrentRemote(RemoteCredentials(startLoopback(), "", ""))
        rule.setContent {
            MaterialTheme { Column { ServerToPhoneCopyControl(remote, transfer, stale = false, onReviewPhoneAdd = onReview) } }
        }
    }

    private fun complete() = RemoteTransfer(hash, "Big Buck Bunny", 441_396_871, 441_396_871, true, "/srv/dl")

    @Test fun completedPublicTransferHandsTheServerPeerMagnetToThePhoneReview() {
        val reviewed = ArrayList<String>()
        show(complete()) { reviewed += it }
        rule.onNodeWithText("Copy files to this phone").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("directly from your server at 127.0.0.1:6890", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("The server transfer stays exactly as it is", substring = true).assertExists()
        assertTrue("Reviewing nothing yet", reviewed.isEmpty())
        rule.onNodeWithText("Review phone Add").performClick()
        assertEquals(listOf("magnet:?xt=urn:btih:${hash.lowercase()}&dn=Big%20Buck%20Bunny&x.pe=127.0.0.1:6890"),
            reviewed)
        assertTrue("Only read-only commands: $methods", methods.toSet() ==
            setOf("network.local_address", "network.listen.port", "d.is_private", "d.is_active"))
    }

    @Test fun privateTransferIsRefusedWithAReasonAndNoHandoff() {
        isPrivate = 1
        val reviewed = ArrayList<String>()
        show(complete()) { reviewed += it }
        rule.onNodeWithText("Copy files to this phone").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("This is a private torrent", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Review phone Add").assertDoesNotExist()
        assertTrue(reviewed.isEmpty())
    }

    @Test fun incompleteTransferOffersNoCopy() {
        show(complete().copy(completedBytes = 1_000))
        rule.onNodeWithText("Copy files to this phone").assertDoesNotExist()
        assertTrue(methods.isEmpty())
    }
}
