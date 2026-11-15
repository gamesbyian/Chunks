package io.github.gamesbyian.chunks

import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.media.*
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Manual-only: automatic artwork for a REMOTE rTorrent inventory through the
 * installed production UI. A disposable loopback rTorrent holds one stopped
 * job whose name is a public TV-episode release; the app connects through its
 * own Connections form and opens Transfers. No Identify tap, no Start, no
 * mutation. Real TVmaze metadata + CDN HTTP (observed, not replaced). The job
 * is a synthetic stopped fixture, NOT BitTorrent download evidence.
 */
@RunWith(AndroidJUnit4::class)
class InstalledRemotePublicMediaInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val media get() = ChunksMedia.get(context)
    private val args get() = InstrumentationRegistry.getArguments()
    private fun decoded(name: String) =
        String(Base64.decode(requireNotNull(args.getString(name)) { name }, Base64.NO_WRAP), Charsets.UTF_8)

    private class Observer(private val saved: ProxySelector?) : ProxySelector() {
        val uris = Collections.synchronizedList(mutableListOf<URI>())
        override fun select(uri: URI): List<Proxy> {
            if (uri.scheme == "https") uris.add(uri)
            return saved?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            saved?.connectFailed(uri, sa, ioe)
        }
    }

    private fun visible(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun scrollTo(text: String) {
        val match = hasText(text, substring = true)
        if (rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty())
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(match)
        else rule.onNode(match).performScrollTo()
    }

    private fun typeInto(label: String, value: String) {
        var last: Throwable? = null
        repeat(5) {
            try {
                scrollTo(label)
                rule.waitForIdle()
                rule.onNodeWithText(label).performTextReplacement(value)
                rule.waitForIdle()
                return
            } catch (failure: AssertionError) {
                last = failure
                Thread.sleep(400)
            }
        }
        throw AssertionError("Could not enter $label", last)
    }

    private fun waitFor(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs, condition)
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            // Disposable loopback fixture with a public title only: safe to print.
            val ui = runCatching { rule.onRoot(useUnmergedTree = true).printToString().take(6_000) }
                .getOrDefault("semantics unavailable")
            throw AssertionError("Timed out: $what\n$ui", failure)
        }
    }

    @Test fun remoteInventoryGetsAutomaticArtworkWithoutIdentify() {
        assumeTrue("One-shot explicitly authorized remote public-media proof only",
            args.getString("chunks_remote_media") == "authorized")
        val qemu = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu"))
            .bufferedReader().use { it.readText().trim() }
        assertEquals("Disposable emulator, not owner's real device", "1", qemu)
        val url = decoded("chunks_remote_media_url_b64")
        val originalName = decoded("chunks_remote_media_name_b64")
        val hash = decoded("chunks_remote_media_hash_b64").lowercase()
        require(url.startsWith("http://127.0.0.1:")) { "disposable loopback server only" }
        val credentials = RemoteCredentials(url, "", "")
        val before = runBlocking { RTorrentRemote(credentials).listTransfers("main") }
        val fixture = before.single { it.hash.equals(hash, ignoreCase = true) }
        assertEquals(originalName, fixture.name)
        assertFalse("The remote fixture is staged stopped", fixture.started)

        val environment = requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(
            RemoteVerifiedUiIdentity.aliasScopeOf(credentials)))
        val key = MediaTransferKey.of(UnifiedTransferKey.of(environment, hash))
        val assignments = FileTransferMediaAssignmentStore(File(context.filesDir, "media/assignments.json"))

        RememberedRemoteLogin(context).clear()
        RememberedRemoteProfileRoster(context).clearAll()
        runBlocking { media.clearAllMediaData().join() }
        // Bounded public provider set for this one-shot; other providers off.
        media.updateSettings(MediaPrivacySettings(
            onlineLookupConsent = true, automaticLookup = true,
            disabledProviders = setOf("tmdb", "wikidata", "anilist", "musicbrainz", "openlibrary")))
        val original = ProxySelector.getDefault()
        val network = Observer(original)
        ProxySelector.setDefault(network)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                waitFor("persistent Connections destination") { visible("Connections") }
                rule.onNodeWithContentDescription("Connections").performClick()
                waitFor("connection form") {
                    runCatching { scrollTo("Seedbox hostname, ruTorrent URL or RPC address"); true }
                        .getOrDefault(false)
                }
                typeInto("Connection name (optional)", "Media box")
                typeInto("Seedbox hostname, ruTorrent URL or RPC address", url)
                val connect = hasTestTag(ChunksSemantics.CONNECTION_CONNECT)
                waitFor("enabled Connect", 20_000) {
                    runCatching {
                        rule.onNode(hasScrollToIndexAction()).performScrollToNode(connect)
                        rule.onNode(connect, useUnmergedTree = true).assertIsDisplayed().assertIsEnabled()
                        true
                    }.getOrDefault(false)
                }
                rule.waitForIdle()
                // Exactly one Connect dispatch.
                rule.onNode(connect, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
                waitFor("verified Connect acknowledgment", 90_000) { visible("Connection verified") }
                rule.onNodeWithText("Continue to Connections").performClick()
                waitFor("connected card") {
                    runCatching { scrollTo("Connected ·") }
                    visible("Connected ·") && visible("View transfers")
                }
                rule.onNodeWithContentDescription("View transfers on current verified server").performClick()
                waitFor("remote fixture row in canonical Transfers", 60_000) {
                    runCatching { scrollTo(originalName); true }.getOrDefault(false)
                }
                // Merely observing the remote inventory must identify it automatically.
                waitFor("automatic TVmaze assignment for the exact remote key", 90_000) {
                    runBlocking(Dispatchers.IO) {
                        assignments.get(key)?.let { a ->
                            a.state == MediaAssignmentState.AUTOMATIC && a.match?.ref?.provider == "tvmaze"
                        } == true
                    }
                }
                assertEquals("475", assignments.get(key)?.match?.ref?.externalId)
                waitFor("cached provider image", 60_000) {
                    File(context.cacheDir, "media-art").listFiles().orEmpty()
                        .any { it.name.endsWith(".img") && it.length() > 64 }
                }
                val requests = synchronized(network.uris) { network.uris.toList() }
                assertTrue("Real TVmaze metadata HTTP required", requests.any { it.host == "api.tvmaze.com" })
                assertTrue("Real TVmaze image-CDN HTTP required", requests.any { it.host == "static.tvmaze.com" })
                requests.forEach { uri ->
                    assertTrue("Only authorized public TVmaze hosts: ${uri.host}",
                        uri.host in setOf("api.tvmaze.com", "static.tvmaze.com"))
                    val decoded = java.net.URLDecoder.decode(uri.toString(), "UTF-8")
                    listOf("1080p", "WEB-DL", "x264", "GRP", ".mkv", hash, "127.0.0.1", "Media box",
                        "announce").forEach { token ->
                        assertFalse("Forbidden release/transfer/server data in provider request",
                            decoded.contains(token, ignoreCase = true))
                    }
                }
                // The remote row actually paints the provider bitmap.
                val tile = rule.onAllNodes(hasContentDescription("Artwork: Babylon 5", substring = true),
                    useUnmergedTree = true)
                waitFor("row artwork", 25_000) { tile.fetchSemanticsNodes().isNotEmpty() }
                val bitmap = requireNotNull(media.controller.observe(key, originalName).value.artwork) {
                    "Remote row must show the verified provider bitmap"
                }
                val painted = tile.onFirst().captureToImage().asAndroidBitmap()
                val expected = Bitmap.createScaledBitmap(bitmap, painted.width, painted.height, true)
                var total = 0L
                for (y in 3..6) for (x in 3..6) {
                    val a = painted.getPixel(painted.width * x / 10, painted.height * y / 10)
                    val e = expected.getPixel(painted.width * x / 10, painted.height * y / 10)
                    for (shift in listOf(0, 8, 16))
                        total += kotlin.math.abs(((a ushr shift) and 255) - ((e ushr shift) and 255))
                }
                assertTrue("Remote canonical row paints the provider JPEG", total / 48 < 45)
                val dir = File(context.getExternalFilesDir(null), "remote-media-evidence").apply { mkdirs() }
                File(dir, "remote-row.png").outputStream().use {
                    rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally {
            ProxySelector.setDefault(original)
        }
        // Read-only: identification never started, renamed or otherwise changed the job.
        val after = runBlocking { RTorrentRemote(credentials).listTransfers("main") }
            .single { it.hash.equals(hash, ignoreCase = true) }
        assertEquals(originalName, after.name)
        assertFalse("Artwork lookup must never start the remote job", after.started)
        android.util.Log.i("ChunksRemoteMedia",
            "CHUNKS_REMOTE_PUBLIC_MEDIA automatic=true provider=tvmaze row_pixels=true sanitized=true job_unchanged=true")
    }
}
