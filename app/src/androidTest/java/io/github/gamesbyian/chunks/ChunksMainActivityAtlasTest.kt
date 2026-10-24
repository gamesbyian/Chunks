package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.security.NetworkSecurityPolicy
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed MainActivity against a deterministic localhost XML-RPC service.
 * Screenshots are actual Android app pixels with SYNTHETIC RPC records.
 * No Seedhost credentials and no real torrents or mutations.
 */
@RunWith(AndroidJUnit4::class)
class ChunksMainActivityAtlasTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private var server: ServerSocket? = null
    private var worker: Thread? = null
    private val shutdown = AtomicBoolean(false)
    private val receivedMethods = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var fixtureProblem: String? = null

    private fun str(s: String) = "<value><string>" + s.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;") + "</string></value>"
    private fun num(n: Long) = "<value><i8>$n</i8></value>"
    private fun array(rows: List<String>) =
        "<value><array><data>" + rows.joinToString("") + "</data></array></value>"
    private fun row(fields: List<String>) = array(fields)

    private fun inventory(view: String): String {
        val rows = listOf(
            listOf(str("ab".repeat(20)), str("Public.Example.Long.Named.Archive.S01E01.mkv"),
                num(1073741824), num(524288000), num(1), str("/home/dev/TV"),
                num(2048), num(512), num(1000)),
            listOf(str("cd".repeat(20)), str("Big Buck Bunny public demo"),
                num(280000000), num(280000000), num(0), str("/home/dev/Public"),
                num(0), num(0), num(1300)),
            listOf(str("ef".repeat(20)), str("A deliberately very long test filename " +
                "with symbols (2026) and extended descriptor.mkv"),
                num(900000000), num(300000000), num(1), str("/home/dev/TV"),
                num(4096), num(1024), num(750))
        )
        val shown = when(view) {
            "started" -> listOf(rows[0], rows[2])
            "stopped" -> listOf(rows[1])
            else -> rows
        }
        return array(shown.map(::row))
    }

    private fun files(): String = array((0 until 85).map { i ->
        row(listOf(
            str("Season" + (i / 10) + "/Episode" + i + "-long-public-example-name.mkv"),
            num(1000000L + i),
            num(if (i % 9 == 0) 0 else 1)
        ))
    })

    private fun response(request: String): ByteArray {
        val method = Regex("<methodName>([^<]+)</methodName>")
            .find(request)?.groupValues?.get(1).orEmpty()
        receivedMethods.add(method)
        val answer = when(method) {
            "system.client_version" -> str("0.9.8")
            "d.multicall2" -> {
                val view = when {
                    request.contains("<string>started</string>") -> "started"
                    request.contains("<string>stopped</string>") -> "stopped"
                    else -> "main"
                }
                inventory(view)
            }
            "f.multicall" -> files()
            else -> error("Unexpected command in read-only installed-app atlas: $method")
        }
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<methodResponse><params><param>" + answer +
            "</param></params></methodResponse>").toByteArray(Charsets.UTF_8)
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 12000
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            require(requestLine.startsWith("POST /RPC2 ")) { "Unexpected endpoint" }
            var length = -1
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    length = line.substringAfter(":").trim().toInt()
                }
            }
            require(length in 0..1048576) { "Invalid test RPC length" }
            val chars = CharArray(length)
            var total = 0
            while (total < length) {
                val read = reader.read(chars, total, length - total)
                if (read < 0) return
                total += read
            }
            val result = response(String(chars))
            val header = ("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=utf-8\r\n" +
                "Content-Length: " + result.size + "\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.US_ASCII)
            socket.getOutputStream().apply {
                write(header)
                write(result)
                flush()
            }
        }
    }

    private fun startLoopback(): String {
        val listening = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = listening
        worker = Thread {
            while (!shutdown.get()) {
                try {
                    serve(listening.accept())
                } catch (failure: Throwable) {
                    if (shutdown.get()) break
                    fixtureProblem = failure::class.java.simpleName +
                        ":" + (failure.message ?: "").take(120)
                }
            }
        }.apply { isDaemon = true; start() }
        return "http://127.0.0.1:" + listening.localPort + "/RPC2"
    }

    private fun save(name: String) {
        val image = rule.onNode(isRoot()).captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("Save actual installed Android screenshot", uri)
        resolver.openOutputStream(uri!!).use {
            val output = requireNotNull(it)
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        assertTrue(resolver.update(uri, published, null, null) == 1)
    }

    @After fun cleanup() {
        shutdown.set(true)
        server?.close()
        worker?.join(2500)
    }

    @Test fun installedAppNavigatesPopulatedReadOnlyRpcScreens() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue("Literal loopback must be permitted for controlled Android QA",
            policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertTrue("Localhost may be used as a safe local-only target",
            policy.isCleartextTrafficPermitted("localhost"))
        assertTrue("Normal external domains must remain HTTPS-only",
            !policy.isCleartextTrafficPermitted("example.com"))
        val endpoint = startLoopback()
        // First prove the exact adapter/fixture works outside Compose navigation.
        // A failure here identifies transport or XML-RPC shape rather than UI state.
        runBlocking {
            val rpc = RTorrentRemote(RemoteCredentials(endpoint, "", ""))
            assertEquals("0.9.8", rpc.version())
            assertEquals(3, rpc.listTransfers("main").size)
            assertEquals(85, rpc.listFiles("ab".repeat(20)).size)
        }
        receivedMethods.clear() // Record the real installed-app calls separately.
        rule.onNodeWithText("ruTorrent web URL or RPC address").performTextInput(endpoint)
        rule.onNodeWithText("Connect to rTorrent").performClick()
        try {
            rule.waitUntil(20_000) {
                rule.onAllNodesWithText("It's all here.").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            // Genuine diagnostic from installed app UI. This fixture uses no
            // secret values; never print HTTP authorization/request bodies.
            save("app-connection-diagnostic-real-compose")
            val ui = rule.onRoot(useUnmergedTree = true).printToString().take(1700)
            throw AssertionError(
                "Loopback RPC methods=" + receivedMethods.toList().joinToString(",") +
                    "; fixture=" + (fixtureProblem ?: "none") +
                    "; actual native UI=" + ui, failure)
        }
        rule.onNodeWithText("It's all here.").assertExists()
        save("app-transfers-populated-real-compose")

        // Identify the episodic fixture by its *original* server-reported name.
        // The queue is name-sorted, so the first unfiltered card is not this item.
        // Keeping this search after Save also proves the original remains searchable.
        rule.onNodeWithText("Search names or Places").performTextInput("Public.Example")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("1 shown"))
        rule.onNodeWithText("1 shown").assertExists()
        // An actual installed-app queue action, not only an isolated editor component.
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Change list name"))
        rule.onAllNodesWithText("Change list name").onFirst().performClick()
        rule.onNodeWithText("Extracted show title, season and episode from filename").assertExists()
        rule.onNodeWithText("Public Example Long Named Archive S01E01").performClick()
        save("app-alias-review-populated-real-compose")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Save name"))
        rule.onNodeWithText("Save name").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(
            hasText("Original torrent: Public.Example.Long.Named.Archive.S01E01.mkv"))
        rule.onNodeWithText("Original torrent: Public.Example.Long.Named.Archive.S01E01.mkv",
            substring = true).assertExists()
        save("app-alias-saved-populated-real-compose")

        rule.onNodeWithText("Places").performClick()
        rule.onNodeWithText("Everything has a place.").assertExists()
        save("app-places-populated-real-compose")

        rule.onNodeWithText("Organize").performClick()
        rule.onNodeWithText("Chunks has ideas.").assertExists()
        save("app-organize-populated-real-compose")

        rule.onNodeWithText("Server").performClick()
        rule.onNodeWithText("Your rTorrent.", substring = true).assertExists()
        save("app-server-populated-real-compose")

        rule.onNodeWithText("Transfers").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Inspect files"))
        rule.onAllNodesWithText("Inspect files").onFirst().performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("85 server-reported files").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(
            hasText("85 server-reported files"))
        save("app-files-populated-real-compose")
        val invoked = receivedMethods.toList()
        assertTrue("Installed client must request server version", invoked.contains("system.client_version"))
        assertTrue("Installed client must enumerate the actual transfer list", invoked.contains("d.multicall2"))
        assertTrue("Installed client must inspect the selected torrent files", invoked.contains("f.multicall"))
        assertTrue("Read-only atlas must never exercise torrent mutations",
            invoked.all { it in setOf("system.client_version", "d.multicall2", "f.multicall") })
    }
}
