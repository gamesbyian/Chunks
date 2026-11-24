package io.github.gamesbyian.chunks

import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticExportProvider
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.media.*
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteReadAuthEvidence
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
 * own Connections form and opens Transfers. The staged job remains untouched.
 * Three separately confirmed disposable Adds exercise the real provider and
 * the exact second-Add exclusion across cold authenticated reconnect. All jobs
 * stay stopped with no payload. TVmaze metadata and CDN are observed, not replaced.
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

    private fun launchChunks() {
        // The host force-stops the real app before phase B. Do not ask
        // ActivityScenario to destroy a singleTask activity as a separate oracle.
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun stage(name: String) =
        android.util.Log.i("ChunksRemoteMedia", "CHUNKS_REMOTE_MEDIA_STAGE $name")

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
        require(url == "http://127.0.0.1:18082/RPC2") { "disposable loopback server only" }
        val credentials = RemoteCredentials(url, "chunks", "chunks-media")
        val remote = RTorrentRemote(credentials)
        val before = runBlocking { remote.version(); remote.listCompleteSessionTransfers() }
        assertEquals(RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED, remote.readAuthenticationEvidence())
        val fixture = before.single { it.hash.equals(hash, ignoreCase = true) }
        assertEquals(originalName, fixture.name)
        assertFalse("The remote fixture is staged stopped", fixture.started)

        val environment = requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(
            RemoteVerifiedUiIdentity.aliasScopeOf(credentials)))
        val key = MediaTransferKey.of(UnifiedTransferKey.of(environment, hash))
        // A store instance caches its file when constructed: read through a fresh one each
        // time (as the local proof does), or a later automatic assignment is never seen.
        val assignments = object {
            fun get(k: MediaTransferKey) =
                FileTransferMediaAssignmentStore(File(context.filesDir, "media/assignments.json")).get(k)
        }

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
            val add = InstalledUniversalAddJourney(rule)
            run {
                launchChunks()
                stage("launched")
                waitFor("persistent Connections destination") { visible("Connections") }
                rule.onNodeWithContentDescription("Connections").performClick()
                rule.openConnectionFormIfNeeded(allowConnected = false)
                rule.openConnectionFormIfNeeded()
            waitFor("connection form") {
                    runCatching { scrollTo("Seedbox hostname, ruTorrent URL or RPC address"); true }
                        .getOrDefault(false)
                }
                typeInto("Connection name (optional)", "Media box")
                typeInto("Seedbox hostname, ruTorrent URL or RPC address", url)
                typeInto("Username, if required", credentials.username)
                typeInto("Password", credentials.password)
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
                stage("authenticated")
                rule.onNodeWithText("Continue to Connections").performClick()
                waitFor("connected card") {
                    runCatching { scrollTo("Connected") }
                    visible("Connected") && visible("View transfers")
                }
                rule.onNodeWithTag(ChunksSemantics.CONNECTION_VIEW_TRANSFERS).performClick()
                waitFor("remote fixture row in canonical Transfers", 60_000) {
                    runCatching { scrollTo(originalName); true }.getOrDefault(false)
                }
                // Merely observing the remote inventory must identify it automatically.
                try {
                    rule.waitUntil(90_000) {
                        runBlocking(Dispatchers.IO) {
                            assignments.get(key)?.let { a ->
                                a.state == MediaAssignmentState.AUTOMATIC && a.match?.ref?.provider == "tvmaze"
                            } == true
                        }
                    }
                } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                    // Disposable loopback fixture with a public title: safe, specific diagnostics.
                    val hosts = synchronized(network.uris) { network.uris.map { it.host } }.groupingBy { it }.eachCount()
                    val observed = runCatching { media.controller.observe(key, originalName).value.toString() }
                        .getOrDefault("unavailable").take(800)
                    throw AssertionError("No automatic TVmaze assignment for the remote key " +
                        "env=${environment.value.take(12)} hash=$hash; https hosts=$hosts; " +
                        "assignment=${runBlocking(Dispatchers.IO) { assignments.get(key) }}; observed=$observed; " +
                        "settings=${media.loadSettings()}; cache=${runCatching { media.cacheSummary() }.getOrNull()}",
                        failure)
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
                val expected = fitArtworkForPixelCheck(bitmap, painted.width, painted.height)
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

                stage("baseline-online")
                val first = openSource("Stargate.Atlantis.S01E01.mkv")
                add.activate(ChunksSemantics.ADD_TARGET_REMOTE)
                submitStopped(remote, add, first, optOut = false)
                stage("first-stopped")
                add.activate(ChunksSemantics.ADD_ANOTHER)
                val excluded = openSource("Fringe.S02E07.mkv")
                submitStopped(remote, add, excluded, optOut = true)
                stage("second-stopped")
                val excludedKey = MediaTransferKey.of(UnifiedTransferKey.of(environment, excluded))
                fun isExcluded(key: MediaTransferKey) =
                    FileMediaAutomationPreferences(File(context.filesDir, "media/automation.json")).isExcluded(key)
                assertTrue("Explicit opt-out must be durable before reconnect", isExcluded(excludedKey))
                assertFalse("The same hash on Local must not inherit the exclusion",
                    isExcluded(MediaTransferKey.of(UnifiedTransferKey.of(EnvironmentSnapshots.LOCAL, excluded))))

                // Read and enrich the full authenticated account before closing it.
                add.activate(ChunksSemantics.FOOTER_TRANSFERS)
                val firstKey = MediaTransferKey.of(UnifiedTransferKey.of(environment, first))
                rule.waitUntil(60_000) { assignments.get(firstKey)?.match?.ref?.externalId == "206" }
                assertNoFringeLookup(network)
                assertNull(assignments.get(excludedKey))
                stage("phase-a-verified")
                assertTrue(context.getSharedPreferences("remote-media-qa", 0).edit()
                    .putString("excluded", excluded).putInt("phase-a-pid", android.os.Process.myPid()).commit())
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
    private fun openSource(name: String): String {
        // The daemon deliberately has DHT disabled. Only an inert loopback tracker is supplied.
        val tracker = "http://127.0.0.1:1/announce"
        val raw = ("d8:announce${tracker.length}:$tracker" +
            "4:infod6:lengthi5e4:name${name.length}:$name" +
            "12:piece lengthi16384e6:pieces20:012345678901234567897:privatei1eee").toByteArray()
        val hash = TorrentMetainfoReader.preview(raw).infoHashV1
        val uri = OwnedDiagnosticExportProvider.publish(context, "remote-${hash.take(12)}.torrent", raw)
        context.startActivity(Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java).apply {
            setDataAndType(uri, "application/x-bittorrent")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        return hash
    }

    private fun submitStopped(remote: RTorrentRemote, add: InstalledUniversalAddJourney, hash: String, optOut: Boolean) {
        val scroller = hasTestTag(ChunksSemantics.ADD_BODY_RTORRENT)
        rule.waitUntil(40_000) {
            runCatching {
                rule.onNode(scroller).performScrollToNode(hasText("Infohash: $hash", substring = true))
                visible("Infohash: $hash")
            }.getOrDefault(false)
        }
        assertNull("Review sends no Add", runBlocking { remote.lookupName(hash) })
        add.chooseStopped()
        val disclosure = hasText("Artwork & metadata") and hasClickAction()
        rule.onNode(scroller).performScrollToNode(disclosure)
        if (rule.onAllNodes(disclosure and hasStateDescription("Expanded")).fetchSemanticsNodes().isEmpty())
            LazyComposeActions.activateOnce(rule, disclosure and hasStateDescription("Collapsed"))
        val choice = hasTestTag(ChunksSemantics.ADD_ARTWORK_CHOICE)
        fun awaitChoice(state: ToggleableState) {
            rule.waitUntil(25_000) {
                runCatching {
                    rule.onNode(scroller).performScrollToNode(choice)
                    rule.onAllNodes(choice).fetchSemanticsNodes().singleOrNull()?.config
                        ?.getOrNull(SemanticsProperties.ToggleableState) == state
                }.getOrDefault(false)
            }
        }
        awaitChoice(ToggleableState.On)
        if (optOut) {
            add.activate(ChunksSemantics.ADD_ARTWORK_CHOICE)
            awaitChoice(ToggleableState.Off)
        }
        add.submit()
        rule.waitUntil(45_000) {
            runBlocking { remote.listCompleteSessionTransfers().any { it.hash.equals(hash, true) } }
        }
        val registered = runBlocking { remote.listCompleteSessionTransfers().single { it.hash.equals(hash, true) } }
        assertFalse("Real daemon must keep this exact Add stopped", registered.started)
        assertEquals(0L, registered.completedBytes)
        assertNull("The selected remote source must not register on Local", LocalEngineHolder.current()?.status(hash))
        val next = hasTestTag(ChunksSemantics.ADD_ANOTHER)
        rule.waitUntil(45_000) {
            runCatching {
                if (rule.onAllNodes(next).fetchSemanticsNodes().isEmpty()) rule.onNode(scroller).performScrollToNode(next)
                rule.onAllNodes(next).fetchSemanticsNodes().size == 1
            }.getOrDefault(false)
        }
    }

    private fun assertNoFringeLookup(network: Observer) {
        val searched = synchronized(network.uris) {
            network.uris.any { it.host == "api.tvmaze.com" && it.path == "/search/shows" &&
                java.net.URLDecoder.decode(it.rawQuery.orEmpty(), "UTF-8").equals("q=Fringe", true) }
        }
        assertFalse("Excluded remote transfer made an outbound provider selection", searched)
    }

    @Test fun coldReconnectKeepsRemoteOptOutWhileAnotherLookupWorks() {
        assumeTrue(args.getString("chunks_remote_media") == "authorized")
        val url = decoded("chunks_remote_media_url_b64")
        require(url == "http://127.0.0.1:18082/RPC2")
        val credentials = RemoteCredentials(url, "chunks", "chunks-media")
        val remote = RTorrentRemote(credentials)
        val saved = context.getSharedPreferences("remote-media-qa", 0)
        val excluded = requireNotNull(saved.getString("excluded", null))
        runBlocking { remote.version() }
        assertEquals(RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED, remote.readAuthenticationEvidence())
        assertNotEquals("Cold reconnect requires a new Android process",
            saved.getInt("phase-a-pid", 0), android.os.Process.myPid())
        val environment = requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(
            RemoteVerifiedUiIdentity.aliasScopeOf(credentials)))
        val excludedKey = MediaTransferKey.of(UnifiedTransferKey.of(environment, excluded))
        fun assignments() = FileTransferMediaAssignmentStore(File(context.filesDir, "media/assignments.json"))
        fun excludedOnDisk() = FileMediaAutomationPreferences(File(context.filesDir, "media/automation.json"))
            .isExcluded(excludedKey)
        assertTrue(excludedOnDisk())

        val original = ProxySelector.getDefault()
        val network = Observer(original)
        ProxySelector.setDefault(network)
        try {
            run {
                launchChunks()
                val add = InstalledUniversalAddJourney(rule)
                waitFor("cold Connections entry") {
                    rule.onAllNodesWithTag(ChunksSemantics.FOOTER_CONNECTIONS).fetchSemanticsNodes().size == 1
                }
                add.activate(ChunksSemantics.FOOTER_CONNECTIONS)
                LazyComposeActions.activateOnce(rule, hasTestTag(ChunksSemantics.CONNECTION_VIEW_TRANSFERS))
                waitFor("excluded transfer observed through the reconnected account") {
                    runCatching { scrollTo("Fringe.S02E07.mkv"); visible("Fringe.S02E07.mkv") }.getOrDefault(false)
                }
                assertTrue(excludedOnDisk())
                assertNull(assignments().get(excludedKey))
                assertNoFringeLookup(network)

                stage("reconnected")
                val control = openSource("Firefly.S01E01.mkv")
                val remoteTarget = hasTestTag(ChunksSemantics.ADD_TARGET_REMOTE) and !isSelected()
                if (rule.onAllNodes(remoteTarget).fetchSemanticsNodes().isNotEmpty())
                    LazyComposeActions.activateOnce(rule, remoteTarget)
                else if (rule.onAllNodesWithTag(ChunksSemantics.ADD_BODY_RTORRENT).fetchSemanticsNodes().isEmpty())
                    add.activate(ChunksSemantics.ADD_TARGET_REMOTE)
                submitStopped(remote, add, control, optOut = false)
                add.activate(ChunksSemantics.FOOTER_TRANSFERS)
                val controlKey = MediaTransferKey.of(UnifiedTransferKey.of(environment, control))
                rule.waitUntil(60_000) { assignments().get(controlKey)?.match?.ref?.externalId == "180" }
                assertTrue(excludedOnDisk())
                assertNull(assignments().get(excludedKey))
                assertNoFringeLookup(network)
                val stopped = runBlocking { remote.listCompleteSessionTransfers().single { it.hash.equals(excluded, true) } }
                assertFalse(stopped.started)
                assertEquals(0L, stopped.completedBytes)
            }
        } finally {
            ProxySelector.setDefault(original)
        }
        android.util.Log.i("ChunksRemoteMedia",
            "CHUNKS_REMOTE_PUBLIC_MEDIA reconnect=true exact_exclusion=true provider_positive=true excluded_selections=0")
    }

}
