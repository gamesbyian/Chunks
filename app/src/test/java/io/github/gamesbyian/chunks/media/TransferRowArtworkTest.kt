package io.github.gamesbyian.chunks.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import io.github.gamesbyian.chunks.visual.smallTouchTargets
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.LocalTransferCard
import io.github.gamesbyian.chunks.TransferRowArtwork
import io.github.gamesbyian.chunks.TransferRowArtworkSize
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.media.artwork.ArtworkDiskCache
import io.github.gamesbyian.chunks.media.artwork.ArtworkRepository
import io.github.gamesbyian.chunks.media.providers.TvMazeProvider
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.localTransferKey
import io.github.gamesbyian.chunks.remoteTransferKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * List-row artwork integration: exact environment identity, original-name recognition, no network
 * from display, privacy withholding, settings that act on visible rows, and compact row geometry at
 * 320dp/200% text. JVM/Robolectric with recorded TVmaze fixtures; not installed-device proof.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-xxhdpi")
class TransferRowArtworkTest {
    @get:Rule val rule = createComposeRule()

    private val clock = FakeClock()
    private var settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
    private val dir: File = Files.createTempDirectory("row-media").toFile()
    private val hash = "c".repeat(40)
    private val name = "Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.mkv"
    private val local = UnifiedTransferKey.of(EnvironmentId("local"), hash)
    private val server = UnifiedTransferKey.of(EnvironmentId("rtorrent:seedbox-2"), hash)

    private fun jpeg(): ByteArray {
        val b = Bitmap.createBitmap(680, 1000, Bitmap.Config.ARGB_8888); Canvas(b).drawColor(Color.rgb(30, 60, 160))
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private val http = FakeMediaHttp().on("/search/shows", body = fixture("tvmaze-search-babylon5.json"))
        .on("/shows/475/episodebynumber?season=2&number=7", body = fixture("tvmaze-episode-475-s02e07.json"))
        .on("/shows/57926/episodebynumber", status = 404)
        .onBytes("static.tvmaze.com", jpeg(), headers = mapOf("Content-Type" to "image/jpeg"))

    private fun controller(): TransferMediaController {
        val tv = TvMazeProvider()
        val service = MediaEnrichmentService(listOf(tv), http, FileMediaQueryCache(File(dir, "q.json")), { clock.now }, clock::sleep)
        return TransferMediaController(service,
            ArtworkRepository(http, ArtworkDiskCache(File(dir, "art")), hostPolicyFor = { tv.info.artworkHostPolicy }, memoryBytes = 4_000_000),
            { FileTransferMediaAssignmentStore(File(dir, "assign.json")) }, { settings }, { MediaNetworkState(true, false) },
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, Dispatchers.Unconfined, { clock.now })
    }

    private val format = "Name suggests .mkv; actual file type not verified"

    @Test fun openDetailsRequestsPosterWhenSameVerifiedWorkLaterGainsArt() {
        val mediaKey = MediaTransferKey.of(server)
        val verified = MediaCandidate(
            io.github.gamesbyian.chunks.model.MediaMetadataCacheKey("wikidata", "item", "Q12345"),
            MediaKind.MOVIE, "Hackers", 1995,
            attribution = MediaAttribution("Wikidata", "CC0", null))
        val poster = ArtworkCandidate("tmdb", "https://image.tmdb.org/t/p/w500/hackers.jpg", ArtworkRole.POSTER)
        var state by androidx.compose.runtime.mutableStateOf(
            TransferMediaUiState(mediaKey, MediaRecognizer.recognize("Hackers.1995.mkv"),
                assignment = TransferMediaAssignment(mediaKey, MediaAssignmentState.AUTOMATIC, match = verified)))
        var requested = 0
        rule.setContent {
            ChunksTheme {
                Surface {
                    io.github.gamesbyian.chunks.media.ui.MediaMetadataSummary(
                        state, onChange = {}, onRequestPoster = { requested++ })
                }
            }
        }
        rule.waitForIdle()
        assertEquals("No poster candidate: don't perform a redundant Details request", 0, requested)
        rule.runOnUiThread {
            state = state.copy(assignment = state.assignment!!.copy(
                match = verified.copy(artwork = listOf(poster))))
        }
        rule.waitForIdle()
        assertEquals("Same verified identity with newly arrived poster must wake open Details", 1, requested)
        rule.runOnUiThread {
            state = state.copy(automaticMessage = "Artwork found for verified identity")
        }
        rule.waitForIdle()
        assertEquals("Unrelated status changes must not repeatedly fetch art", 1, requested)
        rule.runOnUiThread {
            state = state.copy(poster = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888))
        }
        rule.waitForIdle()
        assertEquals("Painting the loaded poster must not re-fetch it", 1, requested)
    }

