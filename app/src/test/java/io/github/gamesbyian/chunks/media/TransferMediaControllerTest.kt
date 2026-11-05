package io.github.gamesbyian.chunks.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.gamesbyian.chunks.media.artwork.ArtworkDiskCache
import io.github.gamesbyian.chunks.media.artwork.ArtworkRepository
import io.github.gamesbyian.chunks.media.providers.TvMazeProvider
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TransferMediaControllerTest {
    private val clock = FakeClock()
    private var settings = MediaPrivacySettings()
    private val dir: File = Files.createTempDirectory("media").toFile()
    private val phone = MediaTransferKey.of(UnifiedTransferKey.of(EnvironmentId("phone"), "a".repeat(40)))
    private val server = MediaTransferKey.of(UnifiedTransferKey.of(EnvironmentId("rtorrent:seedbox-2"), "a".repeat(40)))
    private val name = "Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.mkv"

    private fun jpeg(): ByteArray {
        val b = Bitmap.createBitmap(680, 1000, Bitmap.Config.ARGB_8888); Canvas(b).drawColor(Color.BLUE)
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

    @Test fun displayingNeverCallsNetworkAndShowsCategoryArt() {
        settings = MediaPrivacySettings(onlineLookupConsent = true) // consent alone does not auto-lookup
        val s = controller().observe(phone, name).value
        assertTrue(http.requests.isEmpty())
        assertEquals(MediaKind.TV_EPISODE, s.displayKind); assertEquals(MediaAssignmentState.NOT_LOOKED_UP, s.state); assertNull(s.artwork)
    }

    @Test fun explicitLookupMatchesFetchesArtworkAndPersists() {
        settings = MediaPrivacySettings(onlineLookupConsent = true)
        val c = controller()
        val flow = c.observe(phone, name)
        c.lookup(phone)
        val s = flow.value
        assertEquals(MediaAssignmentState.AUTOMATIC, s.state)
        assertEquals("Babylon 5", s.match!!.title); assertEquals("Soul Mates", s.match!!.episodeTitle)
        assertNotNull(s.artwork); assertEquals(384, s.artwork!!.width)
        // Same hash on another server is an independent transfer: no assignment leaks across.
        assertEquals(MediaAssignmentState.NOT_LOOKED_UP, c.observe(server, name).value.state)
        // New process: assignment and artwork restored from disk without any network.
        val before = http.requests.size
        val restored = controller().observe(phone, name).value
        assertEquals(MediaAssignmentState.AUTOMATIC, restored.state); assertNotNull(restored.artwork)
        assertEquals(before, http.requests.size)
    }

    @Test fun lookupWithoutConsentSendsNothing() {
        val c = controller(); val flow = c.observe(phone, name)
        c.lookup(phone)
        assertTrue(http.requests.isEmpty()); assertEquals(MediaAssignmentState.NOT_LOOKED_UP, flow.value.state)
        assertTrue(flow.value.message!!.contains("off"))
    }

    @Test fun userChoiceWinsOverLaterLookupsAndResetRestoresAutomatic() {
        settings = MediaPrivacySettings(onlineLookupConsent = true)
        val c = controller(); val flow = c.observe(phone, name)
        val other = MediaCandidate(io.github.gamesbyian.chunks.model.MediaMetadataCacheKey("tvmaze", "show", "57926"), MediaKind.TV_SERIES,
            "Babylon 5", attribution = MediaAttribution("TVmaze", "CC BY-SA 4.0", null))
        c.choose(phone, other)
        assertEquals(MediaAssignmentState.CHOSEN_BY_USER, flow.value.state)
        c.lookup(phone)
        assertEquals("57926", flow.value.match!!.ref.externalId) // never overwritten by automatic lookup
        assertTrue(http.requests.isEmpty())
        c.reset(phone)
        assertEquals(MediaAssignmentState.NOT_LOOKED_UP, flow.value.state)
        c.lookup(phone)
        assertEquals("475", flow.value.match!!.ref.externalId)
    }

    @Test fun categoryArtChoiceAndAdultSafety() {
        val c = controller(); val flow = c.observe(phone, name)
        c.useCategoryArt(phone, MediaKind.DOCUMENT)
        assertEquals(MediaKind.DOCUMENT, flow.value.displayKind); assertEquals(MediaAssignmentState.CATEGORY_ART_BY_USER, flow.value.state)
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
        val adultKey = MediaTransferKey("phone|" + "b".repeat(40))
        val adult = c.observe(adultKey, "Something.XXX.1080p.mp4")
        c.lookup(adultKey)
        assertEquals(MediaKind.ADULT, adult.value.displayKind); assertNull(adult.value.match)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun automaticLookupForVisibleTransfersOnlyWhenOptedIn() {
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
        val s = controller().observe(phone, name).value
        assertEquals(MediaAssignmentState.AUTOMATIC, s.state)
    }

    @Test fun manualSearchOffersButNeverAutoApplies() {
        settings = MediaPrivacySettings(onlineLookupConsent = true)
        val c = controller(); val flow = c.observe(phone, "unknown_file")
        c.search(phone, MediaKind.TV_SERIES, "Babylon 5")
        val results = flow.value.searchResults!!
        assertTrue(results.isNotEmpty()); assertEquals(MediaAssignmentState.NOT_LOOKED_UP, flow.value.state)
        c.search(phone, MediaKind.TV_SERIES, "https://tracker/announce?passkey=1")
        assertTrue(flow.value.message!!.contains("link"))
    }

    @Test fun clearAllPurgesAssignmentsCacheAndArtwork() {
        settings = MediaPrivacySettings(onlineLookupConsent = true)
        val c = controller(); val flow = c.observe(phone, name); c.lookup(phone)
        val art = ArtworkDiskCache(File(dir, "art"))
        c.clearAllMediaData({ File(dir, "q.json").delete() }, { art.clear() })
        assertNull(flow.value.assignment); assertNull(flow.value.artwork)
        assertEquals(0L, art.sizeBytes())
        assertTrue(FileTransferMediaAssignmentStore(File(dir, "assign.json")).all().isEmpty())
    }

    private fun png(color: Int): ByteArray {
        val b = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888); Canvas(b).drawColor(color)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun userImageWorksOfflineWinsOverProviderArtAndSurvivesRestart() {
        val c = controller(); val flow = c.observe(phone, name)
        c.importUserImage(phone, png(Color.GREEN))
        val s = flow.value
        assertEquals(MediaAssignmentState.CHOSEN_BY_USER, s.state); assertTrue(s.mayShowRealArt)
        assertEquals(LOCAL_PROVIDER, s.artworkSource!!.provider); assertEquals(Color.GREEN, s.artwork!!.getPixel(192, 192))
        assertTrue(http.requests.isEmpty()) // no consent, no network: local images still work
        settings = MediaPrivacySettings(onlineLookupConsent = true)
        c.lookup(phone)
        assertEquals(LOCAL_PROVIDER, flow.value.artworkSource!!.provider) // lookup never overrides the user's choice
        // Restart; the pinned import survives heavy cache churn.
        val art = ArtworkDiskCache(File(dir, "art"), maxBytes = 1)
        art.put("%040x".format(99), ByteArray(4000))
        val restored = controller().observe(phone, name).value
        assertEquals(LOCAL_PROVIDER, restored.artworkSource!!.provider)
    }

    @Test fun sharedImportIsKeptUntilTheLastReferenceIsRemoved() {
        val c = controller()
        val f1 = c.observe(phone, name); val f2 = c.observe(server, name)
        c.importUserImage(phone, png(Color.RED)); c.importUserImage(server, png(Color.RED))
        assertEquals(f1.value.userArtwork, f2.value.userArtwork)
        c.removeUserImage(phone)
        assertNull(f1.value.userArtwork)
        val reopened = controller().observe(server, name).value
        assertNotNull("other transfer's identical image must survive", reopened.artwork)
        c.reset(server)
        assertNull(f2.value.assignment)
    }

    @Test fun rejectedImportsExplainWithoutChangingAssignment() {
        val c = controller(); val flow = c.observe(phone, name)
        c.importUserImage(phone, "<svg/>".padEnd(500).toByteArray())
        assertNull(flow.value.assignment); assertEquals("Unsupported image format", flow.value.message)
    }

    @Test fun removingImportedImageRestoresAutomaticProvenance() {
        settings = MediaPrivacySettings(onlineLookupConsent = true)
        val c = controller(); val flow = c.observe(phone, name)
        c.lookup(phone); assertEquals(MediaAssignmentState.AUTOMATIC, flow.value.state)
        c.importUserImage(phone, png(Color.GREEN)); assertEquals(MediaAssignmentState.CHOSEN_BY_USER, flow.value.state)
        c.removeUserImage(phone)
        assertEquals(MediaAssignmentState.AUTOMATIC, flow.value.state); assertEquals("475", flow.value.match!!.ref.externalId)
    }

    @Test fun explicitLookupOnUserChoiceExplainsInsteadOfSilentlyDoingNothing() {
        val c = controller(); val flow = c.observe(phone, name)
        c.useCategoryArt(phone, MediaKind.MOVIE)
        c.lookup(phone)
        assertTrue(flow.value.message!!.contains("Reset to automatic"))
    }

    @Test fun replacingAChoiceUnpinsTheOldArtwork() {
        settings = MediaPrivacySettings(onlineLookupConsent = true)
        val c = controller(); val flow = c.observe(phone, name)
        c.lookup(phone)
        val first = flow.value.match!!
        c.choose(phone, first)
        val art = ArtworkDiskCache(File(dir, "art"))
        val k = io.github.gamesbyian.chunks.media.artwork.ArtworkBytesPolicy.cacheKey(first.artwork.first().url, ArtworkRole.SQUARE, 384)
        assertTrue(art.isPinned(k))
        c.useCategoryArt(phone, MediaKind.TV_SERIES)
        assertTrue(!ArtworkDiskCache(File(dir, "art")).isPinned(k))
    }
}
