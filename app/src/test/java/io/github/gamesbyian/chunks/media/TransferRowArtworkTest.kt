package io.github.gamesbyian.chunks.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
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
import io.github.gamesbyian.chunks.phoneTransferKey
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
    private var settings = MediaPrivacySettings(onlineLookupConsent = true)
    private val dir: File = Files.createTempDirectory("row-media").toFile()
    private val hash = "c".repeat(40)
    private val name = "Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.mkv"
    private val phone = UnifiedTransferKey.of(EnvironmentId("phone"), hash)
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

    @Test fun rowsShowTheExactEnvironmentsAssignmentWithoutAnyNetworkFromDisplay() {
        val c = controller()
        c.observe(MediaTransferKey.of(phone), name); c.lookup(MediaTransferKey.of(phone))
        val requestsAfterLookup = http.requests.size
        rule.setContent {
            CompositionLocalProvider(LocalTransferMediaController provides c) {
                ChunksTheme { Surface {
                    androidx.compose.foundation.layout.Column {
                        TransferRowArtwork(phone, name)
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
        c.observe(MediaTransferKey.of(phone), name); c.lookup(MediaTransferKey.of(phone))
        rule.setContent {
            CompositionLocalProvider(LocalTransferMediaController provides c) {
                ChunksTheme { Surface {
                    androidx.compose.foundation.layout.Column {
                        TransferRowArtwork(phone, name, hidden = true)
                        TransferRowArtwork(null, "Other.Release.mkv")
                    }
                } }
            }
        }
        rule.onNodeWithContentDescription("Babylon", substring = true).assertDoesNotExist()
        assertEquals(2, rule.onAllNodesWithText("MKV").fetchSemanticsNodes().size)
        assertNull(remoteTransferKey(null, hash))
        assertNull(phoneTransferKey("not-a-hash"))
        assertEquals(server, remoteTransferKey("rtorrent:seedbox-2", hash.uppercase()))
    }

    @Test fun turningArtworkOffAppliesToVisibleRowsAndBackOnReloadsFromCacheOnly() {
        val c = controller()
        val flow = c.observe(MediaTransferKey.of(phone), name); c.lookup(MediaTransferKey.of(phone))
        assertNotNull(flow.value.artwork)
        settings = settings.copy(showArtwork = false); c.onSettingsChanged()
        assertNull(flow.value.artwork)
        val before = http.requests.size
        settings = settings.copy(showArtwork = true, downloadArtwork = false); c.onSettingsChanged()
        assertNotNull("Re-enabled artwork comes from the local cache", flow.value.artwork)
        assertEquals(before, http.requests.size)
        assertEquals(MediaAssignmentState.AUTOMATIC, flow.value.state)
    }

    @Test fun phoneCardKeepsCompactRowAndOffersIdentificationAt200PercentText() {
        val c = controller()
        c.observe(MediaTransferKey.of(phone), name); c.lookup(MediaTransferKey.of(phone))
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
        rule.onRoot().captureRoboImage("build/chunks-visual/media-row-phone-320dp-200pct.png")
        rule.onNodeWithContentDescription("More details for $name on this phone").performScrollTo().performClick()
        rule.onNodeWithText("Artwork & media information").performScrollTo().assertExists()
        rule.onNodeWithText("Change match or artwork…").performScrollTo().performClick()
        rule.onNodeWithText("Identify this transfer").performScrollTo().assertExists()
        rule.onNodeWithText("Reset to automatic").performScrollTo().assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/media-identify-phone-320dp-200pct.png")
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
        listOf(phone, serverOther, lockedKey).forEach { k ->
            c.observe(MediaTransferKey.of(k), name); c.lookup(MediaTransferKey.of(k))
        }
        c.observe(MediaTransferKey.of(unidentified), name) // same title, never looked up
        val sources = listOf(
            snapshot(EnvironmentId("phone"), false, obs(phone, name)),
            snapshot(EnvironmentId("rtorrent:seedbox-2"), false, obs(serverOther, "B5 copy on server"), obs(unidentified, name)),
            snapshot(lockedEnv, true))
        val before = http.requests.size
        var opened: UnifiedTransferKey? = null
        rule.setContent {
            CompositionLocalProvider(LocalTransferMediaController provides c) {
                ChunksTheme { Surface {
                    io.github.gamesbyian.chunks.SimilarTransfersHost(obs(phone, name), sources,
                        environmentLabel = { if (it.value == "phone") "This phone" else "Seedbox 2" },
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
    }

    @Test fun phoneDetailsUseTheTransfersOwnCompletedCoverWithoutAnyNetwork() {
        val c = controller()
        val folder = Files.createTempDirectory("phone-save").toFile()
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
        rule.onNodeWithContentDescription("More details for $album on this phone").performScrollTo().performClick()
        rule.onNodeWithText("Identify…").performScrollTo().performClick()
        rule.onNodeWithText("Use cover.jpg from this transfer").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Your artwork", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(MediaAssignmentState.CHOSEN_BY_USER, c.observe(MediaTransferKey.of(phone), album).value.state)
        assertEquals("A local cover never touches the network", before, http.requests.size)
        assertTrue("The payload file is unchanged", File(folder, "$album/cover.jpg").readBytes().contentEquals(cover))
    }

    @Test fun choosingAnotherProviderImageIsAUserChoiceThatSurvivesRestartAndIsReversible() {
        val c = controller()
        val k = MediaTransferKey.of(phone)
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

    @Test fun galleryShowsOnlyOnRequestAndRespectsDownloadSettings() {
        val c = controller()
        val k = MediaTransferKey.of(phone)
        c.observe(k, name); c.lookup(k)
        settings = settings.copy(downloadArtwork = false)
        val cachedOnly = kotlinx.coroutines.runBlocking { c.alternatives(k) }
        assertTrue("Without downloads only already cached images appear", cachedOnly.size <= 1)
        settings = settings.copy(showArtwork = false)
        assertTrue(kotlinx.coroutines.runBlocking { c.alternatives(k) }.isEmpty())
    }

    @Test fun searchableTitlesHoldOnlyConfirmedMatchesAndBumpTheRevision() {
        val c = controller()
        val k = MediaTransferKey.of(phone)
        c.observe(k, name)
        val before = c.assignmentRevision.value
        c.lookup(k)
        assertTrue(c.assignmentRevision.value > before)
        assertEquals("Babylon 5 Soul Mates", c.searchableTitles()[k])
        c.useCategoryArt(k, MediaKind.MOVIE)
        assertNull("Category-only choices are not searchable titles", c.searchableTitles()[k])
    }

    @Test fun identifyPanelKindChoicesStayReachableAndTappableAt200Percent() {
        val st = TransferMediaUiState(MediaTransferKey.of(phone), MediaRecognizer.recognize(name))
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
}
