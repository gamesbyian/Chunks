package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Two authenticated rTorrent sessions serve the SAME infohash. The reviewed
 * phone-copy peer plan belongs to the original session, never the next one. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class ServerToPhoneSessionPlanTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "a".repeat(40)
    private val sockets = mutableListOf<ServerSocket>()
    private val invoked = Collections.synchronizedList(mutableListOf<String>())

    @After fun closeSockets() { sockets.forEach { it.close() } }

    private fun server(peerPort: Int): RTorrentRemote {
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        sockets += socket
        Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                client.use {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                    if (reader.readLine() == null) return@use
                    var size = 0
                    while (true) {
                        val header = reader.readLine() ?: return@use
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true))
                            size = header.substringAfter(":").trim().toInt()
                    }
                    val chars = CharArray(size)
                    var got = 0
                    while (got < size) {
                        val n = reader.read(chars, got, size - got)
                        if (n < 0) return@use
                        got += n
                    }
                    val method = Regex("<methodName>([^<]+)</methodName>")
                        .find(String(chars))?.groupValues?.get(1).orEmpty()
                    invoked += method
                    val answer = when (method) {
                        "d.hash" -> "<value><string>$hash</string></value>"
                        "network.local_address" -> "<value><string>127.0.0.1</string></value>"
                        "network.bind_address" -> "<value><string>0.0.0.0</string></value>"
                        "network.listen.port" -> "<value><i8>$peerPort</i8></value>"
                        "d.is_private" -> "<value><i8>0</i8></value>"
                        "d.is_active" -> "<value><i8>1</i8></value>"
                        else -> error("Unexpected method in read-only fixture: $method")
                    }
                    val bytes = ("<?xml version=\"1.0\"?><methodResponse><params><param>" +
                        answer + "</param></params></methodResponse>").toByteArray()
                    it.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: " +
                            bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return RTorrentRemote(RemoteCredentials(
            "http://127.0.0.1:" + socket.localPort + "/RPC2", "", ""))
    }

    private val sharedTransfer = RemoteTransfer(
        hash, "Same public torrent on two servers", 512L, 512L, true, "/downloads"
    )

    @Test fun changingServerDropsReviewedPhoneCopyPlanEvenForSameHash() {
        val first = server(6890)
        val second = server(6891)
        var selected by mutableStateOf(first)
        val handedOff = mutableListOf<String>()
        rule.setContent {
            ChunksTheme {
                Surface {
                    ServerToPhoneCopyControl(selected, sharedTransfer, stale = false) {
                        handedOff += it
                    }
                }
            }
        }
        rule.onNodeWithText("Copy files to this phone").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("directly from your server at 127.0.0.1:6890", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Review phone Add").assertExists()
        rule.runOnUiThread { selected = second }
        rule.onNodeWithText("Review phone Add").assertDoesNotExist()
        assertTrue("Switching servers must never hand off the previous plan", handedOff.isEmpty())
        rule.onNodeWithText("Copy files to this phone").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("directly from your server at 127.0.0.1:6891", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // A new server requires an independent review. On the actual click,
        // exactly one B peer hint must reach the existing phone Add flow.
        rule.onNodeWithText("Review phone Add").assertExists()
        rule.onNodeWithText("Review phone Add").performClick()
        // The click must consume its review as well as dispatch exactly one
        // magnet: distinguish a missed Compose click from a lost callback.
        rule.onNodeWithText("Review phone Add").assertDoesNotExist()
        rule.runOnIdle {
            assertTrue("Expected one B callback; observed " + handedOff.size +
                ": " + handedOff.joinToString(),
                handedOff.size == 1)
            assertTrue("No old-server peer address may survive the switch",
                handedOff.single().contains("x.pe=127.0.0.1:6891"))
        }
        assertTrue("Only read-only RPCs may be sent", invoked.isNotEmpty() &&
            invoked.all { it in setOf("d.hash", "network.local_address",
                "network.bind_address", "network.listen.port", "d.is_private", "d.is_active") })
    }
}
