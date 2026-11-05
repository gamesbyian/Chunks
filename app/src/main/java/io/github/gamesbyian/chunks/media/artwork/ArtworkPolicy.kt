package io.github.gamesbyian.chunks.media.artwork

import io.github.gamesbyian.chunks.media.ArtworkCandidate
import io.github.gamesbyian.chunks.media.ArtworkRole
import java.security.MessageDigest

enum class ImageFormat(val mime: String) { JPEG("image/jpeg"), PNG("image/png"), WEBP("image/webp") }

sealed class ArtworkBytesCheck {
    data class Ok(val format: ImageFormat) : ArtworkBytesCheck()
    data class Rejected(val reason: String) : ArtworkBytesCheck()
}

/**
 * Untrusted remote image validation before any decode: size bounds and magic-byte sniffing.
 * The Content-Type header is advisory only; SVG/XML/HTML/GIF and everything unknown is refused.
 */
object ArtworkBytesPolicy {
    const val MAX_BYTES = 6 * 1024 * 1024
    const val MIN_BYTES = 64
    const val MAX_DIMENSION = 8_000
    const val MIN_DIMENSION = 96
    const val MAX_PIXELS = 40_000_000L

    fun check(bytes: ByteArray, contentType: String?): ArtworkBytesCheck {
        if (bytes.size > MAX_BYTES) return ArtworkBytesCheck.Rejected("Image too large")
        if (bytes.size < MIN_BYTES) return ArtworkBytesCheck.Rejected("Image too small")
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (ct != null && (ct.contains("svg") || ct.contains("xml") || ct.contains("html") || ct.startsWith("text/")))
            return ArtworkBytesCheck.Rejected("Not a raster image")
        val format = sniff(bytes) ?: return ArtworkBytesCheck.Rejected("Unsupported image format")
        return ArtworkBytesCheck.Ok(format)
    }

    fun sniff(b: ByteArray): ImageFormat? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> ImageFormat.JPEG
        b.size >= 8 && b.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> ImageFormat.PNG
        b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> ImageFormat.WEBP
        else -> null
    }

    /** Decoded bounds check, after a bounds-only decode and before allocating pixels. */
    fun dimensionsOk(width: Int, height: Int): String? = when {
        width <= 0 || height <= 0 -> "Image has no size"
        width > MAX_DIMENSION || height > MAX_DIMENSION || width.toLong() * height > MAX_PIXELS -> "Image dimensions too large"
        width < MIN_DIMENSION || height < MIN_DIMENSION -> "Image resolution too low"
        else -> null
    }

    /** Power-of-two sample size so the decoded long edge is at least [target] but no more than ~2x. */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        var s = 1
        while (maxOf(width, height) / (s * 2) >= target) s *= 2
        return s
    }

    fun cacheKey(url: String, role: ArtworkRole, size: Int): String {
        val d = MessageDigest.getInstance("SHA-256").digest("v1|$url|${role.name}|$size".toByteArray(Charsets.UTF_8))
        return d.take(20).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Deterministic artwork choice for a requested role: exact role first, then a role that composes
 * well into it, then larger known size, then provider order, then URL. Never random.
 */
object ArtworkSelector {
    private val fallbacks = mapOf(
        ArtworkRole.SQUARE to listOf(ArtworkRole.SQUARE, ArtworkRole.POSTER, ArtworkRole.THUMB, ArtworkRole.BANNER),
        ArtworkRole.POSTER to listOf(ArtworkRole.POSTER, ArtworkRole.SQUARE, ArtworkRole.THUMB),
        ArtworkRole.BANNER to listOf(ArtworkRole.BANNER, ArtworkRole.POSTER, ArtworkRole.SQUARE),
        ArtworkRole.THUMB to listOf(ArtworkRole.THUMB, ArtworkRole.SQUARE, ArtworkRole.POSTER))

    fun order(candidates: List<ArtworkCandidate>, role: ArtworkRole, providerOrder: List<String> = emptyList()): List<ArtworkCandidate> {
        val roles = fallbacks.getValue(role)
        return candidates.filter { it.role in roles }.distinctBy { it.url }.sortedWith(
            compareBy<ArtworkCandidate> { roles.indexOf(it.role) }
                .thenByDescending { (it.width ?: 0).toLong() * (it.height ?: 0) }
                .thenBy { providerOrder.indexOf(it.provider).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.url })
    }
}
