package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.media.artwork.ArtworkBytesCheck
import io.github.gamesbyian.chunks.media.artwork.ArtworkBytesPolicy
import io.github.gamesbyian.chunks.media.artwork.ArtworkDiskCache
import io.github.gamesbyian.chunks.media.artwork.ArtworkSelector
import io.github.gamesbyian.chunks.media.artwork.ImageFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ArtworkPolicyTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(200)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(200)
    private val webp = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(200)

    @Test fun sniffsOnlyAllowedRasterFormats() {
        assertEquals(ArtworkBytesCheck.Ok(ImageFormat.JPEG), ArtworkBytesPolicy.check(jpeg, "image/jpeg"))
        assertEquals(ArtworkBytesCheck.Ok(ImageFormat.PNG), ArtworkBytesPolicy.check(png, null))
        // A wrong but harmless content-type does not matter; the bytes decide.
        assertEquals(ArtworkBytesCheck.Ok(ImageFormat.WEBP), ArtworkBytesPolicy.check(webp, "application/octet-stream"))
        val svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".padEnd(200).toByteArray()
        assertTrue(ArtworkBytesPolicy.check(svg, "image/svg+xml") is ArtworkBytesCheck.Rejected)
        assertTrue(ArtworkBytesPolicy.check(jpeg, "text/html") is ArtworkBytesCheck.Rejected)
        val gif = "GIF89a".toByteArray() + ByteArray(200)
        assertTrue(ArtworkBytesPolicy.check(gif, "image/gif") is ArtworkBytesCheck.Rejected)
        assertTrue(ArtworkBytesPolicy.check(ByteArray(10), null) is ArtworkBytesCheck.Rejected)
        assertTrue(ArtworkBytesPolicy.check(jpeg + ByteArray(ArtworkBytesPolicy.MAX_BYTES), null) is ArtworkBytesCheck.Rejected)
    }

    @Test fun dimensionBoundsAndSampling() {
        assertNull(ArtworkBytesPolicy.dimensionsOk(680, 1000))
        assertNotNull(ArtworkBytesPolicy.dimensionsOk(9000, 200))
        assertNotNull(ArtworkBytesPolicy.dimensionsOk(7000, 7000)) // 49 MP decompression bomb
        assertNotNull(ArtworkBytesPolicy.dimensionsOk(50, 50))
        assertNotNull(ArtworkBytesPolicy.dimensionsOk(-1, 100))
        assertEquals(1, ArtworkBytesPolicy.sampleSize(500, 500, 384))
        assertEquals(4, ArtworkBytesPolicy.sampleSize(2000, 3000, 384))
        assertEquals(2, ArtworkBytesPolicy.sampleSize(1200, 1000, 384))
    }

    @Test fun selectionIsDeterministicAndRolePreferring() {
        val poster = ArtworkCandidate("tvmaze", "https://static.tvmaze.com/p.jpg", ArtworkRole.POSTER)
        val square = ArtworkCandidate("musicbrainz", "https://coverartarchive.org/x/front-500", ArtworkRole.SQUARE, 500, 500)
        val banner = ArtworkCandidate("tmdb", "https://image.tmdb.org/b.jpg", ArtworkRole.BANNER)
        val bigPoster = ArtworkCandidate("tmdb", "https://image.tmdb.org/p.jpg", ArtworkRole.POSTER, 500, 750)
        val all = listOf(banner, poster, bigPoster, square)
        assertEquals(listOf(square, bigPoster, poster, banner), ArtworkSelector.order(all, ArtworkRole.SQUARE))
        assertEquals(ArtworkSelector.order(all.reversed(), ArtworkRole.SQUARE), ArtworkSelector.order(all, ArtworkRole.SQUARE))
        assertEquals(banner, ArtworkSelector.order(all, ArtworkRole.BANNER).first())
        assertEquals(listOf(poster), ArtworkSelector.order(listOf(poster, poster), ArtworkRole.POSTER))
    }

    @Test fun cacheKeysAreDigestsThatSeparateRoleAndSize() {
        val a = ArtworkBytesPolicy.cacheKey("https://x/y.jpg", ArtworkRole.SQUARE, 384)
        assertTrue(a.matches(Regex("[0-9a-f]{40}")))
        assertFalse(a == ArtworkBytesPolicy.cacheKey("https://x/y.jpg", ArtworkRole.POSTER, 384))
        assertFalse(a == ArtworkBytesPolicy.cacheKey("https://x/y.jpg", ArtworkRole.SQUARE, 512))
    }

    @Test fun diskCacheIsBoundedLruWithPinsAndNegativeEntries() {
        val dir = Files.createTempDirectory("art").toFile()
        val clock = FakeClock()
        val cache = ArtworkDiskCache(dir, maxBytes = 2500, clock = { clock.now })
        val k = (1..4).map { "%040x".format(it) }
        cache.put(k[0], ByteArray(1000)); clock.now += 1000
        cache.put(k[1], ByteArray(1000)); clock.now += 1000
        cache.pin(k[0])
        cache.put(k[2], ByteArray(1000)) // over budget: evict oldest unpinned (k1), never pinned k0
        assertTrue(cache.contains(k[0])); assertFalse(cache.contains(k[1])); assertTrue(cache.contains(k[2]))
        assertArrayEquals(ByteArray(1000), cache.get(k[0]))
        cache.markFailed(k[3], 5_000)
        assertTrue(cache.isNegative(k[3]))
        assertTrue(ArtworkDiskCache(dir, clock = { clock.now }).isNegative(k[3])) // persisted
        clock.now += 5_001
        assertFalse(cache.isNegative(k[3]))
        cache.clear()
        assertEquals(0L, cache.sizeBytes())
        // Directory listing contains only digests: no titles leak through filenames.
        assertTrue(dir.list()!!.all { it.matches(Regex("[0-9a-f]{40}\\.img|negative\\.json")) })
    }
}
