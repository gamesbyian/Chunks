package io.github.gamesbyian.chunks

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticExportProvider
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import io.github.gamesbyian.chunks.media.*
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder
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
 * Manual-only public-provider, installed-Android qualification of automatic media.
 *
 * Phase A loads a *real stopped native torrent*, then opens canonical Transfers.
 * No Identify, remote auth, tracker connection or mutation of an existing job occurs.
 * Provider HTTP and CDN image download are real. Phase B runs after a forced process
 * stop with Android radios off, asserting true installed offline poster reuse.
 * The two phases are intentionally never ordinary CI and require explicit opt-in.
 */
@RunWith(AndroidJUnit4::class)
class InstalledAutomaticPublicMediaInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val media get() = ChunksMedia.get(context)
    private val prefs get() = context.getSharedPreferences("installed-public-auto-media-qa", Context.MODE_PRIVATE)
    private val originalName = "Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.x264-GRP.mkv"

    private fun gate() {
        assumeTrue("One-shot explicitly authorized public provider fixture only",
            InstrumentationRegistry.getArguments().getString("chunks_real_media") == "authorized")
        val result = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
            .let { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() } }
        assertEquals("Disposable emulator, not owner's real device", "1", result)
    }
    private class Observer(private val saved: ProxySelector?, private val offline: Boolean) : ProxySelector() {
        val uris = Collections.synchronizedList(mutableListOf<URI>())
        override fun select(uri: URI): List<Proxy> {
            if (uri.scheme == "https") {
                uris.add(uri)
                if (offline) throw IllegalStateException("Offline installed artwork attempted outbound HTTPS")
            }
            return saved?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            saved?.connectFailed(uri, sa, ioe)
        }
    }
    private fun hash() = requireNotNull(prefs.getString("hash", null))
    private fun key() = MediaTransferKey.of(UnifiedTransferKey.of(EnvironmentSnapshots.LOCAL, hash()))
    private fun exactAssignment() = FileTransferMediaAssignmentStore(File(context.filesDir, "media/assignments.json")).get(key())
    private fun displayed(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun openCanonicalTransfers() {
        rule.waitUntil(25_000) {
            rule.onAllNodes(hasTestTag(ChunksSemantics.FOOTER_TRANSFERS)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
        // Local-only canonical Transfers has no All chip. Its search appears
        // after the real stopped fixture restores, instead of Welcome chrome.
        rule.waitUntil(30_000) {
            rule.onAllNodes(hasSetTextAction() and hasText("Search names or hashes"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun search(value: String) {
        rule.onNodeWithText("Search names or hashes").performTextReplacement(value)
        instrumentation.runOnMainSync {
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity?.window?.decorView?.windowToken, 0)
        }
    }
    private var activity: MainActivity? = null
    private fun pixels(phase: String) {
        val found = rule.onAllNodes(hasContentDescription("Artwork: Babylon 5", substring = true),
            useUnmergedTree = true)
        rule.waitUntil(25_000) { found.fetchSemanticsNodes().isNotEmpty() }
        val bitmap = requireNotNull(media.controller.observe(key(), originalName).value.artwork) {
            "Canonical row must show the verified provider bitmap"
        }
        val tile = found.onFirst().captureToImage().asAndroidBitmap()
        val expected = fitArtworkForPixelCheck(bitmap, tile.width, tile.height)
        var total = 0L
        for (y in 3..6) for (x in 3..6) {
            val actual = tile.getPixel(tile.width * x / 10, tile.height * y / 10)
            val reference = expected.getPixel(tile.width * x / 10, tile.height * y / 10)
            for (shift in listOf(0, 8, 16)) {
                total += kotlin.math.abs(((actual ushr shift) and 255) - ((reference ushr shift) and 255))
            }
        }
        assertTrue("Canonical transfer actually paints the provider JPEG", total / 48 < 45)
        val dir = File(context.getExternalFilesDir(null), "auto-real-media-evidence").apply { mkdirs() }
        File(dir, "$phase.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun phaseA_liveAutomaticArtworkWithoutIdentify() {
        gate()
        RememberedRemoteLogin(context).clear()
        RememberedRemoteProfileRoster(context).clearAll()
        runBlocking { media.clearAllMediaData().join() }
        // Synthetic public TV fixture is explicitly authorized only by this one-shot
        // test flag. No other app or tracker consent is inferred from these settings.
        media.updateSettings(MediaPrivacySettings(
            onlineLookupConsent = true, automaticLookup = false,
            disabledProviders = setOf("tmdb", "wikidata", "anilist", "musicbrainz", "openlibrary")))
        val original = ProxySelector.getDefault()
        val network = Observer(original, offline = false)
        ProxySelector.setDefault(network)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity = it }
                rule.waitUntil(30_000) { displayed("Transfers") }
                // Register a stopped native transfer. It is never started or sent to
                // a remote tracker; merely seeing the fresh inventory causes lookup.
                val engine = LocalEngineHolder.get(context)
                val root = File(context.filesDir, "auto-public-tv-fixture").apply { mkdirs() }
                val file = File(root, originalName).apply {
                    writeBytes(ByteArray(16_384) { (it % 251).toByte() })
                }
                val metainfo = TorrentBuilder().path(file).pieceSize(16_384).setPrivate(true)
                    .addTracker("https://private.invalid/announce")
                    .generate().entry().bencode()
                val added = engine.addStopped(metainfo, root)
                assertTrue("A disposable stopped native job must exist", added is LocalAddResult.AddedStopped)
                val torrentHash = (added as LocalAddResult.AddedStopped).status.infoHash
                assertTrue(prefs.edit().putString("hash", torrentHash).commit())
                assertTrue(engine.saveResumeDataFor(torrentHash, 30_000))
                // A real job makes this the canonical list rather than Welcome.
                // Keep lookup OFF until the row is filtered out: per-row effects
                // cannot be responsible for the later provider request.
                openCanonicalTransfers()
                search("qa-intentionally-no-matching-transfers")
                assertFalse("Filter must hide the fixture before enabling lookup", displayed(originalName))
                assertTrue("No provider HTTP while automatic lookup is off", network.uris.isEmpty())
                media.updateSettings(media.loadSettings().copy(automaticLookup = true))
                // No row is composed while the only backend session enriches
                // this hidden, newly arrived exact-key native inventory.
                assertFalse("Filter must still hide the newly observed row", displayed(originalName))
                rule.waitUntil(90_000) {
                    runBlocking(Dispatchers.IO) {
                        exactAssignment()?.let { a ->
                            a.state == MediaAssignmentState.AUTOMATIC && a.match?.ref?.provider == "tvmaze"
                        } == true
                    }
                }
                assertEquals("475", exactAssignment()?.match?.ref?.externalId)
                rule.waitUntil(60_000) {
                    File(context.cacheDir, "media-art").listFiles().orEmpty()
                        .any { it.name.endsWith(".img") && it.length() > 64 }
                }
                assertFalse("Image was fetched while row remained offscreen", displayed(originalName))
                val requests = synchronized(network.uris) { network.uris.toList() }
                assertTrue("Real TVmaze metadata + image-CDN HTTP required", requests.size >= 2)
                assertTrue(requests.any { it.host == "api.tvmaze.com" })
                assertTrue(requests.any { it.host == "static.tvmaze.com" })
                requests.forEach { uri ->
                    val decoded = java.net.URLDecoder.decode(uri.toString(), "UTF-8")
                    assertTrue("Only authorized public TVmaze hosts", uri.host in setOf("api.tvmaze.com", "static.tvmaze.com"))
                    listOf("1080p", "WEB-DL", "x264", "GRP", ".mkv", torrentHash,
                        "announce", "private.invalid").forEach { token ->
                        assertFalse("Forbidden release/transfer data in provider request", decoded.contains(token, true))
                    }
                }
                // Reveal the already cached poster only AFTER independent
                // offscreen provider/assignment/cache assertions have passed.
                search("")
                rule.waitUntil(25_000) { displayed(originalName) }
                pixels("online")
                // Details should reuse the same match without an Identify tap.
                search(torrentHash)
                rule.onNode(hasContentDescription("Details for $originalName", substring = true))
                    .performScrollTo().performClick()
                rule.onNodeWithText("Artwork & media information").assertDoesNotExist()
        rule.onNodeWithText("Choose artwork").assertExists()
                rule.waitUntil(30_000) { media.controller.observe(key(), originalName).value.poster != null }
                assertTrue(prefs.edit().putInt("pid", android.os.Process.myPid()).commit())
                android.util.Log.i("ChunksAutoMedia", "CHUNKS_AUTO_PUBLIC phase=online consent=true automatic=true verified=true row_pixels=true details_poster=true sanitized=true")
            }
        } finally { ProxySelector.setDefault(original) }
    }

    @Test fun phaseB_offlineColdProcessRestoresRealArtwork() {
        gate()
        assertTrue("Live phase must complete first", prefs.contains("pid"))
        assertNotEquals("A new Android process is required", prefs.getInt("pid", 0), android.os.Process.myPid())
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        rule.waitUntil(20_000) { cm.activeNetwork == null }
        assertEquals("A provider-backed match must already exist on disk before opening any row",
            MediaAssignmentState.AUTOMATIC, exactAssignment()?.state)
        val original = ProxySelector.getDefault()
        val network = Observer(original, offline = true)
        ProxySelector.setDefault(network)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity = it }
                openCanonicalTransfers()
                rule.waitUntil(30_000) { displayed(originalName) }
                val restored = requireNotNull(LocalEngineHolder.current()?.status(hash()))
                assertTrue("Canonical cold restore never starts payload transfer", restored.stopped)
                assertEquals("Restoration downloads no payload", 0L, restored.payloadDownloadedBytes)
                assertArrayEquals("Cold restore preserves the original fixture payload",
                    ByteArray(16_384) { (it % 251).toByte() },
                    File(context.filesDir, "auto-public-tv-fixture/$originalName").readBytes())
                rule.waitUntil(40_000) {
                    runBlocking(Dispatchers.IO) { exactAssignment()?.state == MediaAssignmentState.AUTOMATIC }
                }
                pixels("offline")
                assertEquals("475", exactAssignment()?.match?.ref?.externalId)
                search(hash())
                rule.onNode(hasContentDescription("Details for $originalName", substring = true))
                    .performScrollTo().performClick()
                rule.onNodeWithText("Artwork & media information").assertDoesNotExist()
        rule.onNodeWithText("Choose artwork").assertExists()
                rule.waitUntil(30_000) { media.controller.observe(key(), originalName).value.poster != null }
                assertTrue("Offline list + full Details must make zero HTTPS requests", network.uris.isEmpty())
                android.util.Log.i("ChunksAutoMedia", "CHUNKS_AUTO_PUBLIC phase=offline new_process=true persisted=true row_pixels=true requests=0")
            }
        } finally {
            ProxySelector.setDefault(original)
            LocalEngineHolder.current()?.let { engine ->
                prefs.getString("hash", null)?.let { engine.removeKeepingData(it) }
            }
            runBlocking { media.clearAllMediaData().join() }
            media.updateSettings(MediaPrivacySettings())
            prefs.edit().clear().commit()
        }
    }
    /** Public lookup remains live across a second, explicitly excluded Add. */
    @Test fun phaseC_liveSecondAddOptOutStaysSilent() {
        gate()
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        rule.waitUntil(30_000) { cm.activeNetwork != null }
        media.updateSettings(MediaPrivacySettings(
            showArtwork = true, downloadArtwork = true, onlineLookupConsent = true, automaticLookup = true,
            disabledProviders = setOf("tmdb", "wikidata", "anilist", "musicbrainz", "openlibrary")
        ))
        val original = ProxySelector.getDefault()
        val network = Observer(original, offline = false)
        val add = InstalledUniversalAddJourney(rule)
        val hashes = mutableListOf<String>()
        fun fixture(name: String): Pair<Intent, String> {
            val raw = ("d4:infod6:lengthi5e4:name${name.length}:$name" +
                "12:piece lengthi16384e6:pieces20:012345678901234567897:privatei1eee").toByteArray()
            val hash = TorrentMetainfoReader.preview(raw).infoHashV1
            hashes += hash
            val uri = OwnedDiagnosticExportProvider.publish(context, "public-${hash.take(12)}.torrent", raw)
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, "application/x-bittorrent")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return intent to hash
        }
        fun assignment(hash: String): TransferMediaAssignment? =
            FileTransferMediaAssignmentStore(File(context.filesDir, "media/assignments.json"))
                .get(MediaTransferKey.of(UnifiedTransferKey.of(EnvironmentSnapshots.LOCAL, hash)))
        fun searches(): List<String> = synchronized(network.uris) {
            network.uris.filter { it.host == "api.tvmaze.com" && it.path == "/search/shows" }
                .map { java.net.URLDecoder.decode(it.rawQuery.orEmpty(), "UTF-8") }
        }
        fun waitForProvider(hash: String, title: String, expectedId: String) {
            try {
                rule.waitUntil(45_000) {
                    assignment(hash)?.let {
                        it.state == MediaAssignmentState.AUTOMATIC && it.match?.ref?.provider == "tvmaze" &&
                            it.match?.ref?.externalId == expectedId
                    } == true
                }
            } catch (timeout: ComposeTimeoutException) {
                val attempted = searches().any { it.equals("q=$title", true) }
                val assigned = assignment(hash)?.state?.name ?: "none"
                throw AssertionError("Public positive control did not settle; " +
                    "requestAttempted=$attempted assignmentState=$assigned online=${cm.activeNetwork != null}", timeout)
            }
        }
        fun submittedStopped(scenario: ActivityScenario<MainActivity>, hash: String, optOut: Boolean) {
            rule.waitUntil(30_000) {
                var reviewed = false
                scenario.onActivity {
                    reviewed = ViewModelProvider(it)[LocalAddAttemptOwner::class.java]
                        .reviewedTorrent.value?.preview?.infoHashV1 == hash
                }
                reviewed
            }
            assertNull("Opening a source cannot register it", LocalEngineHolder.current()?.status(hash))
            add.chooseStopped()
            val disclosure = hasText("Artwork & metadata") and hasClickAction()
            if (rule.onAllNodes(disclosure and hasStateDescription("Expanded")).fetchSemanticsNodes().isEmpty())
                LazyComposeActions.activateOnce(rule, disclosure and hasStateDescription("Collapsed"))
            val artwork = hasTestTag(ChunksSemantics.ADD_ARTWORK_CHOICE)
            rule.onNode(artwork).performScrollTo()
            assertEquals(ToggleableState.On,
                rule.onNode(artwork).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
            if (optOut) {
                add.activate(ChunksSemantics.ADD_ARTWORK_CHOICE)
                assertEquals(ToggleableState.Off,
                    rule.onNode(artwork).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
            }
            add.submit()
            rule.waitUntil(35_000) { LocalEngineHolder.current()?.status(hash)?.stopped == true }
            assertEquals(0L, requireNotNull(LocalEngineHolder.current()?.status(hash)).payloadDownloadedBytes)
        }
        fun next(intent: Intent) {
            add.activate(ChunksSemantics.FOOTER_ADD)
            add.activate(ChunksSemantics.ADD_ANOTHER)
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        ProxySelector.setDefault(network)
        try {
            // Public search for "Lost" returns several exactly named shows.
            // Distinct series keep this a consent test rather than a ranking test.
            val (firstIntent, first) = fixture("Stargate.Atlantis.S01E01.mkv")
            val (excludedIntent, excluded) = fixture("Fringe.S02E07.mkv")
            val (controlIntent, control) = fixture("Firefly.S01E01.mkv")
            assertTrue("The opted-out fixture must be eligible for television metadata",
                media.controller.recognize("Fringe.S02E07.mkv").kind.isTv)
            ActivityScenario.launch<MainActivity>(firstIntent).use { scenario ->
                try {
                    submittedStopped(scenario, first, optOut = false)
                    openCanonicalTransfers()
                    waitForProvider(first, "Stargate Atlantis", "206")
                    next(excludedIntent)
                    submittedStopped(scenario, excluded, optOut = true)
                    openCanonicalTransfers()
                    val key = MediaTransferKey.of(UnifiedTransferKey.of(EnvironmentSnapshots.LOCAL, excluded))
                    assertTrue(FileMediaAutomationPreferences(File(context.filesDir, "media/automation.json"))
                        .isExcluded(key))
                    next(controlIntent)
                    submittedStopped(scenario, control, optOut = false)
                    openCanonicalTransfers()
                    waitForProvider(control, "Firefly", "180")
                    assertNull("An excluded Add cannot have a media assignment", assignment(excluded))
                    val requests = synchronized(network.uris) { network.uris.toList() }
                    val searches = searches()
                    assertTrue("Both public positive controls dispatched real lookups",
                        searches.any { it.equals("q=Stargate Atlantis", true) } && searches.any { it.equals("q=Firefly", true) })
                    assertFalse("Excluded title must never reach TVmaze",
                        searches.any { it.equals("q=Fringe", true) })
                    assertFalse("Excluded identity must never enter episode enrichment",
                        requests.any { it.host == "api.tvmaze.com" && it.path.startsWith("/shows/158/") })
                    android.util.Log.i("ChunksAutoMedia",
                        "CHUNKS_AUTO_PUBLIC phase=second_add positive_controls=2 excluded_provider_requests=0 exact_disk_exclusion=true")
                } finally {
                    // ActivityScenario matches close by the original Intent.
                    // Restore the monitor's launch identity after production has handled both new intents.
                    scenario.onActivity { it.intent = firstIntent }
                }
            }
        } finally {
            ProxySelector.setDefault(original)
            LocalEngineHolder.current()?.let { engine -> hashes.forEach { hash -> engine.removeKeepingData(hash) } }
            runBlocking { media.clearAllMediaData().join() }
            media.updateSettings(MediaPrivacySettings())
        }
    }

}
