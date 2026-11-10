package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.media.artwork.ArtworkDiskCache
import io.github.gamesbyian.chunks.media.artwork.ArtworkRepository
import io.github.gamesbyian.chunks.media.providers.MusicBrainzProvider
import io.github.gamesbyian.chunks.media.providers.OpenLibraryProvider
import io.github.gamesbyian.chunks.media.providers.TvMazeProvider
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * Opt-in (CHUNKS_REAL_MEDIA_LOOKUP=1) acceptance against the REAL public providers, through the
 * production controller, HTTP client, provider adapters, image validation and caches. Ordinary CI
 * never depends on outside sites. A handful of requests per run, under each provider's own polite
 * interval. Proves: a real match and a real decoded image; only the recognized title reaches the
 * provider (no file name, release tags, hash or extension); an offline restart restores the
 * assignment and artwork from local storage with zero requests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RealProviderLookupTest {
    private val dir: File = Files.createTempDirectory("real-media").toFile()
    private var offline = false

    /** The production client, observed: every URL is recorded before it is sent. */
    private inner class Recording(private val real: MediaHttp = UrlConnectionMediaHttp()) : MediaHttp {
        val urls = mutableListOf<String>()
        override fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse {
            urls.add(url)
            if (offline) throw MediaHttpException(MediaHttpFailure.OFFLINE, "Network unavailable")
            return real.get(url, hosts, headers, maxBytes)
        }
        override fun postJson(url: String, hosts: MediaHostPolicy, body: String, headers: Map<String, String>, maxBytes: Int) =
            throw MediaHttpException(MediaHttpFailure.IO, "No POST providers in this test")
    }

    private val http = Recording()
    private val settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)

    private fun controller(automatic: Boolean = false): TransferMediaController {
        val providers = listOf(TvMazeProvider(), MusicBrainzProvider(), OpenLibraryProvider())
        val byId = providers.associateBy { it.info.id }
        val service = MediaEnrichmentService(providers, http, FileMediaQueryCache(File(dir, "q.json")))
        return TransferMediaController(service,
            ArtworkRepository(http, ArtworkDiskCache(File(dir, "art")), hostPolicyFor = { byId[it]?.info?.artworkHostPolicy }),
            { FileTransferMediaAssignmentStore(File(dir, "assign.json")) }, { settings.copy(automaticLookup = automatic) }, { MediaNetworkState(!offline, false) },
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, Dispatchers.Unconfined)
    }

    @Before fun optIn() {
        assumeTrue("set CHUNKS_REAL_MEDIA_LOOKUP=1 to query the real public providers",
            System.getenv("CHUNKS_REAL_MEDIA_LOOKUP") == "1")
    }

    private fun key(n: Int) = MediaTransferKey.of(UnifiedTransferKey.of(EnvironmentId("phone"), n.toString().repeat(40)))

    private fun assertNoPrivateNameParts(forbidden: List<String>) {
        http.urls.forEach { u -> forbidden.forEach { f ->
            assertTrue("A provider request carried \"$f\"", !u.contains(f, ignoreCase = true)) } }
    }

    @Test fun realTvEpisodeLookupShowsRealArtworkAndRestoresOffline() {
        val name = "Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.x264-GRP.mkv"
        val k = key(1)
        val c = controller()
        val flow = c.observe(k, name)
        c.lookup(k)
        val s = flow.value
        println("CHUNKS_REAL_MEDIA tv state=${s.state} title=${s.match?.title} episode=${s.match?.episodeTitle} " +
            "art=${s.artwork?.width}x${s.artwork?.height} requests=${http.urls.size}")
        assertEquals("Babylon 5", s.match?.title)
        assertTrue(s.state == MediaAssignmentState.AUTOMATIC || s.state == MediaAssignmentState.SUGGESTED)
        if (s.state == MediaAssignmentState.SUGGESTED) c.choose(k, s.match!!)
        assertNotNull("A real provider image was fetched, validated and decoded", flow.value.artwork)
        assertNoPrivateNameParts(listOf("1080p", "WEB-DL", "x264", "GRP", ".mkv", k.value.substringAfter('|')))

        offline = true
        val before = http.urls.size
        val restored = controller().observe(k, name).value
        assertEquals("Babylon 5", restored.match?.title)
        assertNotNull("Artwork restored from the local cache", restored.artwork)
        assertEquals("Offline restart makes no request", before, http.urls.size)
    }

    @Test fun realOffscreenInventoryAutomaticallyFetchesAndRestoresArtworkForTwoEnvironments() =
        kotlinx.coroutines.runBlocking {
            val name = "Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.x264-GRP.mkv"
            val phoneKey = key(4)
            val serverKey = MediaTransferKey.of(UnifiedTransferKey.of(
                EnvironmentId("rtorrent:public-fixture"), "5".repeat(40)))
            val inventory = listOf(phoneKey to name, serverKey to name)
            // A full-inventory pass happens before either key has been observed
            // by a transfer row or Details. Only public synthetic titles are used.
            controller(automatic = true).enrichInventory(inventory)
            val persisted = FileTransferMediaAssignmentStore(File(dir, "assign.json"))
            for ((id, _) in inventory) {
                val a = requireNotNull(persisted.get(id))
                assertEquals(MediaAssignmentState.AUTOMATIC, a.state)
                assertEquals("Babylon 5", a.displayMatch?.title)
                assertTrue(a.displayMatch?.artwork?.isNotEmpty() == true)
            }
            assertTrue("Actual provider and CDN traffic must have occurred", http.urls.size >= 3)
            assertNoPrivateNameParts(listOf("1080p", "WEB-DL", "x264", "GRP",
                ".mkv", phoneKey.value.substringAfter('|'), serverKey.value.substringAfter('|'),
                "rtorrent"))
            val afterOnline = http.urls.size
            offline = true
            // Recreate the controller, assignment store and artwork repository;
            // the synthetic second account remains an independent exact key.
            val restored = controller(automatic = true)
            for ((id, _) in inventory) {
                val state = restored.observe(id, name).value
                assertEquals(MediaAssignmentState.AUTOMATIC, state.state)
                assertEquals("Babylon 5", state.match?.title)
                assertNotNull("Offline restart must use a previously fetched real JPEG", state.artwork)
            }
            assertEquals("Offline restored assignments/artwork issue no HTTP", afterOnline, http.urls.size)
        }

    @Test fun realMusicAndBookLookupsMatchWithoutLeakingNames() {
        val c = controller()
        val album = "Radiohead - OK Computer (1997) [FLAC] {24-96}"
        val book = "Frank Herbert - Dune (1965) [retail].epub"
        val a = c.observe(key(2), album); c.lookup(key(2))
        val b = c.observe(key(3), book); c.lookup(key(3))
        println("CHUNKS_REAL_MEDIA music state=${a.value.state} title=${a.value.match?.title} provider=${a.value.match?.attribution?.providerName} art=${a.value.artwork != null}")
        println("CHUNKS_REAL_MEDIA book state=${b.value.state} title=${b.value.match?.title} provider=${b.value.match?.attribution?.providerName} art=${b.value.artwork != null}")
        assertTrue("OK Computer found", (listOfNotNull(a.value.match) + (a.value.assignment?.suggestions ?: emptyList()))
            .any { it.title.contains("OK Computer", ignoreCase = true) })
        assertTrue("Dune found", (listOfNotNull(b.value.match) + (b.value.assignment?.suggestions ?: emptyList()))
            .any { it.title.contains("Dune", ignoreCase = true) })
        assertNoPrivateNameParts(listOf("FLAC", "24-96", "retail", ".epub"))
    }
}