    @Test fun rowsShowTheExactEnvironmentsAssignmentWithoutAnyNetworkFromDisplay() {
        val c = controller()
        c.observe(MediaTransferKey.of(local), name); c.lookup(MediaTransferKey.of(local))
        val requestsAfterLookup = http.requests.size
        rule.setContent {
            CompositionLocalProvider(LocalTransferMediaController provides c) {
                ChunksTheme { Surface {
                    androidx.compose.foundation.layout.Column {
                        TransferRowArtwork(local, name)
                        // Same infohash on a server is a different transfer: category art only.
                        TransferRowArtwork(server, name)
                    }
                } }
            }
        }
        rule.onNodeWithContentDescription("Artwork: Babylon 5 (automatic). $format").assertExists()
            .assertWidthIsEqualTo(TransferRowArtworkSize).assertHeightIsEqualTo(TransferRowArtworkSize)
        rule.onNodeWithContentDescription("TV episode category artwork. $format").assertExists()
        assertEquals("Displaying rows must not issue provider or image requests", requestsAfterLookup, http.requests.size)
    }

    @Test fun privacyHiddenOrAmbiguousRowsNeverShowIdentifyingArtwork() {
        val c = controller()
        c.observe(MediaTransferKey.of(local), name); c.lookup(MediaTransferKey.of(local))
        rule.setContent {
            CompositionLocalProvider(LocalTransferMediaController provides c) {
                ChunksTheme { Surface {
                    androidx.compose.foundation.layout.Column {
                        TransferRowArtwork(local, name, hidden = true)
                        TransferRowArtwork(null, "Other.Release.mkv")
                    }
                } }
            }
        }
        rule.onNodeWithContentDescription("Babylon", substring = true).assertDoesNotExist()
        assertEquals(2, rule.onAllNodesWithText("MKV").fetchSemanticsNodes().size)
        assertNull(remoteTransferKey(null, hash))
        assertNull(localTransferKey("not-a-hash"))
        assertEquals(server, remoteTransferKey("rtorrent:seedbox-2", hash.uppercase()))
    }

    @Test fun turningArtworkOffAppliesToVisibleRowsAndBackOnReloadsFromCacheOnly() {
        val c = controller()
        val flow = c.observe(MediaTransferKey.of(local), name); c.lookup(MediaTransferKey.of(local))
        assertNotNull(flow.value.artwork)
        settings = settings.copy(showArtwork = false); c.onSettingsChanged()
        assertNull(flow.value.artwork)
        val before = http.requests.size
        settings = settings.copy(showArtwork = true, downloadArtwork = false); c.onSettingsChanged()
        assertNotNull("Re-enabled artwork comes from the local cache", flow.value.artwork)
        assertEquals(before, http.requests.size)
        assertEquals(MediaAssignmentState.AUTOMATIC, flow.value.state)
    }

