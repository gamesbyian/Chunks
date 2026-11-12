package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.media.artwork.ArtworkDiskCache
import io.github.gamesbyian.chunks.media.artwork.ArtworkRepository
import io.github.gamesbyian.chunks.media.providers.TmdbProvider
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manual genuine-provider qualification for #1453. Requires an explicitly supplied personal TMDB
 * key/token, online service access and permission to contact TMDB and its image CDN. Ordinary CI
 * skips the live case; this test is not a substitute for installed app/owner consent qualification.
 * No secrets, request URLs, names of private transfers or raw response bodies are printed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RealTmdbMovieArtworkTest {
    private val credential = System.getenv("CHUNKS_TMDB_CREDENTIAL")?.trim().orEmpty()
    private val offline = AtomicBoolean(false)
    private val requests = AtomicInteger(0)
    private val dir: File = Files.createTempDirectory("real-tmdb-movies").toFile()

    private val http = object : MediaHttp {
        private val real = UrlConnectionMediaHttp()
        override fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String>,
                         maxBytes: Int): MediaHttpResponse {
            if (offline.get()) throw MediaHttpException(MediaHttpFailure.OFFLINE, "Offline")
            // These exclusions are checked locally without logging a credential-bearing URL.
            assertTrue(!url.contains("1080p", ignoreCase = true))
            assertTrue(!url.contains("x264", ignoreCase = true))
            assertTrue(!url.contains(".mkv", ignoreCase = true))
            assertTrue(!url.contains("rtorrent", ignoreCase = true))
            requests.incrementAndGet()
            return real.get(url, hosts, headers, maxBytes)
        }

        override fun postJson(url: String, hosts: MediaHostPolicy, body: String,
                              headers: Map<String, String>, maxBytes: Int): MediaHttpResponse =
            error("TMDB movie and image lookups must never require a POST")
    }

    private fun controller(): TransferMediaController {
        val tmdb = TmdbProvider { credential }
        return TransferMediaController(
            MediaEnrichmentService(listOf(tmdb), http, FileMediaQueryCache(File(dir, "queries.json"))),
            ArtworkRepository(http, ArtworkDiskCache(File(dir, "artwork")),
                hostPolicyFor = { id -> if (id == TmdbProvider.ID) tmdb.info.artworkHostPolicy else null }),
            { FileTransferMediaAssignmentStore(File(dir, "assignments.json")) },
            { MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = true) },
            { MediaNetworkState(online = !offline.get(), metered = false) },
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, Dispatchers.Unconfined)
    }

    private fun key(environment: String, hash: Char) = MediaTransferKey.of(
        UnifiedTransferKey.of(EnvironmentId(environment), hash.toString().repeat(40)))

    @Test fun realAwayWeGoAndHackersAutoIdentifyAndLoadPostersAcrossEnvironments() = runBlocking {
        assumeTrue("Opt in with CHUNKS_REAL_MEDIA_LOOKUP=1 and a personal TMDB credential",
            System.getenv("CHUNKS_REAL_MEDIA_LOOKUP") == "1" &&
                TmdbProvider.isPlausibleCredential(credential))
        val local = key("local", 'a')
        val remote = key("rtorrent:seedbox-2", 'b')
        val movies = listOf(
            Triple(local, "Away.We.Go.2009.1080p.BluRay.x264-TEST.mkv", 2009),
            Triple(remote, "Hackers.1995.1080p.BluRay.x264-TEST.mkv", 1995))
        val first = controller()
        // This request is from the full authorized inventory, before any row or Details is composed.
        first.enrichInventory(movies.map { it.first to it.second })
        val persisted = FileTransferMediaAssignmentStore(File(dir, "assignments.json"))
        for ((id, title, year) in movies) {
            val a = requireNotNull(persisted.get(id)) { "Expected a persisted provider assignment" }
            assertEquals(MediaAssignmentState.AUTOMATIC, a.state)
            assertEquals(year, a.match?.year)
            assertTrue(a.match?.artwork?.any { it.role == ArtworkRole.POSTER } == true)
            val flow = first.observe(id, title)
            assertNotNull("Square art must be fetched and decoded", flow.value.artwork)
            first.requestPoster(id)
            assertNotNull("Real poster must be available in Details", flow.value.poster)
        }
        val afterOnline = requests.get()
        assertTrue(afterOnline >= 4) // two provider lookups + two actual image fetches
        offline.set(true)
        val restored = controller()
        for ((id, title, year) in movies) {
            val flow = restored.observe(id, title)
            assertEquals(year, flow.value.match?.year)
            assertNotNull("Cached square artwork must survive an offline restart", flow.value.artwork)
            restored.requestPoster(id)
            assertNotNull("Cached poster must survive an offline restart", flow.value.poster)
        }
        assertEquals("Offline restore must not issue network requests", afterOnline, requests.get())
    }
}
