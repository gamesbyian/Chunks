package io.github.gamesbyian.chunks

import android.content.ContentValues
import androidx.compose.ui.test.onNodeWithTag
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.media.ChunksMedia
import io.github.gamesbyian.chunks.media.MediaPrivacySettings
import io.github.gamesbyian.chunks.media.MediaTransferKey
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed MainActivity media journey against a deterministic, read-only localhost XML-RPC
 * service with SYNTHETIC transfers: a real authenticated rTorrent list row shows category art,
 * then the user's own image (validated, processed and cached by the production pipeline; no
 * network), then category art again after Options → Artwork & info turns artwork off. Proves
 * actual pixels in the installed app and that media actions issue no RPC mutation. Not provider,
 * physical-device or TalkBack proof.
 */
@RunWith(AndroidJUnit4::class)
class InstalledMediaArtworkInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private var server: ServerSocket? = null
    private val shutdown = AtomicBoolean(false)
    private val receivedMethods = Collections.synchronizedList(mutableListOf<String>())
    private val media get() = ChunksMedia.get(InstrumentationRegistry.getInstrumentation().targetContext)
    private val episode = "Public.Example.Long.Named.Archive.S01E01.mkv"
    private val format = "Name suggests .mkv; actual file type not verified"

    private fun str(s: String) = "<value><string>" + s.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;") + "</string></value>"
    private fun num(n: Long) = "<value><i8>$n</i8></value>"
    private fun array(rows: List<String>) = "<value><array><data>" + rows.joinToString("") + "</data></array></value>"

    private fun inventory(withMetadata: Boolean): String = array(listOf(
        listOf(str("ab".repeat(20)), str(episode), num(1073741824), num(524288000), num(1),
            str("/home/dev/TV"), num(2048), num(512), num(1000)),
        listOf(str("cd".repeat(20)), str("Big Buck Bunny public demo"), num(280000000), num(280000000),
            num(0), str("/home/dev/Public"), num(0), num(0), num(1300))
    ).map { array(if (withMetadata) it + num(0) else it) })

    private fun response(request: String): ByteArray {
        val method = Regex("<methodName>([^<]+)</methodName>").find(request)?.groupValues?.get(1).orEmpty()
        receivedMethods.add(method)
        val answer = when (method) {
            "system.client_version" -> str("0.9.8")
            "d.multicall2" -> inventory(request.contains("<string>d.is_meta=</string>"))
            "f.multicall" -> array(emptyList())
            "directory.default" -> str("/downloads")
            else -> error("Unexpected command in read-only media journey: $method")
        }
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><methodResponse><params><param>" + answer +
            "</param></params></methodResponse>").toByteArray(Charsets.UTF_8)
    }

    private fun serve(client: Socket): Unit = client.use { socket ->
        socket.soTimeout = 12000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        require(requestLine.startsWith("POST /RPC2 ")) { "Unexpected endpoint" }
        var length = -1
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(":").trim().toInt()
        }
        require(length in 0..1048576)
        val chars = CharArray(length); var total = 0
        while (total < length) { val n = reader.read(chars, total, length - total); if (n < 0) return; total += n }
        val result = response(String(chars))
        socket.getOutputStream().apply {
            write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=utf-8\r\nContent-Length: " + result.size +
                "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            write(result); flush()
        }
    }

    private fun startLoopback(): String {
        val listening = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = listening
        Thread {
            while (!shutdown.get()) {
                try { serve(listening.accept()) } catch (_: Throwable) { if (shutdown.get()) break }
            }
        }.apply { isDaemon = true; start() }
        return "http://127.0.0.1:" + listening.localPort + "/RPC2"
    }

    private fun hideKeyboard() {
        rule.runOnUiThread {
            val input = rule.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            input.hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0)
            rule.activity.currentFocus?.clearFocus()
        }
        rule.waitForIdle()
    }

    /** A single semantic click on a lazily composed button; retries only failed lookups (pre-dispatch). */
    private fun clickLazyButton(label: String) {
        val target = hasClickAction() and (hasText(label) or hasAnyDescendant(hasText(label)))
        for (attempt in 1..5) {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
            rule.waitForIdle()
            if (rule.onAllNodes(target, useUnmergedTree = true).fetchSemanticsNodes().size != 1) continue
            try { rule.onNode(target, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick); return }
            catch (_: AssertionError) { }
        }
        throw AssertionError("$label was never composed and clicked")
    }

    private fun waitForDescription(description: String, timeoutMs: Long = 20_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodes(hasContentDescription(description), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun save(name: String) {
        val image = rule.onNode(isRoot()).captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A distinctive opaque image the user might pick: solid orange with a white centre square. */
    private fun userImage(): ByteArray {
        val b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(0xFFFF7A00.toInt())
        c.drawRect(250f, 250f, 350f, 350f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Connecting remembers the login by default; later tests expect a fresh, local-first launch. */
    private fun forgetLogins() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RememberedRemoteLogin(context).clear()
        RememberedRemoteProfileRoster(context).clearAll()
    }

    @Before fun freshMediaState() {
        forgetLogins()
        runBlocking { media.clearAllMediaData().join() }
        media.updateSettings(MediaPrivacySettings(onlineLookupConsent = false))
    }

    @After fun cleanup() {
        shutdown.set(true)
        server?.close()
        forgetLogins()
        runBlocking { media.clearAllMediaData().join() }
        media.updateSettings(MediaPrivacySettings(onlineLookupConsent = false))
    }

    @Test fun installedRemoteRowShowsUserArtworkAndOptionsSettingTurnsItOff() {
        val endpoint = startLoopback()
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Seedbox hostname, ruTorrent URL or RPC address"))
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").performTextInput(endpoint)
        hideKeyboard()
        clickLazyButton("Connect to rTorrent")
        // Verified Connect acknowledges and stays on Connections (#1515); the saved
        // profile then opens the one canonical Transfers browser.
        rule.waitUntil(60_000) {
            rule.onAllNodesWithText("Continue to Connections").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Continue to Connections").performClick()
        rule.waitUntil(60_000) {
            rule.onAllNodesWithText("View transfers").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("View transfers on current verified server").performScrollTo().performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("2 transfers", substring = true).fetchSemanticsNodes().isNotEmpty() }

        // Real row, bundled category art from the original torrent name; nothing looked up.
        waitForDescription("TV episode category artwork. $format")
        val serverId = requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(
            RemoteVerifiedUiIdentity.aliasScopeOf(RemoteCredentials(endpoint, "", ""))))
        val key = MediaTransferKey.of(UnifiedTransferKey.of(serverId, "ab".repeat(20)))
        val methodsBeforeMedia = receivedMethods.filter { it != "d.multicall2" && it != "system.client_version" }

        // The user's own image through the production import pipeline (Photo Picker bytes path).
        media.controller.importUserImage(key, userImage())
        waitForDescription("Your artwork. $format")
        rule.waitForIdle()
        val tile = rule.onNode(hasContentDescription("Your artwork. $format"), useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        val corner = tile.getPixel(tile.width / 5, tile.height / 5)
        assertTrue("Installed row must draw the user's orange image, saw #" + Integer.toHexString(corner),
            android.graphics.Color.red(corner) > 200 && android.graphics.Color.green(corner) in 80..160 &&
                android.graphics.Color.blue(corner) < 60)
        save("media-row-remote-user-artwork-installed")

        // Options → Artwork & info: turn artwork off; the visible row returns to category art.
        rule.onNodeWithTag(ChunksSemantics.FOOTER_OPTIONS).performClick()
        rule.onNodeWithText("Search options").performTextInput("artwork")
        hideKeyboard()
        rule.onNodeWithText("Artwork & media info").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Show artwork").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Online lookup").assertExists()
        save("media-settings-installed")
        rule.onNodeWithText("Show artwork").performScrollTo().performClick()
        rule.waitUntil(5_000) { !media.settingsStore.load().showArtwork }
        rule.onNodeWithText("Back").performScrollTo().performClick()
        // Back from media settings restores central Options. Navigate through
        // the same Combined inventory to the exact authenticated server list.
        rule.onNodeWithText("Options · this device").assertExists()
        // The canonical Transfers catalog (not a legacy server queue) hosts the exact row.
        rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(episode))
        waitForDescription("TV episode category artwork. $format")

        // Artwork back on: the user's image returns from the app's own processed copy (no network).
        media.updateSettings(media.settingsStore.load().copy(showArtwork = true))
        waitForDescription("Your artwork. $format")
        // Clear artwork, matches & lookup history: the visible row falls back to category art at once.
        runBlocking { media.clearAllMediaData().join() }
        waitForDescription("TV episode category artwork. $format")
        assertTrue("Cleared assignments leave no searchable title", media.controller.searchableTitles().isEmpty())

        assertEquals("Media display and settings must not issue any further RPC beyond list refreshes",
            methodsBeforeMedia, receivedMethods.filter { it != "d.multicall2" && it != "system.client_version" })
        assertTrue("A user image is local: online lookup stays off", !media.settingsStore.load().onlineLookupConsent)
    }
}
