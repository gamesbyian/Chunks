package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import org.junit.After
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
                } catch (_: java.io.IOException) {
                    if (shutdown.get()) break
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
        val endpoint = startLoopback()
        rule.onNodeWithText("ruTorrent web URL or RPC address").performTextInput(endpoint)
        rule.onNodeWithText("Connect to rTorrent").performClick()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("It's all here.").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("It's all here.").assertExists()
        save("app-transfers-populated-real-compose")

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
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Inspect files"))
        rule.onAllNodesWithText("Inspect files").onFirst().performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("85 server-reported files").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasScrollAction()).performScrollToNode(
            hasText("85 server-reported files"))
        save("app-files-populated-real-compose")
    }
}
