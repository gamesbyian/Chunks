package io.github.gamesbyian.chunks

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import org.junit.Rule
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.media.ChunksMedia
import io.github.gamesbyian.chunks.media.FileMediaAutomationPreferences
import io.github.gamesbyian.chunks.media.FileTransferMediaAssignmentStore
import io.github.gamesbyian.chunks.media.MediaAssignmentState
import io.github.gamesbyian.chunks.media.MediaPrivacySettings
import io.github.gamesbyian.chunks.media.MediaTransferKey
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Collections

/**
 * Opt-in installed backend integration against TWO actually running disposable
 * rTorrent daemons and real public TVmaze + artwork HTTPS. Tests exact principal,
 * same-hash exclusion, actual persisted image bytes and fresh offline process.
 * Does not claim canonical UI/Details acceptance or a protected Seedhost account.
 */
@RunWith(AndroidJUnit4::class)
class InstalledDisposableRemotePublicMediaInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val prefs get() = context.getSharedPreferences("installed-disposable-remote-art", Context.MODE_PRIVATE)
    private val media get() = ChunksMedia.get(context)
    private val fixtureName = "Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.x264-CHUNKS.mkv"

    private fun gate() {
        assumeTrue("Disposable-only explicit public provider opt-in",
            args.getString("chunks_remote_media") == "authorized")
        val fd: ParcelFileDescriptor = instrumentation.uiAutomation
            .executeShellCommand("getprop ro.kernel.qemu")
        val qemu = ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() }
        assertEquals("Never run against an owner's device", "1", qemu)
    }

    private fun decoded(name: String): String =
        String(Base64.decode(requireNotNull(args.getString(name)), Base64.NO_WRAP), Charsets.UTF_8)

    private class HttpsWitness(private val previous: ProxySelector?, private val offline: Boolean) : ProxySelector() {
        val requested = Collections.synchronizedList(mutableListOf<URI>())
        override fun select(uri: URI): List<Proxy> {
            if (uri.scheme == "https") {
                requested.add(uri)
                if (offline) throw IllegalStateException("Offline test tried network artwork retrieval")
            }
            return previous?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            previous?.connectFailed(uri, sa, ioe)
        }
    }

    private fun keyA() = MediaTransferKey.of(UnifiedTransferKey.of(
        EnvironmentSnapshots.serverId(requireNotNull(prefs.getString("endpoint_a", null)), "chunks"),
        requireNotNull(prefs.getString("hash", null))))
    private fun keyB() = MediaTransferKey.of(UnifiedTransferKey.of(
        EnvironmentSnapshots.serverId(requireNotNull(prefs.getString("endpoint_b", null)), ""),
        requireNotNull(prefs.getString("hash", null))))
    private fun saved() = FileTransferMediaAssignmentStore(File(context.filesDir, "media/assignments.json"))
    private fun excluded() = FileMediaAutomationPreferences(File(context.filesDir, "media/automation.json"))

    private fun await(what: String, limitMs: Long = 100_000, condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + limitMs
        while (android.os.SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            Thread.sleep(350)
        }
        throw AssertionError("Timed out waiting for " + what)
    }

    private fun visible(text: String): Boolean =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(what: String, limit: Long = 70_000, condition: () -> Boolean) {
        try { rule.waitUntil(limit, condition) }
        catch (failure: Throwable) { throw AssertionError("Timed out waiting for $what", failure) }
    }
    private fun scrollTo(text: String) {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = true))
    }
    private fun enter(label: String, value: String) {
        var error: Throwable? = null
        repeat(5) {
            try {
                scrollTo(label)
                rule.waitForIdle()
                rule.onNodeWithText(label).performTextReplacement(value)
                rule.waitForIdle()
                return
            } catch (problem: AssertionError) {
                error = problem
                Thread.sleep(350)
            }
        }
        throw AssertionError("Could not enter $label", error)
    }
    private fun verifyActualCanonicalRowAndDetails(urlA: String, hash: String) {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("persistent Connections") { visible("Connections") }
            rule.onNodeWithText("Connections").performClick()
            waitFor("real rTorrent connection form") {
                visible("Seedbox hostname, ruTorrent URL or RPC address")
            }
            enter("Seedbox hostname, ruTorrent URL or RPC address", urlA)
            enter("Username, if required", "chunks")
            enter("Password", "chunks-one")
            scrollTo("Connect to rTorrent")
            rule.onNodeWithText("Connect to rTorrent").performClick()
            waitFor("verified authenticated server queue", 90_000) {
                visible("127.0.0.1 · 2 transfers") && visible("+ Add torrent")
            }
            // Enter canonical Combined Transfers instead of asserting the
            // legacy server queue counts as source-exact UI acceptance.
            rule.onAllNodesWithText("All environments…")[0].performClick()
            waitFor("canonical Combined browser") { visible("All environments") }
            val row = hasContentDescription("Details for $fixtureName on", substring = true) and
                hasContentDescription(":18080", substring = true)
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(row)
            val artwork = hasContentDescription("Artwork: Babylon 5", substring = true)
            waitFor("real provider artwork semantics on exact rTorrent row") {
                rule.onAllNodes(artwork, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val rendered = rule.onAllNodes(artwork, useUnmergedTree = true).onFirst()
                .captureToImage().asAndroidBitmap()
            val bitmap = requireNotNull(media.controller.observe(keyA(), fixtureName).value.artwork)
            val expected = Bitmap.createScaledBitmap(bitmap, rendered.width, rendered.height, true)
            var channelError = 0L
            for (y in 3..6) for (x in 3..6) {
                val actual = rendered.getPixel(rendered.width * x / 10, rendered.height * y / 10)
                val reference = expected.getPixel(rendered.width * x / 10, rendered.height * y / 10)
                for (shift in listOf(0, 8, 16))
                    channelError += kotlin.math.abs(((actual ushr shift) and 255) -
                        ((reference ushr shift) and 255))
            }
            assertTrue("Source-exact canonical remote row must paint actual provider pixels",
                channelError / 48 < 45)
            rule.onNode(row).performClick()
            waitFor("full canonical Details for exact server", 45_000) {
                visible("Transfer details") && visible("Environment:") && visible(":18080")
            }
            assertTrue("Full Details media section must exist", visible("Artwork & media information"))
            waitFor("full Details poster from same verified remote assignment") {
                media.controller.observe(keyA(), fixtureName).value.poster != null
            }
            assertEquals(hash, requireNotNull(prefs.getString("hash", null)))
            android.util.Log.i("ChunksRemoteMedia",
                "CHUNKS_REMOTE_CANONICAL screen=Combined remote_auth=true row_pixels=true details_poster=true exact_account=true")
        }
    }

    @Test fun phaseA_authenticatedRemoteInventoryObtainsRealArtWhileOtherAccountExcluded() {
        gate()
        val urlA = decoded("chunks_media_a_b64")
        val urlB = decoded("chunks_media_b_b64")
        val hash = decoded("chunks_media_hash_b64").lowercase()
        require(urlA == "http://127.0.0.1:18080/RPC2" && urlB == "http://127.0.0.1:18082/RPC2")
        val credsA = RemoteCredentials(urlA, "chunks", "chunks-one")
        val credsB = RemoteCredentials(urlB, "", "")
        val rowsA = runBlocking { RTorrentRemote(credsA).listTransfers("main") }
        val rowsB = runBlocking { RTorrentRemote(credsB).listTransfers("main") }
        val a = rowsA.single { it.hash.lowercase() == hash }
        val b = rowsB.single { it.hash.lowercase() == hash }
        assertEquals(fixtureName, a.name)
        assertEquals(fixtureName, b.name)
        assertFalse("Both actual RPC jobs must stay stopped", a.started)
        assertFalse("Both actual RPC jobs must stay stopped", b.started)
        runBlocking { media.clearAllMediaData().join() }
        media.updateSettings(MediaPrivacySettings(
            onlineLookupConsent = true, automaticLookup = true,
            disabledProviders = setOf("tmdb", "wikidata", "anilist", "musicbrainz", "openlibrary")))
        assertTrue(prefs.edit().putString("endpoint_a", urlA).putString("endpoint_b", urlB)
            .putString("hash", hash).commit())
        assertNotEquals("Two authenticated endpoints must have different exact keys", keyA(), keyB())
        assertTrue("Persist exact second-profile exclusion before exposing inventory",
            media.controller.prepareForAdd(UnifiedTransferKey.of(
                EnvironmentSnapshots.serverId(urlB, ""), hash), allowAutomaticLookup = false))
        assertTrue(excluded().isExcluded(keyB()))
        assertFalse(excluded().isExcluded(keyA()))
        val original = ProxySelector.getDefault()
        val witness = HttpsWitness(original, offline = false)
        ProxySelector.setDefault(witness)
        try {
            // This is the production controller, fed by two genuine XML-RPC reads.
            // Neither transfer list row nor Details is composed.
            runBlocking(Dispatchers.IO) {
                media.controller.enrichInventory(listOf(keyA() to a.name, keyB() to b.name)) {
                    it == keyA() || it == keyB()
                }
            }
            await("verified, offscreen, exact authenticated rTorrent identity") {
                saved().get(keyA())?.let { it.state == MediaAssignmentState.AUTOMATIC &&
                    it.match?.ref?.provider == "tvmaze" && it.match?.ref?.externalId == "475" } == true
            }
            await("downloaded real CDN poster for remote exact job") {
                File(context.cacheDir, "media-art").listFiles().orEmpty()
                    .any { it.name.endsWith(".img") && it.length() > 64 }
            }
            assertNull("Same hash on deliberately excluded other profile must stay untouched",
                saved().get(keyB()))
            // A composed row subscribes before the asynchronous cached-art
            // read completes. Observe the same state flow and await its bitmap;
            // the preceding disk assertion independently proves CDN acquisition.
            media.controller.observe(keyA(), fixtureName)
            await("remote row bitmap loaded asynchronously from saved derivative") {
                media.controller.observe(keyA(), fixtureName).value.artwork != null
            }
            val bitmap = requireNotNull(media.controller.observe(keyA(), fixtureName).value.artwork)
            assertTrue(bitmap.width >= 100 && bitmap.height >= 100)
            val urls = synchronized(witness.requested) { witness.requested.toList() }
            assertTrue("Real public metadata HTTPS expected", urls.any { it.host == "api.tvmaze.com" })
            assertTrue("Real CDN HTTPS expected", urls.any { it.host == "static.tvmaze.com" })
            urls.forEach { uri ->
                assertTrue("Only explicit TVmaze/CDN hosts", uri.host in setOf("api.tvmaze.com", "static.tvmaze.com"))
                val decoded = java.net.URLDecoder.decode(uri.toString(), "UTF-8")
                listOf(hash, "WEB-DL", "1080p", "x264", "CHUNKS.mkv", "announce",
                    "127.0.0.1", "RPC2").forEach { token ->
                    assertFalse("Never disclose private/transfer fields to provider", decoded.contains(token, true))
                }
            }
            verifyActualCanonicalRowAndDetails(urlA, hash)
            assertTrue(prefs.edit().putInt("pid", android.os.Process.myPid()).commit())
            android.util.Log.i("ChunksRemoteMedia", "CHUNKS_REMOTE_PUBLIC phase=online rpc_auth=true same_hash_two_accounts=true exact_opt_out=true real_provider=true real_art=true sanitized=true")
        } finally { ProxySelector.setDefault(original) }
    }

    @Test fun phaseB_newProcessOfflineRestoresExactRemoteArtWithoutHttp() {
        gate()
        assertTrue("Online test must have completed", prefs.contains("pid"))
        assertNotEquals("Genuine new process required", prefs.getInt("pid", 0), android.os.Process.myPid())
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        await("Android offline radios") { cm.activeNetwork == null }
        assertTrue("Exact other-account exclusion must persist", excluded().isExcluded(keyB()))
        assertNull("Excluded same hash must remain unassigned", saved().get(keyB()))
        assertEquals(MediaAssignmentState.AUTOMATIC, saved().get(keyA())?.state)
        val original = ProxySelector.getDefault()
        val witness = HttpsWitness(original, offline = true)
        ProxySelector.setDefault(witness)
        try {
            val state = media.controller.observe(keyA(), fixtureName).value
            await("offline real CDN derivative restored to a fresh process") {
                media.controller.observe(keyA(), fixtureName).value.artwork != null
            }
            val bitmap: Bitmap = requireNotNull(media.controller.observe(keyA(), fixtureName).value.artwork)
            assertTrue(bitmap.width >= 100 && bitmap.height >= 100)
            assertEquals("475", saved().get(keyA())?.match?.ref?.externalId)
            assertTrue("No new HTTPS during offline restoration", witness.requested.isEmpty())
            android.util.Log.i("ChunksRemoteMedia", "CHUNKS_REMOTE_PUBLIC phase=offline new_process=true exact_opt_out=true bitmap=true requests=0")
        } finally {
            ProxySelector.setDefault(original)
            runBlocking { media.clearAllMediaData().join() }
            media.updateSettings(MediaPrivacySettings())
            prefs.edit().clear().commit()
        }
    }
}
