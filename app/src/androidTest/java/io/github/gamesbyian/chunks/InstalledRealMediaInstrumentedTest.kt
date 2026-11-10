package io.github.gamesbyian.chunks

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.media.*
import io.github.gamesbyian.chunks.media.ui.candidateSubtitle
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import org.libtorrent4j.TorrentBuilder
import java.io.File
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Manual-only real HTTPS providers through the installed production UI and process graph.
 * Torrent jobs are stopped synthetic fixtures, NOT BitTorrent download evidence. No HTTP client,
 * controller, provider, response or image is replaced. ProxySelector observes real outgoing URIs
 * in memory; only counts and booleans are exported. Phase B runs in a fresh, offline process. */
@RunWith(AndroidJUnit4::class)
class InstalledRealMediaInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val media get() = ChunksMedia.get(context)
    private val prefs get() = context.getSharedPreferences("installed-real-media-fixtures", Context.MODE_PRIVATE)
    private val searchLabel = "Search local names, aliases, folders or hashes"
    private val fixtures = listOf(
        Triple("Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.x264-GRP.mkv", "Babylon 5", "tvmaze"),
        Triple("Radiohead - OK Computer (1997) [FLAC] {24-96}", "OK Computer", "musicbrainz"),
        Triple("Frank Herbert - Dune (1965) [retail].epub", "Dune", "openlibrary"))
    private var activity: MainActivity? = null
    private var capturePhase = "online"

    private fun authorized() {
        assumeTrue("Explicit rate-limited installed real-provider campaign only",
            InstrumentationRegistry.getArguments().getString("chunks_real_media") == "authorized")
        assertEquals("Disposable emulator required", "1", android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")).bufferedReader().use { it.readText().trim() })
    }
    private class ObserveRequests(private val original: ProxySelector?, private val offline: Boolean) : ProxySelector() {
        val uris = Collections.synchronizedList(mutableListOf<URI>())
        override fun select(uri: URI): List<Proxy> {
            if (uri.scheme == "https") {
                uris.add(uri)
                if (offline) throw IllegalStateException("Unexpected offline HTTPS request")
            }
            return original?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) {
            original?.connectFailed(uri, address, failure)
        }
    }
    private fun visible(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun scroll(text: String) {
        if (runCatching { rule.onNodeWithText(text).assertIsDisplayed() }.isSuccess) return
        if (rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty())
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = true))
        rule.onNodeWithText(text).performScrollTo()
    }
    private fun click(text: String) {
        rule.waitUntil(20_000) { runCatching { scroll(text); rule.onNodeWithText(text).assertIsEnabled(); true }.getOrDefault(false) }
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
    }
    private fun type(label: String, value: String) {
        scroll(label); rule.onNodeWithText(label).performTextReplacement(value)
        instrumentation.runOnMainSync {
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity?.window?.decorView?.windowToken, 0)
        }
    }
    private fun key(index: Int): MediaTransferKey = MediaTransferKey.of(UnifiedTransferKey.of(
        EnvironmentSnapshots.PHONE, requireNotNull(prefs.getString("hash-$index", null))))
    private fun state(index: Int) = media.controller.observe(key(index), fixtures[index].first).value
    private fun awaitMedia(index: Int, step: String, timeout: Long = 60_000, condition: () -> Boolean) {
        try { rule.waitUntil(timeout, condition) } catch (failure: Throwable) {
            val s = state(index)
            throw AssertionError("Installed provider ${fixtures[index].third}: $step; state=${s.state} " +
                "busy=${s.busy} result=${s.assignment?.lastStatus} suggestions=${s.assignment?.suggestions?.size} " +
                "search=${s.searchResults?.size} artwork=${s.artwork != null}", failure)
        }
    }
    private fun correctCandidate(index: Int, candidate: MediaCandidate): Boolean =
        candidate.title.equals(fixtures[index].second, ignoreCase = true) &&
            candidate.ref.provider == fixtures[index].third && when (index) {
                0 -> candidate.ref.externalId == "475" // Original 1993 series, not the announced CW reboot.
                1 -> candidate.creators.any { it.contains("Radiohead", true) }
                2 -> candidate.creators.any { it.contains("Frank Herbert", true) }
                else -> true
            }
    private fun openRow(index: Int) {
        type(searchLabel, requireNotNull(prefs.getString("hash-$index", null)))
        click("More details")
        click("Change identification or artwork…")
        rule.waitUntil(10_000) { visible("Identify this transfer") }
    }
    private fun closeRow() {
        click("Close media identification"); click("Hide details")
    }
    private fun verifyArtworkPixels(index: Int) {
        // The original name also appears in read-only detail/continuity text.
        // The card header's full accessibility label uniquely identifies this row.
        rule.onNodeWithContentDescription(fixtures[index].first).performScrollTo().assertIsDisplayed()
        val s = state(index)
        val bitmap = requireNotNull(s.artwork)
        val node = rule.onAllNodes(hasContentDescription("Artwork: ${s.match!!.title}", substring = true), useUnmergedTree = true)
        rule.waitUntil(20_000) { node.fetchSemanticsNodes().isNotEmpty() }
        val tile = node.onFirst().captureToImage().asAndroidBitmap()
        val scaled = Bitmap.createScaledBitmap(bitmap, tile.width, tile.height, true)
        var error = 0L
        for (y in 3..6) for (x in 3..6) {
            val actual = tile.getPixel(tile.width * x / 10, tile.height * y / 10)
            val expected = scaled.getPixel(tile.width * x / 10, tile.height * y / 10)
            for (shift in listOf(0, 8, 16)) error += kotlin.math.abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255))
        }
        assertTrue("Transfer row must draw the downloaded provider bitmap (mean channel error=${error / 48})", error / 48 < 45)
        val output = File(context.getExternalFilesDir(null), "real-media-evidence").apply { mkdirs() }
        File(output, "provider-$index-$capturePhase.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun assertSanitized(requests: ObserveRequests) {
        val hashes = fixtures.indices.map { requireNotNull(prefs.getString("hash-$it", null)) }
        val forbidden = hashes + fixtures.map { it.first } + listOf("1080p", "WEB-DL", "x264", "GRP", ".mkv", "FLAC", "24-96", "retail", ".epub", "passkey", "announce", "magnet:", "password", "credential", "chunks-test-private-token", "chunks-test-credential", "/workspace", context.filesDir.path)
        val allowed = media.providers.filter { it.info.id in setOf("tvmaze", "musicbrainz", "openlibrary") }
        synchronized(requests.uris) {
            assertTrue("Observed genuine outgoing provider requests", requests.uris.isNotEmpty())
            requests.uris.forEach { uri ->
                val decoded = java.net.URLDecoder.decode(uri.toString(), "UTF-8")
                assertNull("No URL credentials", uri.userInfo)
                assertTrue("Only the permitted provider API/artwork hosts", allowed.any {
                    it.info.apiHostPolicy.allows(uri.host) || it.info.artworkHostPolicy.allows(uri.host) })
                forbidden.forEach { token -> assertFalse("A private/release token escaped in a provider request", decoded.contains(token, true)) }
            }
        }
    }

    @Test fun phaseA_tvmazeConsentSelectionAndArtwork() = online(0)
    @Test fun phaseA_musicbrainzSelectionAndArtwork() = online(1)
    @Test fun phaseA_openlibrarySelectionAndArtwork() = online(2)
    @Test fun phaseB_tvmazeOfflineProcess() = offline(0)
    @Test fun phaseB_musicbrainzOfflineProcess() = offline(1)
    @Test fun phaseB_openlibraryOfflineProcess() = offline(2)

    private fun online(index: Int) {
        authorized()
        if (index == 0) {
            RememberedRemoteLogin(context).clear(); RememberedRemoteProfileRoster(context).clearAll()
            runBlocking { media.clearAllMediaData().join() }
            media.updateSettings(MediaPrivacySettings(disabledProviders = setOf("tmdb", "wikidata", "anilist")))
        }
        val previous = ProxySelector.getDefault()
        val requests = ObserveRequests(previous, offline = false)
        ProxySelector.setDefault(requests)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity = it }
                rule.waitUntil(30_000) { visible("On this phone (experimental)") }
                if (index == 0) {
                    val engine = LocalEngineHolder.get(context)
                    fixtures.forEachIndexed { index, f ->
                        val root = File(context.filesDir, "real-media-fixture-$index").apply { mkdirs() }
                        val file = File(root, f.first).apply { writeBytes(ByteArray(16_384) { (it % 251).toByte() }) }
                        val raw = TorrentBuilder().path(file).pieceSize(16_384).setPrivate(true)
                            .addTracker("https://private.invalid/announce?passkey=chunks-test-private-token")
                            .comment("credential=chunks-test-credential").generate().entry().bencode()
                        val result = engine.addStopped(raw, root)
                        assertTrue("Stopped real native job for media presentation", result is LocalAddResult.AddedStopped)
                        val hash = (result as LocalAddResult.AddedStopped).status.infoHash
                        assertTrue(prefs.edit().putString("hash-$index", hash).commit())
                        assertTrue("Stopped job metadata saved for cold process restore", engine.saveResumeDataFor(hash, 30_000))
                    }
                    rule.waitUntil(20_000) { visible(fixtures[0].first) }
                }
                openRow(index)
                if (index == 0) {
                    assertFalse(media.settingsStore.load().onlineLookupConsent)
                    assertTrue("Opening a transfer/Identify cannot query a provider", requests.uris.isEmpty())
                    click("Turn on online lookup…")
                    rule.onNodeWithText("Turn on online lookup?").assertExists()
                    rule.onNodeWithText("These services can see your network address.", substring = true).assertExists()
                    assertTrue("Consent dialog makes no request", requests.uris.isEmpty())
                    click("Not now")
                    assertFalse(media.settingsStore.load().onlineLookupConsent)
                    click("Turn on online lookup…"); click("Turn on")
                    rule.waitUntil(10_000) { media.settingsStore.load().onlineLookupConsent && visible("Look up online") }
                    assertTrue("Enabling manual lookup alone makes no request", requests.uris.isEmpty())
                } else {
                    assertTrue("Explicit global consent persists for the next provider", media.settingsStore.load().onlineLookupConsent)
                    assertTrue("Opening another row makes no request", requests.uris.isEmpty())
                }
                click("Look up online")
                awaitMedia(index, "lookup") { !state(index).busy && state(index).assignment?.lastStatus != null }
                assertTrue("A permitted provider response is required, got ${state(index).assignment?.lastStatus}",
                    state(index).assignment?.lastStatus in setOf(MediaLookupStatus.MATCHED, MediaLookupStatus.NEEDS_REVIEW))
                // Ambiguous results are a supported outcome: select the correct
                // candidate explicitly before requiring its image, never silently
                // turn a suggestion into a confirmed/automatic match for the test.
                val proposed = listOfNotNull(state(index).match) + state(index).assignment?.suggestions.orEmpty()
                assertTrue("Correct provider candidate returned", proposed.any { correctCandidate(index, it) })
                // Explicitly choose the correct provider result through the actual manual search UI.
                type("Title", fixtures[index].second)
                click("Search")
                awaitMedia(index, "manual search") { !state(index).busy && state(index).searchResults != null }
                assertTrue("A real provider search must return candidates", state(index).searchResults!!.isNotEmpty())
                val chosen = state(index).searchResults!!.first { correctCandidate(index, it) }
                val choice = rule.onNode(hasClickAction() and
                    SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
                        androidx.compose.ui.semantics.Role.RadioButton) and
                    (hasText(chosen.title) or hasAnyDescendant(hasText(chosen.title))) and
                    (hasText(candidateSubtitle(chosen)) or hasAnyDescendant(hasText(candidateSubtitle(chosen)))))
                choice.performScrollTo().assertIsDisplayed()
                choice.performSemanticsAction(SemanticsActions.OnClick)
                awaitMedia(index, "selected artwork", 30_000) { state(index).state == MediaAssignmentState.CHOSEN_BY_USER && state(index).artwork != null }
                assertEquals("The visible candidate selection must preserve exact provider identity", chosen.ref, state(index).match!!.ref)
                assertEquals(fixtures[index].third, state(index).match!!.ref.provider)
                closeRow()
                verifyArtworkPixels(index)
                // Navigate away/back without losing the assignment or the rendered image.
                click("All environments…"); click("Back")
                type(searchLabel, requireNotNull(prefs.getString("hash-$index", null)))
                verifyArtworkPixels(index)
                assertSanitized(requests)
                assertTrue(prefs.edit().putInt("online-pid-$index", android.os.Process.myPid()).commit())
                type(searchLabel, "")
                android.util.Log.i("ChunksRealMedia", "CHUNKS_REAL_MEDIA_PROVIDER provider=${fixtures[index].third} phase=online consent=true selected=true pixels=true navigated=true sanitized=true requests=${requests.uris.size}")
            }
        } finally { ProxySelector.setDefault(previous) }
    }

    private fun offline(index: Int) {
        authorized()
        capturePhase = "offline"
        assertTrue("The corresponding online case must pass first", prefs.contains("online-pid-$index"))
        assertNotEquals("A genuinely new app process is required", prefs.getInt("online-pid-$index", 0), android.os.Process.myPid())
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        rule.waitUntil(15_000) { cm.activeNetwork == null }
        assertNull("Runner must remove the default network before offline restart", cm.activeNetwork)
        val previous = ProxySelector.getDefault()
        val requests = ObserveRequests(previous, offline = true)
        ProxySelector.setDefault(requests)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity = it }
                rule.waitUntil(30_000) { visible("On this phone (experimental)") }
                assertTrue("Consent persisted", media.settingsStore.load().onlineLookupConsent)
                type(searchLabel, requireNotNull(prefs.getString("hash-$index", null)))
                awaitMedia(index, "offline restoration", 30_000) {
                    state(index).state == MediaAssignmentState.CHOSEN_BY_USER && state(index).artwork != null
                }
                verifyArtworkPixels(index)
                assertTrue("Correct provider identity persists", correctCandidate(index, state(index).match!!))
                type(searchLabel, "")
                assertTrue("Offline UI restoration made zero HTTPS attempts", requests.uris.isEmpty())
                android.util.Log.i("ChunksRealMedia", "CHUNKS_REAL_MEDIA_PROVIDER provider=${fixtures[index].third} phase=offline new_process=true persisted=true pixels=true requests=0")
            }
        } finally {
            ProxySelector.setDefault(previous)
            if (index == 2) {
                fixtures.indices.forEach { i -> prefs.getString("hash-$i", null)?.let { LocalEngineHolder.current()?.removeKeepingData(it) } }
                runBlocking { media.clearAllMediaData().join() }
                media.updateSettings(MediaPrivacySettings())
            }
        }
    }
}
