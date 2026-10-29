package io.github.gamesbyian.chunks

import android.content.Context
import android.content.ContentValues
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import android.security.NetworkSecurityPolicy
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections
import kotlinx.coroutines.runBlocking
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.After
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native installed-app Compose acceptance with the *real Android system*
 * font-scale setting applied before this Activity is launched, not a synthetic
 * CompositionLocalProvider or merely doubling individual text pixel sizes.
 *
 * This is a diagnostic, source-controlled smoke gate for actual app navigation
 * and six surviving pixel screenshots, not a running TalkBack audit or final
 * font-art approval. It uses a local synthetic read-only RPC endpoint and
 * never mutates torrents or requires external credentials.
 */
@RunWith(AndroidJUnit4::class)
class SystemFontScaleComposeInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    // Exactly the same deterministic, read-only XML-RPC fixture as the
    // installed native Add/Transfers acceptance test. No external network.
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


    @After fun cleanup() {
        shutdown.set(true)
        server?.close()
        worker?.join(2500)
    }


    private fun savePixels(name: String) {
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("Real Compose screenshot too small", image.width >= 300 && image.height >= 500)
        val pixels = IntArray(10)
        for (index in pixels.indices) {
            val x = ((index+1)*image.width / (pixels.size+1)).coerceAtMost(image.width-1)
            val y = ((index+1)*image.height / (pixels.size+1)).coerceAtMost(image.height-1)
            pixels[index] = image.getPixel(x,y)
        }
        assertTrue("Screenshot contained no opaque Android pixels",pixels.any {
            android.graphics.Color.alpha(it) == 255
        })
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "chunks-compose-200pct-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/ChunksCompose200Pct")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        )) { "MediaStore rejected real Compose 200% screenshot $name" }
        resolver.openOutputStream(uri).use { raw ->
            val stream = requireNotNull(raw) { "No MediaStore output stream" }
            assertTrue("Failed to encode real Compose screen $name",
                image.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        assertEquals("Published real Compose screenshot",1,
            resolver.update(uri,published,null,null))
    }

    @Test
    fun actualChunksActivityKeepsItsCoreScreensAccessibleAtRealSystemFontScale200Percent() {
        val actual = rule.activity.resources.configuration.fontScale
        assertTrue("Android app must run with real system font scale at least 190%, got $actual",
            actual >= 1.9f)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("chunks. Your downloads have places to be.")
            .assertExists()
        // Fresh Chunks shows ConnectionScreen rather than remote tabs until
        // authentication succeeds. Capture that real 200% screen as well.
        savePixels("connection")
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue("Read-only loopback must remain permitted for test",
            policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertTrue("External URLs must retain HTTPS-only network security",
            !policy.isCleartextTrafficPermitted("example.com"))
        val endpoint = startLoopback()
        runBlocking {
            val rpc = RTorrentRemote(RemoteCredentials(endpoint, "", ""))
            assertEquals("0.9.8", rpc.version())
            assertEquals(3, rpc.listTransfers("main").size)
        }
        receivedMethods.clear()
        // At 200% type the phone and offline-tool tiles push the address field
        // below the fold; scroll so the lazy list composes it first.
        rule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Seedbox hostname, ruTorrent URL or RPC address"))
        rule.waitForIdle()
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address")
            .performTextInput(endpoint)
        rule.runOnUiThread {
            val input = rule.activity.getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
            input.hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0)
            rule.activity.currentFocus?.clearFocus()
        }
        rule.waitForIdle()
        // The Connect action is below the fold, especially at 200% type.
        // Only one actual click is sent; scroll for node attachment first.
        rule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Connect to rTorrent"))
        rule.waitForIdle()
        rule.onNodeWithText("Connect to rTorrent")
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("It's all here.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // A green connection assertion alone is insufficient: Android's
        // just-dismissed IME can retain a shortened viewport for several
        // frames, cutting the lower controls in the first screenshot.
        // Require the actual bottom Transfer action to be composed and
        // visible before accepting these real 200% screen pixels.
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("More tools")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("More tools").assertIsDisplayed()
        savePixels("transfers-initial")
        // Occasional tools are collapsed behind More tools; they must still
        // open and show their actions at 200% type.
        rule.onNodeWithText("More tools").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Save inventory-only snapshot").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Hide tools").performScrollTo().performClick()
        rule.waitForIdle()
        // Existing installed-app navigation paths with real Compose UI and
        // read-only localhost RPC records. Visit every main navigation destination and
        // return to Transfers, retaining actual pixels for review.
        for (tab in listOf("Places", "Organize", "Server", "Transfers")) {
            rule.onNodeWithText(tab).assertExists().performScrollTo().performClick()
            rule.waitForIdle()
            savePixels(if (tab == "Transfers") "transfers-return" else tab.lowercase())
        }
        // The combined All environments view must stay usable at 200% type too:
        // header, scope, direction and grouping controls and a real server row.
        rule.onNodeWithText("All environments…").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("All environments").assertIsDisplayed()
        // One lazy list: scroll by semantics so items below the fold get composed first.
        val combinedList = rule.onNode(hasScrollToIndexAction())
        combinedList.performScrollToNode(hasText("Group by environment"))
        rule.onNodeWithText("Group by environment").assertIsDisplayed()
        rule.onNodeWithContentDescription("Sorted by Name, A–Z. Reverse order").assertIsDisplayed()
        combinedList.performScrollToNode(hasText("Big Buck Bunny public demo"))
        rule.onNodeWithText("Big Buck Bunny public demo").assertIsDisplayed()
        combinedList.performScrollToNode(hasText("Select…"))
        rule.onNodeWithText("Select…").assertIsDisplayed()
        rule.waitForIdle()
        savePixels("combined")
        // Open in its list: the signed-in server's own list, searched to that exact hash.
        combinedList.performScrollToNode(hasText("Big Buck Bunny public demo"))
        rule.onNodeWithContentDescription("Open Big Buck Bunny public demo in", substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("1 shown").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Big Buck Bunny public demo").assertExists()
        // Seven distinct captures: unauthenticated Connection, initial and
        // return-to-Transfers, Places / Organize / Server and All environments.
        assertTrue("Test fixture unexpectedly failed: $fixtureProblem",
            fixtureProblem == null)
        assertTrue("Installed Compose must have fetched server transfers read-only",
            receivedMethods.contains("d.multicall2"))
        assertTrue("Test must not mutate remote torrents",
            receivedMethods.all { it in setOf("system.client_version", "d.multicall2", "f.multicall") })
    }
}
