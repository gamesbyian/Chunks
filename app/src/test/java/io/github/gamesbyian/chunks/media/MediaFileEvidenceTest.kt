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
    private val phone = MediaTransferKey.of(UnifiedTransferKey.of(EnvironmentId("phone"), "c".repeat(40)))
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
        controller(MediaFileEvidenceSource.NONE).enrichInventory(listOf(phone to folderName))
        assertTrue(providerRequests().isEmpty())
        assertNull(records().get(phone))
    }

    @Test fun offscreenInventoryUsesLocalFileCompositionToAutoIdentifyAndCachePoster() = runBlocking {
        val evidence = CountingEvidence(mapOf(phone to movieFiles))
        controller(evidence).enrichInventory(listOf(phone to folderName))

        val saved = records().get(phone)
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
        val restored = controller(MediaFileEvidenceSource.NONE).observe(phone, folderName).value
        assertEquals(MediaAssignmentState.AUTOMATIC, restored.state)
        assertNotNull(restored.artwork)
        assertEquals(before, http.requests.size)
    }

    @Test fun evidenceIsReadOncePerExactJobAndNameAcrossInventoryPasses() = runBlocking {
        val evidence = CountingEvidence(mapOf(phone to movieFiles))
        val c = controller(evidence)
        settings = settings.copy(onlineLookupConsent = false)
        c.enrichInventory(listOf(phone to folderName))
        c.enrichInventory(listOf(phone to folderName))
        assertEquals(1, evidence.reads.get())
        assertTrue(http.requests.isEmpty()) // local evidence never implies provider consent
    }

    @Test fun pendingMetadataIsRetriedOnALaterPass() = runBlocking {
        var files: List<MediaFileHint>? = null // magnet metadata not yet known
        val c = controller { key -> if (key == phone) files else null }
        c.enrichInventory(listOf(phone to folderName))
        assertTrue(providerRequests().isEmpty())
        files = movieFiles
        c.enrichInventory(listOf(phone to folderName))
        assertEquals(MediaAssignmentState.AUTOMATIC, records().get(phone)?.state)
    }

    @Test fun nonVideoCompositionIsNeverLookedUpAsAMovie() = runBlocking {
        controller(CountingEvidence(mapOf(phone to photoFiles))).enrichInventory(listOf(phone to folderName))
        assertTrue(providerRequests().isEmpty())
        assertNull(records().get(phone)?.match)
    }

    @Test fun unauthorizedOrExcludedJobsNeitherReadFilesNorCallProviders() = runBlocking {
        val evidence = CountingEvidence(mapOf(phone to movieFiles, server to movieFiles))
        val c = controller(evidence)
        c.enrichInventory(listOf(phone to folderName)) { false }
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
        c.enrichInventory(listOf(phone to folderName)) { allowed }
        assertTrue(providerRequests().isEmpty())
        assertNull(records().get(phone))
        allowed = true
        // Not memoized from the revoked read: the next authorized pass reads again and succeeds.
        c.enrichInventory(listOf(phone to folderName)) { allowed }
        assertEquals(2, reads)
        assertEquals(MediaAssignmentState.AUTOMATIC, records().get(phone)?.state)
    }

    @Test fun nameOnlyRowDoesNotDowngradeDetailsFileEvidence() {
        settings = settings.copy(onlineLookupConsent = false)
        val c = controller(MediaFileEvidenceSource.NONE)
        val details = c.observe(phone, folderName, movieFiles)
        assertEquals(MediaKind.MOVIE, details.value.recognition.kind)
        val row = c.observe(phone, folderName)
        assertEquals(MediaKind.MOVIE, row.value.recognition.kind)
        assertEquals(MediaKind.MOVIE, details.value.recognition.kind)
    }

    @Test fun visibleNameOnlyRowUpgradesFromEngineEvidenceAndAutoIdentifies() {
        val c = controller(CountingEvidence(mapOf(phone to movieFiles)))
        val row = c.observe(phone, folderName) { true }
        c.refreshAutomatic(phone)
        assertEquals(MediaKind.MOVIE, row.value.recognition.kind)
        assertEquals(MediaAssignmentState.AUTOMATIC, row.value.state)
        assertNotNull(row.value.artwork)
    }

    @Test fun phoneEngineEvidenceIgnoresRemoteAndMalformedKeysAndNeverStartsAnEngine() {
        assertNull(PhoneEngineMediaFileEvidence.files(server))
        assertNull(PhoneEngineMediaFileEvidence.files(MediaTransferKey("phone|../../etc")))
        // No engine has been started in this process: no file list, no side effects.
        assertNull(PhoneEngineMediaFileEvidence.files(phone))
        assertNull(io.github.gamesbyian.chunks.LocalEngineHolder.current())
    }

    @Test fun evidenceCacheIsBoundedAndNameExact() {
        val cache = MediaFileEvidenceCache(maxEntries = 2)
        val r = MediaRecognizer.recognize(folderName, movieFiles)
        val keys = (1..3).map { MediaTransferKey("phone|" + it.toString().repeat(40)) }
        keys.forEach { cache.put(it, folderName, r) }
        assertNull(cache.get(keys[0], folderName))
        assertEquals(r, cache.get(keys[2], folderName))
        assertNull(cache.get(keys[2], "Renamed original"))
    }
}