    @Test fun localCardKeepsCompactRowAndOffersIdentificationAt200PercentText() {
        val c = controller()
        c.observe(MediaTransferKey.of(local), name); c.lookup(MediaTransferKey.of(local))
        val transfer = LocalTransferStatus(infoHash = hash, name = name, state = LocalTransferState.DOWNLOADING,
            stopped = false, progressPpm = 420_000, wantedBytes = 1_000_000, doneBytes = 420_000,
            payloadDownloadedBytes = 430_000, payloadUploadedBytes = 0, peers = 3, savePath = "/local", error = null)
        var mutations = 0
        rule.setContent {
            CompositionLocalProvider(LocalTransferMediaController provides c,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                ChunksTheme { Surface {
                    androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                        LocalTransferCard(transfer = transfer, busy = false, alias = "My show",
                            onToggle = { mutations++ }, onRemove = { mutations++ }, files = emptyList(),
                            onShowFiles = { mutations++ })
                    }
                } }
            }
        }
        rule.onNodeWithContentDescription("Artwork: Babylon 5 (automatic). $format").assertExists()
            .assertWidthIsEqualTo(40.dp)
        rule.onRoot().captureRoboImage("build/chunks-visual/media-row-local-320dp-200pct.png")
        rule.onNodeWithContentDescription("More details for $name on this device").performScrollTo().performClick()
        rule.onNodeWithText("Artwork & media information").performScrollTo().assertExists()
        rule.onNodeWithText("Change identification or artwork…").performScrollTo().performClick()
        rule.onNodeWithText("Identify this transfer").performScrollTo().assertExists()
        rule.onNodeWithText("Reset to automatic").performScrollTo().assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/media-identify-local-320dp-200pct.png")
        assertEquals("Media actions never toggle, remove or open files", 0, mutations)
        assertTrue(http.requests.none { it.contains("My show") || it.contains("My%20show") })
    }

    private fun obs(key: UnifiedTransferKey, name: String) = io.github.gamesbyian.chunks.model.UnifiedTransferObservation(
        key, name, name, 1_000, 500_000, true, 0, 0, 1)
    private fun snapshot(env: EnvironmentId, locked: Boolean, vararg rows: io.github.gamesbyian.chunks.model.UnifiedTransferObservation) =
        io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot(env,
            if (locked) io.github.gamesbyian.chunks.model.EnvironmentAvailability.LOCKED else io.github.gamesbyian.chunks.model.EnvironmentAvailability.LIVE,
            true, clock.now, if (locked) emptyList() else rows.toList())

    @Test fun similarTransfersUseOnlyConfirmedIdsFromAuthorizedInventoryAndOpenTheExactTarget() {
        val c = controller()
        val otherHash = "d".repeat(40)
        val serverOther = UnifiedTransferKey.of(EnvironmentId("rtorrent:seedbox-2"), otherHash)
        val lockedEnv = EnvironmentId("rtorrent:seedbox-3")
        val lockedKey = UnifiedTransferKey.of(lockedEnv, "e".repeat(40))
        val unidentified = UnifiedTransferKey.of(EnvironmentId("rtorrent:seedbox-2"), "f".repeat(40))
        // Confirmed identity for the source and one other server transfer; the locked one is
        // identified too, but its source is LOCKED and must contribute nothing.
        listOf(local, serverOther, lockedKey).forEach { k ->
            c.observe(MediaTransferKey.of(k), name); c.lookup(MediaTransferKey.of(k))
        }
        c.observe(MediaTransferKey.of(unidentified), name) // same title, never looked up
        val sources = listOf(
            snapshot(EnvironmentId("local"), false, obs(local, name)),
            snapshot(EnvironmentId("rtorrent:seedbox-2"), false, obs(serverOther, "B5 copy on server"), obs(unidentified, name)),
            snapshot(lockedEnv, true))
        val before = http.requests.size
        var opened: UnifiedTransferKey? = null
        rule.setContent {
            CompositionLocalProvider(LocalTransferMediaController provides c) {
                ChunksTheme { Surface {
                    io.github.gamesbyian.chunks.SimilarTransfersHost(obs(local, name), sources,
                        environmentLabel = { if (it.value == "local") "This local" else "Seedbox 2" },
                        environmentColor = { androidx.compose.ui.graphics.Color.Cyan },
                        onOpen = { opened = it })
                } }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Similar transfers").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("B5 copy on server").assertExists().performClick()
        assertEquals(serverOther, opened)
        assertEquals("Title-only and locked rows never appear", 1,
            rule.onAllNodesWithText("Seedbox 2", substring = true).fetchSemanticsNodes().size)
        assertEquals("Similar Transfers issues no lookup", before, http.requests.size)
        // Settings: this-environment-only drops the server match; off hides the section.
        settings = settings.copy(similarSameEnvironmentOnly = true); c.onSettingsChanged()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("B5 copy on server").fetchSemanticsNodes().isEmpty() }
        settings = settings.copy(similarSameEnvironmentOnly = false, showSimilarTransfers = false); c.onSettingsChanged()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("Similar transfers").fetchSemanticsNodes().size)
    }

    @Test fun localDetailsUseTheTransfersOwnCompletedCoverWithoutAnyNetwork() {
        val c = controller()
        val folder = Files.createTempDirectory("local-save").toFile()
        val album = "Artist - Album (2020) [FLAC]"
        val cover = jpeg()
        File(folder, "$album/cover.jpg").apply { parentFile.mkdirs(); writeBytes(cover) }
        val files = listOf(io.github.gamesbyian.chunks.engine.LocalTransferFile(0, "$album/cover.jpg", cover.size.toLong(), cover.size.toLong(), true),
            io.github.gamesbyian.chunks.engine.LocalTransferFile(1, "$album/01.flac", 30_000_000, 30_000_000, true))
        val transfer = LocalTransferStatus(infoHash = hash, name = album, state = LocalTransferState.SEEDING,
            stopped = false, progressPpm = 1_000_000, wantedBytes = 30_000_000, doneBytes = 30_000_000,
            payloadDownloadedBytes = 0, payloadUploadedBytes = 0, peers = 0, savePath = folder.absolutePath, error = null)
        val before = http.requests.size
        rule.setContent {
            CompositionLocalProvider(LocalTransferMediaController provides c) {
                ChunksTheme { Surface {
                    androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                        LocalTransferCard(transfer = transfer, busy = false, onToggle = {}, onRemove = {},
                            files = files, filesShown = true)
                    }
                } }
            }
        }
        rule.onNodeWithContentDescription("More details for $album on this device").performScrollTo().performClick()
        rule.onNodeWithText("Change identification or artwork…").performScrollTo().performClick()
        rule.onNodeWithText("Use cover.jpg from this transfer").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Your artwork", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(MediaAssignmentState.CHOSEN_BY_USER, c.observe(MediaTransferKey.of(local), album).value.state)
        assertEquals("A local cover never touches the network", before, http.requests.size)
        assertTrue("The payload file is unchanged", File(folder, "$album/cover.jpg").readBytes().contentEquals(cover))
    }

    @Test fun choosingAnotherProviderImageIsAUserChoiceThatSurvivesRestartAndIsReversible() {
        val c = controller()
        val k = MediaTransferKey.of(local)
        val flow = c.observe(k, name); c.lookup(k)
        assertEquals(MediaAssignmentState.AUTOMATIC, flow.value.state)
        val alts = kotlinx.coroutines.runBlocking { c.alternatives(k) }
        assertTrue("Episode matches offer poster and still", alts.size >= 2)
        val pick = alts.last().candidate
        c.chooseAlternative(k, pick)
        assertEquals(MediaAssignmentState.CHOSEN_BY_USER, flow.value.state)
        assertEquals(pick, flow.value.userArtwork)
        assertEquals("The match itself is kept", "Babylon 5", flow.value.match?.title)
        // A different candidate not from this match is refused.
        c.chooseAlternative(k, ArtworkCandidate("tvmaze", "https://static.tvmaze.com/other.jpg", ArtworkRole.POSTER))
        assertEquals(pick, flow.value.userArtwork)
        // New process, offline: the pinned choice is restored from the local cache.
        val before = http.requests.size
        http.offline = true
        val restored = controller().observe(k, name).value
        assertEquals(pick, restored.userArtwork); assertNotNull(restored.artwork)
        assertEquals(before, http.requests.size)
        http.offline = false
        c.removeUserImage(k)
        assertEquals(MediaAssignmentState.AUTOMATIC, flow.value.state)
        assertNull(flow.value.userArtwork)
    }

    @Test fun choosingADetailPosterIsPerTransferSurvivesOfflineRestartAndClearsOnReset() {
        val c = controller()
        val k = MediaTransferKey.of(local)
        val flow = c.observe(k, name); c.lookup(k)
        val base = flow.value.match!!
        val a = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/uploads/images/original_untouched/1/a.jpg", ArtworkRole.POSTER)
        val b = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/uploads/images/original_untouched/1/b.jpg", ArtworkRole.POSTER)
        c.choose(k, base.copy(artwork = listOf(a, b) + base.artwork.filter { it.role != ArtworkRole.POSTER }))
        val posters = kotlinx.coroutines.runBlocking { c.posterAlternatives(k) }
        assertEquals(listOf(a, b), posters.map { it.candidate })
        c.choosePoster(k, b)
        assertEquals(b.url, flow.value.assignment?.userPosterUrl)
        assertNotNull("The chosen poster is loaded for details", flow.value.poster)
        assertNull("List tiles keep their own image", flow.value.userArtwork)
        // Not from this match: refused.
        c.choosePoster(k, ArtworkCandidate("tvmaze", "https://static.tvmaze.com/other.jpg", ArtworkRole.POSTER))
        assertEquals(b.url, flow.value.assignment?.userPosterUrl)
        // New process, offline: restored and loaded from the pinned cache.
        val before = http.requests.size
        http.offline = true
        val c2 = controller()
        val restored = c2.observe(k, name)
        c2.requestPoster(k)
        assertEquals(b.url, restored.value.assignment?.userPosterUrl); assertNotNull(restored.value.poster)
        assertEquals(before, http.requests.size)
        http.offline = false
        c.reset(k)
        assertNull(flow.value.assignment)
        assertNull(controller().observe(k, name).value.assignment?.userPosterUrl)
    }

    @Test fun posterGalleryAppearsOnlyForSeveralPostersAndMarksTheChoice() {
        val st0 = TransferMediaUiState(MediaTransferKey.of(local), MediaRecognizer.recognize(name))
        val a = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/a.jpg", ArtworkRole.POSTER)
        val b = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/b.jpg", ArtworkRole.POSTER)
        val m = MediaCandidate(io.github.gamesbyian.chunks.model.MediaMetadataCacheKey("tvmaze", "show", "475"), MediaKind.TV_SERIES, "Babylon 5",
            artwork = listOf(a, b), attribution = MediaAttribution("TVmaze", "CC BY-SA", null))
        val st = st0.copy(assignment = TransferMediaAssignment(st0.key, MediaAssignmentState.CHOSEN_BY_USER, m, userPosterUrl = b.url))
        val bmp = Bitmap.createBitmap(68, 100, Bitmap.Config.ARGB_8888)
        var picked: ArtworkCandidate? = null
        rule.setContent {
            ChunksTheme { Surface { androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                io.github.gamesbyian.chunks.media.ui.MediaIdentifyPanel(st, settings, onEnableLookup = {}, onLookup = {},
                    onSearch = { _, _, _ -> }, onChoose = {}, onUseCategoryArt = {}, onReset = {},
                    onLoadPosters = {}, posters = listOf(TransferMediaController.ArtworkAlternative(a, bmp), TransferMediaController.ArtworkAlternative(b, bmp)),
                    onChoosePoster = { picked = it })
            } } }
        }
        rule.onNodeWithText("Detail poster").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Poster 2 of 2 from TVmaze").performScrollTo()
            .assertIsSelected()
        rule.onNodeWithContentDescription("Poster 1 of 2 from TVmaze").performScrollTo().performClick()
        assertEquals(a, picked)
    }

    @Test fun galleryShowsOnlyOnRequestAndRespectsDownloadSettings() {
        val c = controller()
        val k = MediaTransferKey.of(local)
        c.observe(k, name); c.lookup(k)
        settings = settings.copy(downloadArtwork = false)
        val cachedOnly = kotlinx.coroutines.runBlocking { c.alternatives(k) }
        assertTrue("Without downloads only already cached images appear", cachedOnly.size <= 1)
        settings = settings.copy(showArtwork = false)
        assertTrue(kotlinx.coroutines.runBlocking { c.alternatives(k) }.isEmpty())
    }

    @Test fun searchableTitlesHoldOnlyConfirmedMatchesAndBumpTheRevision() {
        val c = controller()
        val k = MediaTransferKey.of(local)
        c.observe(k, name)
        val before = c.assignmentRevision.value
        c.lookup(k)
        assertTrue(c.assignmentRevision.value > before)
        assertEquals("Babylon 5 Soul Mates", c.searchableTitles()[k])
        c.useCategoryArt(k, MediaKind.MOVIE)
        assertNull("Category-only choices are not searchable titles", c.searchableTitles()[k])
    }

    @Test fun identifyPanelKindChoicesStayReachableAndTappableAt200Percent() {
        val st = TransferMediaUiState(MediaTransferKey.of(local), MediaRecognizer.recognize(name))
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                ChunksTheme { Surface {
                    androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                        io.github.gamesbyian.chunks.media.ui.MediaIdentifyPanel(st, MediaPrivacySettings(onlineLookupConsent = true),
                            onEnableLookup = {}, onLookup = {}, onSearch = { _, _, _ -> }, onChoose = {},
                            onUseCategoryArt = {}, onReset = {}, onPickImage = {})
                    }
                } }
            }
        }
        for (label in listOf("Movie", "TV series", "Album", "Book"))
            rule.onAllNodesWithText(label).fetchSemanticsNodes().let { assertTrue("$label present", it.isNotEmpty()) }
        rule.onAllNodesWithText("Book")[0].performScrollTo().assertIsDisplayed()
        val density = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        assertEquals(emptyList<String>(), rule.smallTouchTargets(density))
    }

    @Test fun localCardInspectsOnlyTheCompletedMediaFileOnRequest() {
        val folder = Files.createTempDirectory("local-inspect").toFile()
        val mkv = javaClass.classLoader!!.getResource("media-fixtures/embedded/tracks.mkv")!!.readBytes()
        val movie = "Example.Movie.2020.mkv"
        File(folder, movie).writeBytes(mkv)
        fun card(done: Long) = LocalTransferStatus(infoHash = hash, name = movie, state = LocalTransferState.SEEDING,
            stopped = false, progressPpm = 1_000_000, wantedBytes = mkv.size.toLong(), doneBytes = done,
            payloadDownloadedBytes = 0, payloadUploadedBytes = 0, peers = 0, savePath = folder.absolutePath, error = null)
        var doneBytes by androidx.compose.runtime.mutableStateOf(mkv.size.toLong())
        rule.setContent {
            ChunksTheme { Surface {
                androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                    LocalTransferCard(transfer = card(doneBytes), busy = false, onToggle = {}, onRemove = {},
                        files = listOf(io.github.gamesbyian.chunks.engine.LocalTransferFile(0, movie, mkv.size.toLong(), doneBytes, true)),
                        filesShown = true)
                }
            } }
        }
        rule.onNodeWithText("Reads only this file's container headers, on this device.").performScrollTo().assertExists()
        rule.onNodeWithText("Inspect media").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Matroska", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("The payload is only read", File(folder, movie).readBytes().contentEquals(mkv))
        // A partially downloaded file is never offered for inspection.
        doneBytes = 10
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("Inspect media").fetchSemanticsNodes().size)
    }

    @Test fun largerListArtworkSettingResizesVisibleRowsWithoutChangingIdentity() {
        val c = controller()
        c.observe(MediaTransferKey.of(local), name); c.lookup(MediaTransferKey.of(local))
        rule.setContent {
            CompositionLocalProvider(LocalTransferMediaController provides c) {
                ChunksTheme { Surface { TransferRowArtwork(local, name) } }
            }
        }
        val node = rule.onNodeWithContentDescription("Artwork: Babylon 5 (automatic). $format")
        node.assertWidthIsEqualTo(TransferRowArtworkSize)
        settings = settings.copy(largeListArtwork = true); c.onSettingsChanged()
        rule.waitForIdle()
        node.assertWidthIsEqualTo(io.github.gamesbyian.chunks.TransferRowArtworkLargeSize)
            .assertHeightIsEqualTo(io.github.gamesbyian.chunks.TransferRowArtworkLargeSize)
    }
}
