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
import org.junit.Assert.assertFalse
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

    private fun controller(httpOverride: MediaHttp = http, artDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined): TransferMediaController {
        val tv = TvMazeProvider()
        val service = MediaEnrichmentService(listOf(tv), httpOverride, FileMediaQueryCache(File(dir, "q.json")), { clock.now }, clock::sleep)
        return TransferMediaController(service,
            ArtworkRepository(httpOverride, ArtworkDiskCache(File(dir, "art")), hostPolicyFor = { tv.info.artworkHostPolicy }, memoryBytes = 4_000_000),
            { FileTransferMediaAssignmentStore(File(dir, "assign.json")) }, { settings }, { MediaNetworkState(true, false) },
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, artDispatcher, { clock.now },
            automationStoreFactory = { FileMediaAutomationPreferences(File(dir, "automation.json")) })
    }

    @Test fun displayingNeverCallsNetworkAndShowsCategoryArt() {
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false) // consent alone does not auto-lookup
        val s = controller().observe(phone, name).value
        assertTrue(http.requests.isEmpty())
        assertEquals(MediaKind.TV_EPISODE, s.displayKind); assertEquals(MediaAssignmentState.NOT_LOOKED_UP, s.state); assertNull(s.artwork)
    }

    @Test fun explicitLookupMatchesFetchesArtworkAndPersists() {
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
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
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
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

    @Test fun enablingAutomaticLookupRescansAlreadyObservedTransfersAcrossEnvironments() {
        val c = controller()
        val local = c.observe(phone, name)
        val remote = c.observe(server, name)
        assertTrue(http.requests.isEmpty())
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
        c.onSettingsChanged()
        assertEquals(MediaAssignmentState.AUTOMATIC, local.value.state)
        assertEquals(MediaAssignmentState.AUTOMATIC, remote.value.state)
    }

    @Test fun disablingAutomaticLookupDoesNotSendRequestsForNewTransfers() {
        settings = MediaPrivacySettings(onlineLookupConsent = false, automaticLookup = true)
        val c = controller()
        c.observe(phone, name)
        assertTrue(http.requests.isEmpty())
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
        c.onSettingsChanged()
        c.observe(server, name)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun inventoryEnrichesUnrenderedPhoneAndServerAndRestoresFromDisk() =
        kotlinx.coroutines.runBlocking {
            settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
            val c = controller()
            c.enrichInventory(listOf(phone to name, server to name))
            val records = FileTransferMediaAssignmentStore(File(dir, "assign.json"))
            assertEquals(MediaAssignmentState.AUTOMATIC, records.get(phone)?.state)
            assertEquals(MediaAssignmentState.AUTOMATIC, records.get(server)?.state)
            val requests = http.requests.size
            controller().enrichInventory(listOf(phone to name, server to name))
            assertEquals(requests, http.requests.size)
        }

    @Test fun inventoryWithoutConsentSendsNoProviderRequestsAndPreservesUserChoice() =
        kotlinx.coroutines.runBlocking {
            val c = controller()
            c.enrichInventory(listOf(phone to name, server to name))
            assertTrue(http.requests.isEmpty())
            c.observe(phone, name)
            c.useCategoryArt(phone, MediaKind.DOCUMENT)
            settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
            c.enrichInventory(listOf(phone to name, server to name))
            assertEquals(MediaAssignmentState.CATEGORY_ART_BY_USER,
                FileTransferMediaAssignmentStore(File(dir, "assign.json")).get(phone)?.state)
            assertEquals(MediaAssignmentState.AUTOMATIC,
                FileTransferMediaAssignmentStore(File(dir, "assign.json")).get(server)?.state)
        }

    @Test fun blockedAutomaticLookupExplainsConsentAndGlobalArtworkGatesWithoutRequests() {
        val c = controller()
        val flow = c.observe(phone, name)
        assertTrue(flow.value.automaticMessage!!.contains("consent"))
        assertTrue(http.requests.isEmpty())
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true, showArtwork = false)
        c.onSettingsChanged()
        assertTrue(flow.value.automaticMessage!!.contains("collection switched off"))
        assertTrue(http.requests.isEmpty())
    }

    @Test fun unavailableConfiguredProviderIsReportedWithoutPretendingANameWasVerified() {
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true,
            disabledProviders = setOf("tvmaze"))
        val flow = controller().observe(phone, name)
        assertEquals(MediaAssignmentState.NOT_LOOKED_UP, flow.value.state)
        assertTrue(flow.value.automaticMessage!!.contains("No enabled provider"))
        assertTrue(http.requests.isEmpty())
    }

    @Test fun revokingFullInventoryAuthorizationDuringHttpPreventsAssignmentCommit() =
        kotlinx.coroutines.runBlocking {
            settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
            val granted = java.util.concurrent.atomic.AtomicBoolean(true)
            val guarded = object : MediaHttp by http {
                override fun get(url: String, hosts: MediaHostPolicy,
                                 headers: Map<String, String>, maxBytes: Int): MediaHttpResponse {
                    val result = http.get(url, hosts, headers, maxBytes)
                    granted.set(false)
                    return result
                }
            }
            val c = controller(guarded)
            c.enrichInventory(listOf(phone to name)) { granted.get() }
            assertTrue(http.requests.isNotEmpty())
            assertNull(FileTransferMediaAssignmentStore(File(dir, "assign.json")).get(phone))
        }

    @Test fun manualSearchOffersButNeverAutoApplies() {
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
        val c = controller(); val flow = c.observe(phone, "unknown_file")
        c.search(phone, MediaKind.TV_SERIES, "Babylon 5")
        val results = flow.value.searchResults!!
        assertTrue(results.isNotEmpty()); assertEquals(MediaAssignmentState.NOT_LOOKED_UP, flow.value.state)
        c.search(phone, MediaKind.TV_SERIES, "https://tracker/announce?passkey=1")
        assertTrue(flow.value.message!!.contains("link"))
    }

    @Test fun clearAllPurgesAssignmentsCacheAndArtwork() {
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
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
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
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
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
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
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
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

    @Test fun posterRoleLoadsOnRequestWithOriginalAspect() {
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
        val c = controller(); val flow = c.observe(phone, name)
        c.lookup(phone)
        assertNull(flow.value.poster) // never loaded for list rows
        c.requestPoster(phone)
        val poster = flow.value.poster!!
        assertTrue(poster.height > poster.width) // 680x1000 source keeps its portrait aspect
        assertTrue(poster.height <= 720)
    }

    @Test fun applyChoiceToRelatedTransfersIsPreviewedExplicitAndKeepsEpisodes() {
        // External image download requires the same provider consent as ordinary enrichment.
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
        val c = controller()
        val k = { n: Int -> MediaTransferKey("phone|" + "%040x".format(n)) }
        c.observe(k(1), "Babylon.5.S02E07.Soul.Mates.mkv"); val e8 = c.observe(k(2), "Babylon 5 - S02E08 - Gropos.mkv")
        val pack = c.observe(k(3), "Babylon.5.S03.1080p.WEB-DL"); val other = c.observe(k(4), "Babylon.Berlin.S01E01.mkv")
        val excluded = c.observe(k(5), "babylon_5_S04E01.mkv"); val decided = c.observe(k(6), "Babylon.5.S01E01.mkv")
        c.useCategoryArt(k(6), MediaKind.DOCUMENT)
        val show = MediaCandidate(io.github.gamesbyian.chunks.model.MediaMetadataCacheKey("tvmaze", "show", "475"), MediaKind.TV_EPISODE,
            "Babylon 5", 1993, season = 2, episode = 7, episodeTitle = "Soul Mates",
            artwork = listOf(ArtworkCandidate("tvmaze", "https://static.tvmaze.com/p.jpg", ArtworkRole.POSTER)), attribution = MediaAttribution("TVmaze", "CC", null))
        assertTrue(c.relatedForChoice(k(1)).isNotEmpty()) // preview is available before and after choosing
        c.choose(k(1), show)
        val preview = c.relatedForChoice(k(1)).map { it.key }
        assertEquals(listOf(k(2), k(3), k(5)).toSet(), preview.toSet()) // not Babylon Berlin, not the decided one
        assertTrue(http.requests.size <= 1) // only the shared artwork download, no per-episode lookups
        c.applyChoiceTo(k(1), preview - k(5))
        assertEquals(MediaAssignmentState.CHOSEN_BY_USER, e8.value.state)
        assertEquals(8, e8.value.match!!.episode); assertNull(e8.value.match!!.episodeTitle)
        assertEquals(MediaKind.TV_SEASON, pack.value.match!!.kind); assertEquals(3, pack.value.match!!.season)
        assertEquals(MediaAssignmentState.NOT_LOOKED_UP, excluded.value.state)
        assertEquals(MediaAssignmentState.NOT_LOOKED_UP, other.value.state)
        assertEquals(MediaKind.DOCUMENT, decided.value.displayKind)
        assertNotNull(e8.value.artwork)
    }

    @Test fun seasonPostersAndAutomaticMatchesNeverSpreadToOtherTransfers() {
        val c = controller()
        val k = { n: Int -> MediaTransferKey("phone|" + "%040x".format(n)) }
        c.observe(k(1), "Babylon.5.S01.1080p.WEB-DL"); val s3 = c.observe(k(2), "Babylon.5.S03.1080p.WEB-DL")
        val season1 = MediaCandidate(io.github.gamesbyian.chunks.model.MediaMetadataCacheKey("tvmaze", "show", "475"), MediaKind.TV_SEASON,
            "Babylon 5", 1993, season = 1, attribution = MediaAttribution("TVmaze", "CC", null), artwork = listOf(
                ArtworkCandidate("tvmaze", "https://static.tvmaze.com/s1.jpg", ArtworkRole.POSTER, season = 1),
                ArtworkCandidate("tvmaze", "https://static.tvmaze.com/show.jpg", ArtworkRole.POSTER)))
        c.choose(k(1), season1)
        c.applyChoiceTo(k(1), listOf(k(2)))
        assertEquals(listOf("https://static.tvmaze.com/show.jpg"), s3.value.match!!.artwork.map { it.url })
        // An automatic match under a user image is not a user choice and does not spread.
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
        val src = c.observe(phone, name); val target = c.observe(MediaTransferKey("phone|" + "c".repeat(40)), "Babylon.5.S02E09.mkv")
        c.lookup(phone); c.importUserImage(phone, png(Color.BLUE))
        assertEquals(MediaAssignmentState.CHOSEN_BY_USER, src.value.state)
        c.applyChoiceTo(phone, listOf(target.value.key))
        assertEquals(MediaAssignmentState.NOT_LOOKED_UP, target.value.state)
    }

    @Test fun localEmbeddedTagsDriveTheLookupAndOfferTheirCover() {
        val tagged = javaClass.classLoader!!.getResource("media-fixtures/embedded/tagged.flac")!!.readBytes()
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
        val mbHttp = FakeMediaHttp().on("musicbrainz.org", body = fixture("mb-releasegroup-search-ok-computer.json"))
        val service = MediaEnrichmentService(listOf(io.github.gamesbyian.chunks.media.providers.MusicBrainzProvider()), mbHttp,
            InMemoryMediaQueryCache(), { clock.now }, clock::sleep)
        val c = TransferMediaController(service, ArtworkRepository(mbHttp, ArtworkDiskCache(File(dir, "art2")), hostPolicyFor = { null }, memoryBytes = 1_000_000),
            { InMemoryTransferMediaAssignmentStore() }, { settings }, { MediaNetworkState(true, false) },
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, Dispatchers.Unconfined, { clock.now })
        val files = listOf(MediaFileHint("rip/01.flac", 30_000_000), MediaFileHint("rip/02.flac", 30_000_000))
        val flow = c.observe(phone, "rip", files)
        c.inspectLocalAudio(phone, files) { io.github.gamesbyian.chunks.media.inspection.ByteArrayMediaByteSource(tagged) }
        assertEquals("OK Computer", flow.value.recognition.title); assertTrue(flow.value.embeddedTags!!.picture != null)
        c.lookup(phone)
        assertEquals(MediaAssignmentState.AUTOMATIC, flow.value.state)
        assertTrue(mbHttp.requests.single().contains("artist%3A%22Radiohead%22"))
        c.importUserImage(phone, flow.value.embeddedTags!!.picture!!) // "Use album art from the files"
        assertEquals(LOCAL_PROVIDER, flow.value.artworkSource!!.provider)
    }

    @Test fun futureArrivalRulesFillOnlyUnassignedMatchingTransfersAndPersist() {
        val rules = File(dir, "rules.json")
        fun ctl() = TransferMediaController(
            MediaEnrichmentService(listOf(TvMazeProvider()), http, InMemoryMediaQueryCache(), { clock.now }, clock::sleep),
            ArtworkRepository(http, ArtworkDiskCache(File(dir, "art")), hostPolicyFor = { TvMazeProvider().info.artworkHostPolicy }, memoryBytes = 4_000_000),
            { FileTransferMediaAssignmentStore(File(dir, "assign.json")) }, { settings }, { MediaNetworkState(true, false) },
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, Dispatchers.Unconfined, { clock.now },
            ruleStoreFactory = { FileMediaRuleStore(rules) })
        val c = ctl()
        val k = { n: Int -> MediaTransferKey("phone|" + "%040x".format(n)) }
        c.observe(k(1), "Babylon.5.S02E07.mkv")
        val show = MediaCandidate(io.github.gamesbyian.chunks.model.MediaMetadataCacheKey("tvmaze", "show", "475"), MediaKind.TV_EPISODE,
            "Babylon 5", 1993, season = 2, episode = 7, attribution = MediaAttribution("TVmaze", "CC", null))
        c.choose(k(1), show); c.saveRuleFrom(k(1))
        assertEquals(1, c.rules().size)
        // New process; a future arrival inherits the rule with its own episode, without any lookup.
        val c2 = ctl()
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
        kotlinx.coroutines.runBlocking { c2.enrichInventory(listOf(k(2) to "Babylon 5 S04E03 720p.mkv")) }
        val arrival = c2.observe(k(2), "Babylon 5 S04E03 720p.mkv")
        assertEquals(MediaAssignmentState.CHOSEN_BY_USER, arrival.value.state); assertEquals(4, arrival.value.match!!.season)
        assertEquals(3, arrival.value.match!!.episode)
        assertTrue(c2.setAutomaticLookupEnabled(k(6), false))
        val excludedArrival = c2.observe(k(6), "Babylon 5 S04E04.mkv")
        assertEquals(MediaAssignmentState.NOT_LOOKED_UP, excludedArrival.value.state)
        assertEquals(MediaKind.TV_EPISODE, excludedArrival.value.recognition.kind)
        settings = MediaPrivacySettings()
        val unrelated = c2.observe(k(3), "Babylon.Berlin.S01E01.mkv"); assertEquals(MediaAssignmentState.NOT_LOOKED_UP, unrelated.value.state)
        assertTrue(http.requests.none { it.contains("search/shows") })
        // Existing decisions are never overridden; removing the rule stops future inheritance.
        assertNotNull(c2.ruleFor(k(2)))
        c2.removeRule(c2.rules().single().id)
        assertEquals(MediaAssignmentState.NOT_LOOKED_UP, ctl().observe(k(4), "Babylon.5.S05E01.mkv").value.state)
        assertEquals(MediaAssignmentState.CHOSEN_BY_USER, ctl().observe(k(2), "Babylon 5 S04E03 720p.mkv").value.state)
    }
    @Test fun settingsChangesNeverRescanAnObservationWhoseAuthorizationEnded() {
        var authorized = true
        val c = controller()
        c.observe(server, name, isAuthorized = { authorized })
        authorized = false // privacy gate disposed the remote screen
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
        c.onSettingsChanged()
        assertTrue(http.requests.isEmpty())
    }

    @Test fun excludedTransferNeverQueuesProvidersAcrossObservationInventoryAndRestart() {
        val c = controller()
        assertTrue(c.setAutomaticLookupEnabled(phone, false))
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
        c.observe(phone, name)
        kotlinx.coroutines.runBlocking { c.enrichInventory(listOf(phone to name)) }
        c.onSettingsChanged()
        controller().observe(phone, name)
        assertTrue(http.requests.isEmpty())
        controller().observe(server, name)
        assertTrue(http.requests.isNotEmpty()) // same hash, independent environment
    }

    @Test fun addAdmissionExcludesBeforeTheFirstObservationAndSurvivesRestart() {
        val key = UnifiedTransferKey.of(EnvironmentId("phone"), "a".repeat(40))
        assertTrue(controller().prepareForAdd(key, allowAutomaticLookup = false))
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
        controller().observe(phone, name)
        assertTrue(http.requests.isEmpty())
        controller().observe(server, name)
        assertTrue(http.requests.isNotEmpty())
    }

    @Test fun defaultAddAdmissionNeverReenablesAnExistingExclusion() {
        val key = UnifiedTransferKey.of(EnvironmentId("phone"), "a".repeat(40))
        val c = controller()
        assertTrue(c.setAutomaticLookupEnabled(phone, false))
        assertTrue(c.prepareForAdd(key, allowAutomaticLookup = true))
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
        controller().observe(phone, name)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun failedOptOutPersistenceRefusesAddAdmissionWithoutProviderRequests() {
        File(dir, "automation.json").writeText("unreadable preferences")
        val key = UnifiedTransferKey.of(EnvironmentId("phone"), "a".repeat(40))
        assertFalse(controller().prepareForAdd(key, allowAutomaticLookup = false))
        assertTrue(http.requests.isEmpty())
    }

    @Test fun togglingAutomaticLookupPreservesManualIdentification() {
        val c = controller()
        val flow = c.observe(phone, name)
        val choice = MediaCandidate(io.github.gamesbyian.chunks.model.MediaMetadataCacheKey("tvmaze", "show", "475"),
            MediaKind.TV_SERIES, "Babylon 5", attribution = MediaAttribution("TVmaze", "CC", null))
        c.choose(phone, choice)
        assertTrue(c.setAutomaticLookupEnabled(phone, false))
        assertEquals(choice, flow.value.match)
        assertFalse(flow.value.automaticLookupEnabled)
        c.reset(phone)
        assertFalse(c.observe(phone, name).value.automaticLookupEnabled)
        assertTrue(c.setAutomaticLookupEnabled(phone, true))
        assertTrue(flow.value.automaticLookupEnabled)
    }

    @Test fun exclusionDuringProviderSearchBlocksFurtherRequestsAndDiscardsTheResult() {
        lateinit var c: TransferMediaController
        val gated = object : MediaHttp by http {
            override fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse {
                val response = http.get(url, hosts, headers, maxBytes)
                assertTrue(c.setAutomaticLookupEnabled(phone, false))
                return response
            }
        }
        c = controller(gated)
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
        val state = c.observe(phone, name)
        assertEquals(1, http.requests.size)
        assertNull(state.value.match)
        assertFalse(state.value.automaticLookupEnabled)
        assertNull(FileTransferMediaAssignmentStore(File(dir, "assign.json")).get(phone))
    }

    @Test fun committedObserverLoadsAnExistingAssignmentAfterInitiallyDeniedAuthorization() {
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
        val c = controller()
        val choice = MediaCandidate(io.github.gamesbyian.chunks.model.MediaMetadataCacheKey("tvmaze", "show", "475"),
            MediaKind.TV_SERIES, "Babylon 5", artwork = listOf(ArtworkCandidate("tvmaze", "https://static.tvmaze.com/cover.jpg", ArtworkRole.SQUARE)),
            attribution = MediaAttribution("TVmaze", "CC", null))
        var authorized = false
        val state = c.observe(phone, name, isAuthorized = { authorized })
        c.choose(phone, choice)
        assertTrue(http.requests.isEmpty())
        authorized = true
        c.refreshAutomatic(phone)
        assertNotNull(state.value.artwork)
        assertEquals(1, http.requests.size)
    }

    @Test fun exclusionBeforeQueuedArtworkExecutionSendsNoImageRequest() {
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
        val tasks = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val c = controller(artDispatcher = dispatcher)
        val state = c.observe(phone, name)
        c.choose(phone, MediaCandidate(io.github.gamesbyian.chunks.model.MediaMetadataCacheKey("tvmaze", "show", "475"),
            MediaKind.TV_SERIES, "Babylon 5", artwork = listOf(ArtworkCandidate("tvmaze", "https://static.tvmaze.com/cover.jpg", ArtworkRole.SQUARE)),
            attribution = MediaAttribution("TVmaze", "CC", null)))
        assertTrue(tasks.isNotEmpty())
        assertTrue(c.setAutomaticLookupEnabled(phone, false))
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertTrue(http.requests.isEmpty())
        assertNull(state.value.artwork)
    }

    @Test fun anAuthorizedObserverHandoffDoesNotLoseTheInFlightLookup() {
        lateinit var c: TransferMediaController
        var first = true
        val firstPermit = { first }
        val secondPermit = { true }
        var handedOff = false
        val changing = object : MediaHttp by http {
            override fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse {
                val response = http.get(url, hosts, headers, maxBytes)
                if (!handedOff) {
                    handedOff = true
                    first = false
                    c.stopObserving(phone, firstPermit)
                    c.observe(phone, name, isAuthorized = secondPermit)
                    c.refreshAutomatic(phone)
                }
                return response
            }
        }
        c = controller(changing)
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
        val state = c.observe(phone, name, isAuthorized = firstPermit)
        assertEquals(MediaAssignmentState.AUTOMATIC, state.value.state)
        assertEquals("Babylon 5", state.value.match!!.title)
    }

    @Test fun anUncachedPriorChoiceNeverFetchesArtworkWithoutProviderConsent() {
        val c = controller()
        val state = c.observe(phone, name)
        c.choose(phone, MediaCandidate(io.github.gamesbyian.chunks.model.MediaMetadataCacheKey("tvmaze", "show", "475"),
            MediaKind.TV_SERIES, "Babylon 5", artwork = listOf(ArtworkCandidate("tvmaze", "https://static.tvmaze.com/cover.jpg", ArtworkRole.SQUARE)),
            attribution = MediaAttribution("TVmaze", "CC", null)))
        c.requestPoster(phone)
        assertTrue(http.requests.isEmpty())
        assertNotNull(state.value.match)
        assertNull(state.value.artwork)
    }

    @Test fun negativeIdentificationExpiresAcrossRestartWithoutOverridingExclusion() {
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
        http.routes.removeAll { it.contains == "/search/shows" }
        http.on("/search/shows", status = 404)
        val c = controller()
        kotlinx.coroutines.runBlocking { c.enrichInventory(listOf(phone to name)) }
        assertEquals(MediaAssignmentState.COULDNT_IDENTIFY,
            FileTransferMediaAssignmentStore(File(dir, "assign.json")).get(phone)!!.state)
        val before = http.requests.size
        http.routes.removeAll { it.contains == "/search/shows" }
        http.on("/search/shows", body = fixture("tvmaze-search-babylon5.json"))
        clock.now += MediaEnrichmentService.NOT_FOUND_TTL_MS - 1
        kotlinx.coroutines.runBlocking { controller().enrichInventory(listOf(phone to name)) }
        assertEquals(before, http.requests.size)
        assertTrue(c.setAutomaticLookupEnabled(phone, false))
        clock.now += 2
        kotlinx.coroutines.runBlocking { controller().enrichInventory(listOf(phone to name)) }
        assertEquals(before, http.requests.size)
        val restored = controller()
        assertTrue(restored.setAutomaticLookupEnabled(phone, true))
        kotlinx.coroutines.runBlocking { restored.enrichInventory(listOf(phone to name)) }
        assertEquals(MediaAssignmentState.AUTOMATIC,
            FileTransferMediaAssignmentStore(File(dir, "assign.json")).get(phone)!!.state)
        assertTrue(http.requests.size > before)
    }

    @Test fun userChoiceWinsAgainstAnAutomaticResultAtTheStoreCommitBoundary() = choiceWinsAtCommit(true)
    @Test fun userChoiceWinsAgainstAnExplicitLookupAtTheStoreCommitBoundary() = choiceWinsAtCommit(false)


    private fun choiceWinsAtCommit(automatic: Boolean) {
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = automatic)
        val parent = kotlinx.coroutines.SupervisorJob()
        val work = CoroutineScope(parent + Dispatchers.Default)
        val reachedCommit = java.util.concurrent.CountDownLatch(1)
        val releaseCommit = java.util.concurrent.CountDownLatch(1)
        val manualStored = java.util.concurrent.CountDownLatch(1)
        val blockOnce = java.util.concurrent.atomic.AtomicBoolean(true)
        val backing = InMemoryTransferMediaAssignmentStore()
        val store = object : TransferMediaAssignmentStore by backing {
            override fun put(a: TransferMediaAssignment) {
                if (a.state == MediaAssignmentState.AUTOMATIC && blockOnce.compareAndSet(true, false)) {
                    reachedCommit.countDown()
                    check(releaseCommit.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
                backing.put(a)
                if (a.state == MediaAssignmentState.CHOSEN_BY_USER) manualStored.countDown()
            }
        }
        val c = TransferMediaController(
            MediaEnrichmentService(listOf(TvMazeProvider()), http, InMemoryMediaQueryCache(), { clock.now }, clock::sleep),
            ArtworkRepository(http, ArtworkDiskCache(File(dir, "race-art")), hostPolicyFor = { TvMazeProvider().info.artworkHostPolicy }),
            { store }, { settings }, { MediaNetworkState(true, false) }, work,
            Dispatchers.Default, Dispatchers.Default, { clock.now })
        suspend fun awaitIdle() = kotlinx.coroutines.withTimeout(10_000) {
            while (parent.children.any()) kotlinx.coroutines.delay(10)
        }
        try {
            c.observe(phone, name)
            if (!automatic) {
                kotlinx.coroutines.runBlocking { awaitIdle() }
                c.lookup(phone)
            }
            assertTrue(reachedCommit.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val chosen = MediaCandidate(io.github.gamesbyian.chunks.model.MediaMetadataCacheKey("tvmaze", "show", "57926"),
                MediaKind.TV_SERIES, "Chosen deliberately", attribution = MediaAttribution("TVmaze", "CC", null))
            c.choose(phone, chosen)
            // Either write order must preserve the deliberate edit. The old path permits the
            // edit to finish before its stale lookup commits; an atomic commit serializes them.
            manualStored.await(2, java.util.concurrent.TimeUnit.SECONDS)
            releaseCommit.countDown()
            kotlinx.coroutines.runBlocking { awaitIdle() }
            assertEquals(MediaAssignmentState.CHOSEN_BY_USER, backing.get(phone)!!.state)
            assertEquals(chosen, backing.get(phone)!!.match)
            assertEquals(chosen, c.observe(phone, name).value.match)
        } finally {
            releaseCommit.countDown()
            parent.cancel()
        }
    }
    @Test fun manualSearchStopsWhenConsentIsWithdrawnDuringTheFirstResponse() = interruptedSearch(true)
    @Test fun manualSearchStopsWhenItsObservationIsClosedDuringTheFirstResponse() = interruptedSearch(false)

    private fun interruptedSearch(withdrawConsent: Boolean) {
        var authorized = true
        val changing = object : MediaHttp by http {
            override fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse {
                val response = http.get(url, hosts, headers, maxBytes)
                if (withdrawConsent) settings = settings.copy(onlineLookupConsent = false) else authorized = false
                return response
            }
        }
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
        val c = controller(changing)
        val state = c.observe(phone, name, isAuthorized = { authorized })
        c.search(phone, MediaKind.TV_EPISODE, "Babylon 5", year = 1993, season = 2, episode = 7)
        assertEquals(1, http.requests.size)
        assertNull(state.value.searchResults)
        assertFalse(state.value.busy)
    }

    @Test fun gallerySquareRequestsStopAfterConsentWithdrawal() = interruptedGallery(false)
    @Test fun galleryPosterRequestsStopAfterConsentWithdrawal() = interruptedGallery(true)

    private fun interruptedGallery(posters: Boolean) {
        val changing = object : MediaHttp by http {
            override fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse {
                val response = http.get(url, hosts, headers, maxBytes)
                settings = settings.copy(onlineLookupConsent = false)
                return response
            }
        }
        val c = controller(changing)
        c.observe(phone, name)
        c.choose(phone, galleryChoice()) // offline choice; no image fetched yet
        assertTrue(http.requests.isEmpty())
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
        val result = kotlinx.coroutines.runBlocking {
            if (posters) c.posterAlternatives(phone) else c.alternatives(phone)
        }
        assertEquals(1, http.requests.size)
        assertTrue(result.isEmpty())
    }

    @Test fun excludedTransfersStillAllowExplicitArtworkCorrection() {
        val c = controller()
        val state = c.observe(phone, name)
        c.choose(phone, galleryChoice())
        assertTrue(c.setAutomaticLookupEnabled(phone, false))
        settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
        val result = kotlinx.coroutines.runBlocking { c.alternatives(phone) }
        assertEquals(2, result.size)
        assertEquals(2, http.requests.size)
        assertFalse(state.value.automaticLookupEnabled)
    }

    private fun galleryChoice() = MediaCandidate(io.github.gamesbyian.chunks.model.MediaMetadataCacheKey("tvmaze", "show", "475"),
        MediaKind.TV_SERIES, "Babylon 5", artwork = listOf(
            ArtworkCandidate("tvmaze", "https://static.tvmaze.com/first.jpg", ArtworkRole.POSTER),
            ArtworkCandidate("tvmaze", "https://static.tvmaze.com/second.jpg", ArtworkRole.POSTER)),
        attribution = MediaAttribution("TVmaze", "CC", null))

}
