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
}
