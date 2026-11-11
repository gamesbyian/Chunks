package io.github.gamesbyian.chunks.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.gamesbyian.chunks.media.artwork.ArtworkDiskCache
import io.github.gamesbyian.chunks.media.artwork.ArtworkLoadResult
import io.github.gamesbyian.chunks.media.artwork.ArtworkRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/** Real JPEG/PNG encode → validate → bounded decode → square compose → disk/memory cache (JVM graphics). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ArtworkRepositoryTest {
    private fun image(w: Int, h: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(Color.rgb(200, 40, 120)); drawRect(w * 0.25f, h * 0.25f, w * 0.75f, h * 0.75f,
            android.graphics.Paint().apply { color = Color.YELLOW }) }
        return ByteArrayOutputStream().also { b.compress(format, 90, it) }.toByteArray()
    }

    private val tvmaze = mediaHostPolicy(listOf("static.tvmaze.com"))
    private fun repo(http: FakeMediaHttp, dir: java.io.File = Files.createTempDirectory("art").toFile()) =
        ArtworkRepository(http, ArtworkDiskCache(dir), hostPolicyFor = { if (it == "tvmaze") tvmaze else null }, memoryBytes = 8 * 1024 * 1024)

    private val poster = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/uploads/p.jpg", ArtworkRole.POSTER)

    @Test fun portraitPosterComposesIntoSquareWithFillAndIsCached() {
        val http = FakeMediaHttp().onBytes("p.jpg", image(680, 1000), headers = mapOf("Content-Type" to "image/jpeg"))
        val dir = Files.createTempDirectory("art").toFile()
        val r = repo(http, dir).load(listOf(poster), ArtworkRole.SQUARE, allowNetwork = true) as ArtworkLoadResult.Loaded
        assertEquals(ArtworkRepository.SQUARE_SIZE, r.bitmap.width); assertEquals(ArtworkRepository.SQUARE_SIZE, r.bitmap.height)
        assertFalse(r.fromCache)
        // Left edge is blurred fill (opaque), centre is the original image content (yellow-ish).
        assertEquals(255, Color.alpha(r.bitmap.getPixel(2, 192)))
        val centre = r.bitmap.getPixel(192, 192)
        assertTrue(Color.red(centre) > 200 && Color.green(centre) > 200)
        // A new repository (new process) is served from disk, offline.
        val again = repo(FakeMediaHttp(), dir).load(listOf(poster), ArtworkRole.SQUARE, allowNetwork = false) as ArtworkLoadResult.Loaded
        assertTrue(again.fromCache); assertEquals(1, http.requests.size)
    }

    @Test fun corruptProcessedDiskDerivativeCannotSuppressOffscreenRetryAfterRestart() {
        val http = FakeMediaHttp().onBytes("p.jpg", image(680, 1000),
            headers = mapOf("Content-Type" to "image/jpeg"))
        val dir = Files.createTempDirectory("damaged-offscreen-art").toFile()
        val first = repo(http, dir)
        assertTrue(first.load(listOf(poster), ArtworkRole.SQUARE, true) is ArtworkLoadResult.Loaded)
        assertTrue(first.hasCachedSquare(listOf(poster)))
        // Simulate storage corruption after a successful download and before
        // a new process restores the offscreen inventory. No UI row is composed.
        val cached = dir.listFiles().orEmpty().single { it.name.endsWith(".img") }
        cached.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) +
            ByteArray(500) { 0x41 })
        val fresh = repo(http, dir)
        assertFalse("A damaged derivative must be eligible for offscreen refetch",
            fresh.hasCachedSquare(listOf(poster)))
        val before = http.requests.size
        assertTrue(fresh.load(listOf(poster), ArtworkRole.SQUARE, true) is ArtworkLoadResult.Loaded)
        assertEquals(before + 1, http.requests.size)
        assertTrue("Repaired derivative must remain reusable without a second fetch",
            repo(FakeMediaHttp(), dir).hasCachedSquare(listOf(poster)))
        assertTrue(repo(FakeMediaHttp(), dir)
            .load(listOf(poster), ArtworkRole.SQUARE, false) is ArtworkLoadResult.Loaded)
    }

    @Test fun squarePngIsKeptAndMemoryCacheAvoidsDisk() {
        val sq = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/s.png", ArtworkRole.SQUARE)
        val http = FakeMediaHttp().onBytes("s.png", image(500, 500, Bitmap.CompressFormat.PNG))
        val repo = repo(http)
        repo.load(listOf(sq), ArtworkRole.SQUARE, true)
        assertTrue((repo.load(listOf(sq), ArtworkRole.SQUARE, true) as ArtworkLoadResult.Loaded).fromCache)
        assertTrue(repo.memoryCached(sq, ArtworkRole.SQUARE) != null); assertEquals(1, http.requests.size)
    }

    @Test fun failuresFallBackToNextCandidateAndAreNegativelyCached() {
        val missing = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/missing.jpg", ArtworkRole.SQUARE)
        val html = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/page.jpg", ArtworkRole.SQUARE)
        val http = FakeMediaHttp().on("missing.jpg", status = 404)
            .on("page.jpg", body = "<html><body>not an image</body></html>".padEnd(300), headers = mapOf("Content-Type" to "text/html"))
            .onBytes("p.jpg", image(680, 1000))
        val repo = repo(http)
        val r = repo.load(listOf(missing, html, poster), ArtworkRole.SQUARE, true) as ArtworkLoadResult.Loaded
        assertEquals(poster, r.source)
        // Second pass: failed URLs are not retried within their negative TTL.
        val before = http.requests.size
        repo.clearMemory()
        repo.load(listOf(missing, html), ArtworkRole.SQUARE, true)
        assertEquals(before, http.requests.size)
    }

    @Test fun hostileImagesAreRejectedBeforeAllocation() {
        val wide = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/wide.png", ArtworkRole.SQUARE)
        val tiny = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/tiny.png", ArtworkRole.SQUARE)
        val corrupt = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/corrupt.jpg", ArtworkRole.SQUARE)
        val http = FakeMediaHttp().onBytes("wide.png", image(9000, 120, Bitmap.CompressFormat.PNG))
            .onBytes("tiny.png", image(40, 40, Bitmap.CompressFormat.PNG))
            .onBytes("corrupt.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(500) { 7 })
        val r = repo(http).load(listOf(wide, tiny, corrupt), ArtworkRole.SQUARE, true) as ArtworkLoadResult.Unavailable
        assertTrue(r.reasons.toString(), r.reasons.containsAll(listOf("Image dimensions too large", "Image resolution too low")))
        assertTrue(r.reasons.any { it.contains("decode", ignoreCase = true) || it.contains("no size") })
    }

    @Test fun foreignHostsAndOfflineNeverFetch() {
        val evil = ArtworkCandidate("tvmaze", "https://evil.example/x.jpg", ArtworkRole.SQUARE)
        val unknownProvider = ArtworkCandidate("nope", "https://static.tvmaze.com/x.jpg", ArtworkRole.SQUARE)
        val http = FakeMediaHttp()
        val r = repo(http).load(listOf(evil, unknownProvider), ArtworkRole.SQUARE, true) as ArtworkLoadResult.Unavailable
        assertTrue(http.requests.isEmpty())
        assertTrue(r.reasons.toString(), r.reasons.any { it.contains("not permitted") } && r.reasons.contains("Unknown artwork source"))
        val off = repo(FakeMediaHttp()).load(listOf(poster), ArtworkRole.SQUARE, allowNetwork = false) as ArtworkLoadResult.Unavailable
        assertEquals(listOf("Not cached; network not allowed"), off.reasons)
    }

    @Test fun userImageUrlImportIsBoundedAndPolicyChecked() {
        val http = FakeMediaHttp().onBytes("images.example.com/poster.png", image(600, 900, Bitmap.CompressFormat.PNG))
            .on("images.example.com/page", body = "<html>".padEnd(300), headers = mapOf("Content-Type" to "text/html"))
        val repo = repo(http)
        val public = mediaHostPolicy(listOf("images.example.com"))
        val (ok, _) = repo.importFromUrl("https://images.example.com/poster.png", public)
        assertEquals(LOCAL_PROVIDER, ok!!.provider)
        assertTrue(repo.loadOne(ok, ArtworkRole.SQUARE, allowNetwork = false) is ArtworkLoadResult.Loaded)
        assertEquals("Not a raster image", repo.importFromUrl("https://images.example.com/page", public).second)
        assertEquals("Local or private addresses are not allowed", repo.importFromUrl("https://192.168.1.1/x.png", public).second)
        // A redirect to a host the policy rejects never gets fetched.
        assertEquals("Local or private addresses are not allowed", repo.importFromUrl("https://cdn.example.org/x.png", public).second)
        assertEquals(2, http.requests.size)
    }
    @Test fun aRevokedLiveGatePreventsQueuedAndFallbackImageRequests() {
        var allowed = false
        val http = FakeMediaHttp().onBytes("p.jpg", image(680, 1000))
        val store = repo(http)
        store.load(listOf(poster), ArtworkRole.SQUARE, true, requestAllowed = { allowed })
        assertTrue(http.requests.isEmpty())
        allowed = true
        val missing = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/missing.jpg", ArtworkRole.SQUARE)
        val changing = object : MediaHttp by http {
            override fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse {
                val response = http.get(url, hosts, headers, maxBytes)
                allowed = false
                return response
            }
        }
        val r = ArtworkRepository(changing, ArtworkDiskCache(Files.createTempDirectory("gated-art").toFile()),
            hostPolicyFor = { tvmaze }).load(listOf(missing, poster), ArtworkRole.SQUARE, true, requestAllowed = { allowed })
        assertTrue(r is ArtworkLoadResult.Unavailable)
        assertEquals(1, http.requests.size)
    }

    @Test fun revocationDoesNotPoisonTheSharedImageCacheForAuthorizedTransfers() {
        var allowed = true
        var revokeOnFirstRequest = true
        val http = FakeMediaHttp().onBytes("p.jpg", image(680, 1000))
        val changing = object : MediaHttp by http {
            override fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse {
                if (revokeOnFirstRequest) {
                    revokeOnFirstRequest = false
                    allowed = false
                }
                return http.get(url, hosts, headers, maxBytes)
            }
        }
        val r = ArtworkRepository(changing, ArtworkDiskCache(Files.createTempDirectory("gated-art").toFile()), hostPolicyFor = { tvmaze })
        r.load(listOf(poster), ArtworkRole.SQUARE, true, requestAllowed = { allowed })
        assertTrue(http.requests.isEmpty())
        allowed = true
        assertTrue(r.load(listOf(poster), ArtworkRole.SQUARE, true, requestAllowed = { allowed }) is ArtworkLoadResult.Loaded)
        assertEquals(1, http.requests.size)
    }


    @Test fun disabledArtworkProviderCannotFetchButItsProcessedImageRemainsOfflineCapable() {
        val wire = FakeMediaHttp().onBytes("p.jpg", image(680, 1000),
            headers = mapOf("Content-Type" to "image/jpeg"))
        val dir = Files.createTempDirectory("provider-disabling-art").toFile()
        var enabled = false
        fun currentRepository() = ArtworkRepository(wire, ArtworkDiskCache(dir),
            hostPolicyFor = { id -> if (enabled && id == "tvmaze") tvmaze else null })

        val blocked = currentRepository().load(listOf(poster), ArtworkRole.SQUARE, allowNetwork = true)
        assertTrue("A disabled provider must not receive an image request",
            blocked is ArtworkLoadResult.Unavailable)
        assertTrue(wire.requests.isEmpty())

        enabled = true
        val downloaded = currentRepository().load(listOf(poster), ArtworkRole.SQUARE, allowNetwork = true)
        assertTrue(downloaded is ArtworkLoadResult.Loaded)
        assertEquals(1, wire.requests.size)

        // Revocation stops NEW HTTP, but does not erase previously consented artwork.
        // A separate repository instance proves disk persistence across process restart.
        enabled = false
        val restored = currentRepository().load(listOf(poster), ArtworkRole.SQUARE, allowNetwork = false)
        assertTrue(restored is ArtworkLoadResult.Loaded)
        assertTrue((restored as ArtworkLoadResult.Loaded).fromCache)
        val neverCached = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/never-cached.jpg", ArtworkRole.POSTER)
        assertTrue(currentRepository().load(listOf(neverCached), ArtworkRole.SQUARE, allowNetwork = true)
            is ArtworkLoadResult.Unavailable)
        assertEquals("Disabled provider must receive no further outbound image requests",
            1, wire.requests.size)
    }


    @Test fun importedImageSurvivesAndroidCacheCleanupAndNewRepositoryInstance() {
        val cache = Files.createTempDirectory("reclaimable-provider-art").toFile()
        val durable = Files.createTempDirectory("durable-manual-art").toFile()
        fun newRepository() = ArtworkRepository(FakeMediaHttp(), ArtworkDiskCache(cache),
            hostPolicyFor = { tvmaze }, importedDisk = ArtworkDiskCache(durable))
        val candidate = newRepository().importLocal(image(600, 800)).first!!
        assertEquals(LOCAL_PROVIDER, candidate.provider)
        assertTrue(durable.listFiles().orEmpty().any { it.name.endsWith(".img") })

        // Android is allowed to remove arbitrary cacheDir contents, even while the
        // user's exact manual assignment still refers to the imported image.
        cache.listFiles().orEmpty().forEach { it.delete() }
        val recovered = newRepository().loadOne(candidate, ArtworkRole.SQUARE, allowNetwork = false)
        assertTrue("Manual selection must be available without network or cacheDir",
            recovered is ArtworkLoadResult.Loaded)
        assertTrue((recovered as ArtworkLoadResult.Loaded).fromCache)
    }

    @Test fun olderCachedImportMigratesToDurableStoreWithoutChangingIdentity() {
        val cache = Files.createTempDirectory("legacy-import-cache").toFile()
        val durable = Files.createTempDirectory("migrated-manual-art").toFile()
        val existing = ArtworkRepository(FakeMediaHttp(), ArtworkDiskCache(cache),
            hostPolicyFor = { tvmaze })
        val candidate = existing.importLocal(image(600, 800)).first!!
        assertTrue(cache.listFiles().orEmpty().any { it.name.endsWith(".img") })

        fun upgraded() = ArtworkRepository(FakeMediaHttp(), ArtworkDiskCache(cache),
            hostPolicyFor = { tvmaze }, importedDisk = ArtworkDiskCache(durable))
        assertTrue(upgraded().loadOne(candidate, ArtworkRole.SQUARE, false) is ArtworkLoadResult.Loaded)
        assertTrue("Migration must copy an older local derivative to durable storage",
            durable.listFiles().orEmpty().any { it.name.endsWith(".img") })
        cache.listFiles().orEmpty().forEach { it.delete() }
        assertTrue("A later cold restore must retain the exact legacy import",
            upgraded().loadOne(candidate, ArtworkRole.SQUARE, false) is ArtworkLoadResult.Loaded)

        // Deliberate removal must delete both durable and legacy copies. A saved
        // assignment that refers to the removed candidate must not revive from either.
        val finished = upgraded()
        finished.removeLocal(candidate)
        assertTrue(finished.loadOne(candidate, ArtworkRole.SQUARE, false) is ArtworkLoadResult.Unavailable)
        assertFalse(durable.listFiles().orEmpty().any { it.name.endsWith(".img") })
    }


    @Test fun deliberatelySelectedProviderArtworkSurvivesCacheCleanupOffline() {
        val cache = Files.createTempDirectory("provider-auto-cache").toFile()
        val durable = Files.createTempDirectory("selected-poster-storage").toFile()
        val wire = FakeMediaHttp().onBytes("p.jpg", image(680, 1000),
            headers = mapOf("Content-Type" to "image/jpeg"))
        fun newRepository(http: FakeMediaHttp = wire) =
            ArtworkRepository(http, ArtworkDiskCache(cache),
                hostPolicyFor = { tvmaze }, importedDisk = ArtworkDiskCache(durable))

        val first = newRepository()
        assertTrue(first.loadOne(poster, ArtworkRole.SQUARE, true) is ArtworkLoadResult.Loaded)
        assertFalse("Ordinary automatic downloaded artwork is still reclaimable",
            durable.listFiles().orEmpty().any { it.name.endsWith(".img") })
        first.pin(poster, ArtworkRole.SQUARE)
        assertTrue("Choosing an image promotes its processed derivative to durable storage",
            durable.listFiles().orEmpty().any { it.name.endsWith(".img") })

        cache.listFiles().orEmpty().forEach { it.delete() }
        val offline = FakeMediaHttp()
        assertTrue(newRepository(offline).loadOne(poster, ArtworkRole.SQUARE, false) is ArtworkLoadResult.Loaded)
        assertTrue(offline.requests.isEmpty())

        val released = newRepository(offline)
        released.unpin(poster, ArtworkRole.SQUARE)
        assertFalse(durable.listFiles().orEmpty().any { it.name.endsWith(".img") })
        assertTrue(newRepository(offline).loadOne(poster, ArtworkRole.SQUARE, false) is ArtworkLoadResult.Unavailable)
        assertTrue(offline.requests.isEmpty())
    }

    @Test fun choosingPosterBeforeFirstDownloadStoresThatRoleDurably() {
        val cache = Files.createTempDirectory("poster-cache").toFile()
        val durable = Files.createTempDirectory("chosen-poster-storage").toFile()
        val wire = FakeMediaHttp().onBytes("p.jpg", image(680, 1000),
            headers = mapOf("Content-Type" to "image/jpeg"))
        val first = ArtworkRepository(wire, ArtworkDiskCache(cache),
            hostPolicyFor = { tvmaze }, importedDisk = ArtworkDiskCache(durable))
        first.pin(poster, ArtworkRole.POSTER)
        assertTrue(first.loadOne(poster, ArtworkRole.POSTER, true) is ArtworkLoadResult.Loaded)
        assertTrue(durable.listFiles().orEmpty().any { it.name.endsWith(".img") })
        assertEquals(1, wire.requests.size)

        cache.listFiles().orEmpty().forEach { it.delete() }
        val noNetwork = FakeMediaHttp()
        val restored = ArtworkRepository(noNetwork, ArtworkDiskCache(cache),
            hostPolicyFor = { tvmaze }, importedDisk = ArtworkDiskCache(durable))
        assertTrue(restored.loadOne(poster, ArtworkRole.POSTER, false) is ArtworkLoadResult.Loaded)
        assertTrue(noNetwork.requests.isEmpty())
    }

    @Test fun formerlyPinnedProviderArtworkMigratesAfterUpgradeWithoutAnIdentityChange() {
        val cache = Files.createTempDirectory("old-pinned-cache").toFile()
        val durable = Files.createTempDirectory("new-pinned-storage").toFile()
        val wire = FakeMediaHttp().onBytes("p.jpg", image(680, 1000))
        val legacy = ArtworkRepository(wire, ArtworkDiskCache(cache), hostPolicyFor = { tvmaze })
        assertTrue(legacy.loadOne(poster, ArtworkRole.SQUARE, true) is ArtworkLoadResult.Loaded)
        legacy.pin(poster, ArtworkRole.SQUARE)
        assertTrue(ArtworkDiskCache(cache).isPinned(
            io.github.gamesbyian.chunks.media.artwork.ArtworkBytesPolicy.cacheKey(
                poster.url, ArtworkRole.SQUARE, ArtworkRepository.SQUARE_SIZE)))

        fun upgraded() = ArtworkRepository(FakeMediaHttp(), ArtworkDiskCache(cache),
            hostPolicyFor = { tvmaze }, importedDisk = ArtworkDiskCache(durable))
        assertTrue(upgraded().loadOne(poster, ArtworkRole.SQUARE, false) is ArtworkLoadResult.Loaded)
        cache.listFiles().orEmpty().forEach { it.delete() }
        assertTrue(upgraded().loadOne(poster, ArtworkRole.SQUARE, false) is ArtworkLoadResult.Loaded)
    }


    @Test fun newManualImageIsNeverEvictedBeforeItsFirstPinUnderStoragePressure() {
        val cache = Files.createTempDirectory("ordinary-art-cache").toFile()
        val durable = Files.createTempDirectory("one-byte-limit-user-art").toFile()
        fun newRepository() = ArtworkRepository(FakeMediaHttp(), ArtworkDiskCache(cache),
            hostPolicyFor = { tvmaze },
            // This deliberately cannot fit any JPEG. The user's explicit choice is
            // protected even when existing pins already exceed the target budget.
            importedDisk = ArtworkDiskCache(durable, maxBytes = 1))
        val candidate = newRepository().importLocal(image(600, 800)).first!!
        assertTrue("New manual art must be pinned before trim sees its bytes",
            durable.listFiles().orEmpty().any { it.name.endsWith(".img") })
        assertTrue("A cold instance must decode the exact manual image offline",
            newRepository().loadOne(candidate, ArtworkRole.SQUARE, false) is ArtworkLoadResult.Loaded)
    }


    @Test fun removingAnOptionalProviderKeyStopsUncachedImageHttpButKeepsCachedPixels() {
        var credential: String? = null
        val provider = io.github.gamesbyian.chunks.media.providers.TmdbProvider { credential }
        val settings = MediaPrivacySettings(onlineLookupConsent = true)
        val dir = Files.createTempDirectory("key-revoked-poster-cache").toFile()
        val http = FakeMediaHttp().onBytes("image.tmdb.org", image(680, 1000),
            headers = mapOf("Content-Type" to "image/jpeg"))
        fun newRepository() = ArtworkRepository(http, ArtworkDiskCache(dir),
            hostPolicyFor = { id ->
                provider.takeIf {
                    it.info.id == id && settings.providerOn(it.info) && it.isConfigured()
                }?.info?.artworkHostPolicy
            })
        val first = ArtworkCandidate("tmdb",
            "https://image.tmdb.org/t/p/w500/first.jpg", ArtworkRole.POSTER)
        val second = ArtworkCandidate("tmdb",
            "https://image.tmdb.org/t/p/w500/second.jpg", ArtworkRole.POSTER)

        assertFalse(provider.isConfigured())
        assertTrue(newRepository().loadOne(first, ArtworkRole.SQUARE, true)
            is ArtworkLoadResult.Unavailable)
        assertTrue("No image HTTP without the user's configured key", http.requests.isEmpty())

        credential = "a".repeat(32)
        assertTrue(provider.isConfigured())
        assertTrue(newRepository().loadOne(first, ArtworkRole.SQUARE, true)
            is ArtworkLoadResult.Loaded)
        assertEquals(1, http.requests.size)

        credential = null
        assertTrue("An already processed, consented image stays usable without network",
            newRepository().loadOne(first, ArtworkRole.SQUARE, false)
                is ArtworkLoadResult.Loaded)
        assertTrue(newRepository().loadOne(second, ArtworkRole.SQUARE, true)
            is ArtworkLoadResult.Unavailable)
        assertEquals("Removing the provider credential forbids new image downloads",
            1, http.requests.size)
    }

}
