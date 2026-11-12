package io.github.gamesbyian.chunks.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.gamesbyian.chunks.media.artwork.ArtworkDiskCache
import io.github.gamesbyian.chunks.media.artwork.ArtworkRepository
import io.github.gamesbyian.chunks.media.providers.TmdbProvider
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * #1453: a consented, authorized multi-file torrent named after its folder ("Hackers (1995)")
 * must be identified from its *local* file composition in the offscreen inventory path, exactly
 * as a rendered Details panel would, without ever sending a path or filename to a provider.
 * Provider responses here are SYNTHETIC test doubles, not real-provider evidence.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MediaFileEvidenceTest {
    private val clock = FakeClock()
    private var settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true)
    private val dir: File = Files.createTempDirectory("media-evidence").toFile()
    private val local = MediaTransferKey.of(UnifiedTransferKey.of(EnvironmentId("local"), "c".repeat(40)))
    private val server = MediaTransferKey.of(UnifiedTransferKey.of(EnvironmentId("rtorrent-profile"), "c".repeat(40)))
    private val folderName = "Hackers (1995)"
    private val movieFiles = listOf(
        MediaFileHint("Hackers (1995)/Hackers.1995.Private.Rip.mkv", 1_400_000_000L),
        MediaFileHint("Hackers (1995)/release-notes.nfo", 2_000L))
    private val photoFiles = (1..40).map { MediaFileHint("Hackers (1995)/IMG_$it.jpg", 3_000_000L) }

    private fun jpeg(): ByteArray {
        val b = Bitmap.createBitmap(500, 750, Bitmap.Config.ARGB_8888); Canvas(b).drawColor(Color.RED)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    // Synthetic TMDB-shaped response: one 1995 release and a same-title remake with another year.
    private val http = FakeMediaHttp()
        .on("/3/search/movie", body = """{"results":[
            {"id":10428,"title":"Hackers","original_title":"Hackers","release_date":"1995-09-15",
             "overview":"Synthetic fixture","poster_path":"/syntheticHackers.jpg","popularity":20.0,"adult":false},
            {"id":99999,"title":"Hackers","original_title":"Hackers","release_date":"2031-01-01",
             "overview":"Synthetic remake","poster_path":"/syntheticRemake.jpg","popularity":5.0,"adult":false}]}""")
        .on("/3/search/tv", body = """{"results":[
            {"id":68595,"name":"Planet Earth II","original_name":"Planet Earth II","first_air_date":"2016-11-06",
             "overview":"Synthetic fixture","poster_path":"/syntheticPlanet.jpg","popularity":30.0}]}""")
        .onBytes("image.tmdb.org", jpeg(), headers = mapOf("Content-Type" to "image/jpeg"))

    private fun providerRequests() = http.requests.filter { it.contains("api.themoviedb.org") }

    private fun controller(evidence: MediaFileEvidenceSource): TransferMediaController {
        val tmdb = TmdbProvider { "0123456789abcdef0123456789abcdef" }
        val service = MediaEnrichmentService(listOf(tmdb), http, FileMediaQueryCache(File(dir, "q.json")), { clock.now }, clock::sleep)
        return TransferMediaController(service,
            ArtworkRepository(http, ArtworkDiskCache(File(dir, "art")), hostPolicyFor = { tmdb.info.artworkHostPolicy }, memoryBytes = 4_000_000),
            { FileTransferMediaAssignmentStore(File(dir, "assign.json")) }, { settings }, { MediaNetworkState(true, false) },
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, Dispatchers.Unconfined, { clock.now },
            automationStoreFactory = { FileMediaAutomationPreferences(File(dir, "automation.json")) },
            fileEvidence = evidence)
    }

    private fun records() = FileTransferMediaAssignmentStore(File(dir, "assign.json"))

    private class CountingEvidence(private val byKey: Map<MediaTransferKey, List<MediaFileHint>>) : MediaFileEvidenceSource {
        val reads = AtomicInteger()
        override fun files(key: MediaTransferKey): List<MediaFileHint>? { reads.incrementAndGet(); return byKey[key] }
    }

    @Test fun folderNameAloneAbstainsFromOnlineLookup() = runBlocking {
        // The conservative recognizer must not blindly treat every "Title (Year)" as a film.
        assertEquals(MediaKind.UNKNOWN, MediaRecognizer.recognize(folderName).kind)
        controller(MediaFileEvidenceSource.NONE).enrichInventory(listOf(local to folderName))
        assertTrue(providerRequests().isEmpty())
        assertNull(records().get(local))
    }

    @Test fun offscreenInventoryUsesLocalFileCompositionToAutoIdentifyAndCachePoster() = runBlocking {
        val evidence = CountingEvidence(mapOf(local to movieFiles))
        controller(evidence).enrichInventory(listOf(local to folderName))

        val saved = records().get(local)
        assertEquals(MediaAssignmentState.AUTOMATIC, saved?.state)
        assertEquals("10428", saved!!.match!!.ref.externalId) // year safeguard excludes the same-title remake
        assertEquals(1995, saved.match!!.year)
        assertTrue(saved.match!!.artwork.isNotEmpty())
        // The processed artwork was fetched for the never-rendered job.
        assertTrue(http.requests.any { it.contains("image.tmdb.org") })

        // Privacy: only the cleaned title/year leave the device, never a path or filename.
        val query = providerRequests().single()
        assertTrue(query.contains("query=Hackers"))
        assertTrue(query.contains("year=1995"))
        for (leak in listOf("mkv", "nfo", "Private", "Rip", "release-notes", "%2F")) assertFalse(leak, query.contains(leak))

        // A fresh process restores identity and art without HTTP, then a row shows it.
        val before = http.requests.size
        val restored = controller(MediaFileEvidenceSource.NONE).observe(local, folderName).value
        assertEquals(MediaAssignmentState.AUTOMATIC, restored.state)
        assertNotNull(restored.artwork)
        assertEquals(before, http.requests.size)
    }

    @Test fun evidenceIsReadOncePerExactJobAndNameAcrossInventoryPasses() = runBlocking {
        val evidence = CountingEvidence(mapOf(local to movieFiles))
        val c = controller(evidence)
        settings = settings.copy(onlineLookupConsent = false)
        c.enrichInventory(listOf(local to folderName))
        c.enrichInventory(listOf(local to folderName))
        assertEquals(1, evidence.reads.get())
        assertTrue(http.requests.isEmpty()) // local evidence never implies provider consent
    }

    @Test fun pendingMetadataIsRetriedOnALaterPass() = runBlocking {
        var files: List<MediaFileHint>? = null // magnet metadata not yet known
        val c = controller { key -> if (key == local) files else null }
        c.enrichInventory(listOf(local to folderName))
        assertTrue(providerRequests().isEmpty())
        files = movieFiles
        c.enrichInventory(listOf(local to folderName))
        assertEquals(MediaAssignmentState.AUTOMATIC, records().get(local)?.state)
    }

    @Test fun lateMagnetMetadataRetriesAnUnresolvedResultOnceWhenTheQueryChanges() = runBlocking {
        // A magnet's display name alone looks like a film; no matching movie exists.
        val magnetName = "Planet.Earth.II.2160p.UHD.BluRay.x265-TEST"
        assertEquals(MediaKind.VIDEO, MediaRecognizer.recognize(magnetName).kind)
        var files: List<MediaFileHint>? = null
        val c = controller { key -> if (key == local) files else null }
        c.enrichInventory(listOf(local to magnetName))
        assertEquals(MediaAssignmentState.COULDNT_IDENTIFY, records().get(local)?.state)
        assertTrue(providerRequests().all { it.contains("/search/movie") })

        // An unchanged inventory pass must not repeat the negative search.
        val before = http.requests.size
        c.enrichInventory(listOf(local to magnetName))
        assertEquals(before, http.requests.size)

        // Metadata arrives: six episode files reveal a season pack. One retry is now due.
        files = (1..6).map { MediaFileHint("Planet.Earth.II/Planet.Earth.II.S01E0$it.2160p.mkv", 4_000_000_000L) }
        c.enrichInventory(listOf(local to magnetName))
        val saved = records().get(local)
        assertEquals(MediaAssignmentState.AUTOMATIC, saved?.state)
        assertEquals("68595", saved!!.match!!.ref.externalId)
        val tvQuery = providerRequests().single { it.contains("/search/tv") }
        for (leak in listOf("S01E0", "mkv", "2160p", "%2F")) assertFalse(leak, tvQuery.contains(leak))

        // Resolved: later passes do not search again.
        val settled = http.requests.size
        c.enrichInventory(listOf(local to magnetName))
        assertEquals(settled, http.requests.size)
    }

    @Test fun hostOfferedRemoteFileListIdentifiesTheExactRemoteJobOnly() = runBlocking {
        val c = controller(MediaFileEvidenceSource.NONE)
        c.enrichInventory(listOf(server to folderName))
        assertTrue(providerRequests().isEmpty()) // name alone: no lookup

        // An already-loaded authorized rTorrent file list, shared by its host.
        c.offerFileEvidence(server, folderName, movieFiles)
        c.enrichInventory(listOf(server to folderName, local to folderName))
        assertEquals(MediaAssignmentState.AUTOMATIC, records().get(server)?.state)
        // The same hash on the local device is a different job without evidence of its own.
        assertNull(records().get(local))
        assertEquals(1, providerRequests().size)
        // Exclusion still wins over offered evidence.
        val other = MediaTransferKey.of(UnifiedTransferKey.of(EnvironmentId("rtorrent-profile"), "d".repeat(40)))
        assertTrue(c.setAutomaticLookupEnabled(other, false))
        c.offerFileEvidence(other, folderName, movieFiles)
        c.enrichInventory(listOf(other to folderName))
        assertNull(records().get(other))
    }

    @Test fun offeredEvidenceUpgradesAVisibleRow() {
        val c = controller(MediaFileEvidenceSource.NONE)
        val row = c.observe(server, folderName) { true }
        assertEquals(MediaKind.UNKNOWN, row.value.recognition.kind)
        c.offerFileEvidence(server, folderName, movieFiles)
        assertEquals(MediaKind.MOVIE, row.value.recognition.kind)
        assertEquals(MediaAssignmentState.AUTOMATIC, row.value.state)
    }

    @Test fun nonVideoCompositionIsNeverLookedUpAsAMovie() = runBlocking {
        controller(CountingEvidence(mapOf(local to photoFiles))).enrichInventory(listOf(local to folderName))
        assertTrue(providerRequests().isEmpty())
        assertNull(records().get(local)?.match)
    }

    @Test fun unauthorizedOrExcludedJobsNeitherReadFilesNorCallProviders() = runBlocking {
        val evidence = CountingEvidence(mapOf(local to movieFiles, server to movieFiles))
        val c = controller(evidence)
        c.enrichInventory(listOf(local to folderName)) { false }
        assertEquals(0, evidence.reads.get())
        assertTrue(c.setAutomaticLookupEnabled(server, false))
        c.enrichInventory(listOf(server to folderName))
        assertTrue(providerRequests().isEmpty())
        assertNull(records().get(server)?.match)
        // Evidence for one exact key never classifies another environment's same-hash job.
        assertEquals(MediaKind.UNKNOWN, c.observe(MediaTransferKey.of(
            UnifiedTransferKey.of(EnvironmentId("other-profile"), "c".repeat(40))), folderName).value.recognition.kind)
    }

    @Test fun withdrawnAuthorityDuringFileReadRecordsNothing() = runBlocking {
        var allowed = true
        var reads = 0
        // The first read races a privacy lock / logout that revokes this exact job.
        val c = controller { _ -> reads++; if (reads == 1) allowed = false; movieFiles }
        c.enrichInventory(listOf(local to folderName)) { allowed }
        assertTrue(providerRequests().isEmpty())
        assertNull(records().get(local))
        allowed = true
        // Not memoized from the revoked read: the next authorized pass reads again and succeeds.
        c.enrichInventory(listOf(local to folderName)) { allowed }
        assertEquals(2, reads)
        assertEquals(MediaAssignmentState.AUTOMATIC, records().get(local)?.state)
    }

    @Test fun nameOnlyRowDoesNotDowngradeDetailsFileEvidence() {
        settings = settings.copy(onlineLookupConsent = false)
        val c = controller(MediaFileEvidenceSource.NONE)
        val details = c.observe(local, folderName, movieFiles)
        assertEquals(MediaKind.MOVIE, details.value.recognition.kind)
        val row = c.observe(local, folderName)
        assertEquals(MediaKind.MOVIE, row.value.recognition.kind)
        assertEquals(MediaKind.MOVIE, details.value.recognition.kind)
    }

    @Test fun visibleNameOnlyRowUpgradesFromEngineEvidenceAndAutoIdentifies() {
        val c = controller(CountingEvidence(mapOf(local to movieFiles)))
        val row = c.observe(local, folderName) { true }
        c.refreshAutomatic(local)
        assertEquals(MediaKind.MOVIE, row.value.recognition.kind)
        assertEquals(MediaAssignmentState.AUTOMATIC, row.value.state)
        assertNotNull(row.value.artwork)
    }

    @Test fun localEngineEvidenceIgnoresRemoteAndMalformedKeysAndNeverStartsAnEngine() {
        assertNull(LocalEngineMediaFileEvidence.files(server))
        assertNull(LocalEngineMediaFileEvidence.files(MediaTransferKey("local|../../etc")))
        // No engine has been started in this process: no file list, no side effects.
        assertNull(LocalEngineMediaFileEvidence.files(local))
        assertNull(io.github.gamesbyian.chunks.LocalEngineHolder.current())
    }

    @Test fun evidenceCacheIsBoundedAndNameExact() {
        val cache = MediaFileEvidenceCache(maxEntries = 2)
        val r = MediaRecognizer.recognize(folderName, movieFiles)
        val keys = (1..3).map { MediaTransferKey("local|" + it.toString().repeat(40)) }
        keys.forEach { cache.put(it, folderName, r) }
        assertNull(cache.get(keys[0], folderName))
        assertEquals(r, cache.get(keys[2], folderName))
        assertNull(cache.get(keys[2], "Renamed original"))
    }
}
