package io.github.gamesbyian.chunks.media.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import io.github.gamesbyian.chunks.media.ArtworkCandidate
import io.github.gamesbyian.chunks.media.ArtworkRole
import io.github.gamesbyian.chunks.media.MediaHostPolicy
import io.github.gamesbyian.chunks.media.MediaHttp
import io.github.gamesbyian.chunks.media.MediaHttpException
import io.github.gamesbyian.chunks.media.MediaHttpFailure
import io.github.gamesbyian.chunks.media.whileAllowed
import java.io.ByteArrayOutputStream

sealed class ArtworkLoadResult {
    data class Loaded(val bitmap: Bitmap, val source: ArtworkCandidate, val fromCache: Boolean) : ArtworkLoadResult()
    /** No usable image; callers show bundled category art. [reasons] are safe diagnostics. */
    data class Unavailable(val reasons: List<String>) : ArtworkLoadResult()
}

/**
 * Safe decode + composition. Non-square art is never stretched or blindly cropped: near-square
 * images are centre-cropped by at most ~10%; others are fitted over a blurred fill of themselves.
 */
object ArtworkComposer {
    fun decodeBounded(bytes: ByteArray, target: Int): Pair<Bitmap?, String?> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        ArtworkBytesPolicy.dimensionsOk(bounds.outWidth, bounds.outHeight)?.let { return null to it }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = ArtworkBytesPolicy.sampleSize(bounds.outWidth, bounds.outHeight, target)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
            ?: return null to "Image could not be decoded"
        return bmp to null
    }

    fun square(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val aspect = w / h
        if (aspect in 0.9f..1.11f) {
            val side = minOf(src.width, src.height)
            val l = (src.width - side) / 2; val t = (src.height - side) / 2
            canvas.drawBitmap(src, Rect(l, t, l + side, t + side), Rect(0, 0, size, size), paint)
            return out
        }
        // Blurred fill: scale a tiny copy up (cheap, deterministic blur), dim it, then fit original.
        val tiny = Bitmap.createScaledBitmap(src, maxOf(2, (12 * aspect).toInt().coerceAtMost(24)), maxOf(2, (12 / aspect).toInt().coerceAtMost(24)), true)
        val coverScale = maxOf(size / w, size / h)
        val cw = w * coverScale; val ch = h * coverScale
        canvas.drawBitmap(tiny, null, RectF((size - cw) / 2, (size - ch) / 2, (size + cw) / 2, (size + ch) / 2), paint)
        canvas.drawColor(0x66000000)
        val fitScale = minOf(size / w, size / h)
        val fw = w * fitScale; val fh = h * fitScale
        canvas.drawBitmap(src, null, RectF((size - fw) / 2, (size - fh) / 2, (size + fw) / 2, (size + fh) / 2), paint)
        tiny.recycle()
        return out
    }

    fun fit(src: Bitmap, maxEdge: Int): Bitmap {
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(src.width, src.height))
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(src, maxOf(1, (src.width * scale).toInt()), maxOf(1, (src.height * scale).toInt()), true)
    }

    fun encode(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
}

/**
 * Artwork retrieval with memory + disk caches, negative caching, offline fallback and deterministic
 * candidate order. Blocking; call from a background dispatcher. Never writes outside [disk]'s
 * app-private directory and never touches transfer payloads.
 */
class ArtworkRepository(
    private val http: MediaHttp,
    private val disk: ArtworkDiskCache,
    /** Provider id → artwork host policy (from MediaProviderInfo.artworkHosts). */
    private val hostPolicyFor: (String) -> MediaHostPolicy?,
    memoryBytes: Int = defaultMemoryBytes()
) {
    companion object {
        const val SQUARE_SIZE = 384
        const val POSTER_EDGE = 720
        const val NEGATIVE_TTL_MS = 6L * 3600_000
        const val NOT_FOUND_TTL_MS = 3L * 24 * 3600_000
        fun defaultMemoryBytes(): Int = (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(24L * 1024 * 1024).toInt()
    }

    private val memory = object : LruCache<String, Bitmap>(memoryBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun memoryCached(source: ArtworkCandidate, role: ArtworkRole): Bitmap? = memory.get(key(source, role))

    private fun key(source: ArtworkCandidate, role: ArtworkRole) =
        ArtworkBytesPolicy.cacheKey(source.url, role, if (role == ArtworkRole.SQUARE) SQUARE_SIZE else POSTER_EDGE)

    fun pin(source: ArtworkCandidate, role: ArtworkRole) = disk.pin(key(source, role))
    fun unpin(source: ArtworkCandidate, role: ArtworkRole) = disk.unpin(key(source, role))

    /** Try candidates in deterministic order; the first usable one wins. */
    fun load(candidates: List<ArtworkCandidate>, role: ArtworkRole, allowNetwork: Boolean, providerOrder: List<String> = emptyList(), requestAllowed: () -> Boolean = { true }): ArtworkLoadResult {
        val reasons = mutableListOf<String>()
        for (c in ArtworkSelector.order(candidates, role, providerOrder)) {
            when (val r = loadOne(c, role, allowNetwork, requestAllowed)) {
                is ArtworkLoadResult.Loaded -> return r
                is ArtworkLoadResult.Unavailable -> reasons.addAll(r.reasons)
            }
        }
        if (candidates.isEmpty()) reasons.add("No artwork offered")
        return ArtworkLoadResult.Unavailable(reasons)
    }

    /**
     * Import a user-picked image (already read with a size bound). Same validation and composition as
     * remote art; the derivative is pinned in app-private storage, so the original URI is not retained.
     */
    fun importLocal(bytes: ByteArray): Pair<ArtworkCandidate?, String?> {
        val check = ArtworkBytesPolicy.check(bytes, null)
        if (check is ArtworkBytesCheck.Rejected) return null to check.reason
        val (decoded, err) = ArtworkComposer.decodeBounded(bytes, SQUARE_SIZE)
        if (decoded == null) return null to (err ?: "Image could not be decoded")
        val square = ArtworkComposer.square(decoded, SQUARE_SIZE)
        if (square !== decoded) decoded.recycle()
        val encoded = ArtworkComposer.encode(square)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(encoded).take(20).joinToString("") { "%02x".format(it) }
        val candidate = ArtworkCandidate(io.github.gamesbyian.chunks.media.LOCAL_PROVIDER, "local:$digest", ArtworkRole.SQUARE,
            square.width, square.height)
        val k = key(candidate, ArtworkRole.SQUARE)
        disk.put(k, encoded); disk.pin(k); memory.put(k, square)
        return candidate to null
    }

    /**
     * Explicit user "Use image URL": bounded download under [UserImageUrlPolicy] (public HTTPS hosts
     * only, every redirect hop rechecked), then the same validation as a device import.
     */
    fun importFromUrl(url: String, hostPolicy: MediaHostPolicy = UserImageUrlPolicy.hostPolicy()): Pair<ArtworkCandidate?, String?> {
        val checked = when (val c = UserImageUrlPolicy.check(url)) {
            is UserImageUrlPolicy.Check.Refused -> return null to c.reason
            is UserImageUrlPolicy.Check.Ok -> c.url
        }
        val resp = try {
            http.get(checked, hostPolicy, mapOf("Accept" to "image/jpeg,image/png,image/webp"), ArtworkBytesPolicy.MAX_BYTES)
        } catch (e: MediaHttpException) {
            return null to when (e.failure) {
                MediaHttpFailure.BLOCKED_HOST -> "Local or private addresses are not allowed"
                MediaHttpFailure.TOO_LARGE -> "Image too large"
                MediaHttpFailure.OFFLINE -> "Offline"
                MediaHttpFailure.TIMEOUT -> "Timed out"
                else -> "Download failed"
            }
        }
        if (resp.status != 200) return null to "Image link returned ${resp.status}"
        val check = ArtworkBytesPolicy.check(resp.body, resp.header("Content-Type"))
        if (check is ArtworkBytesCheck.Rejected) return null to check.reason
        return importLocal(resp.body)
    }

    /** Forget a user import (caller has verified no other assignment references it). */
    fun removeLocal(c: ArtworkCandidate) {
        if (c.provider != io.github.gamesbyian.chunks.media.LOCAL_PROVIDER) return
        val k = key(c, ArtworkRole.SQUARE); memory.remove(k); disk.remove(k)
    }

    fun loadOne(c: ArtworkCandidate, role: ArtworkRole, allowNetwork: Boolean, requestAllowed: () -> Boolean = { true }): ArtworkLoadResult {
        val local = c.provider == io.github.gamesbyian.chunks.media.LOCAL_PROVIDER
        val k = key(c, if (local) ArtworkRole.SQUARE else role)
        memory.get(k)?.let { return ArtworkLoadResult.Loaded(it, c, true) }
        disk.get(k)?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { memory.put(k, it); return ArtworkLoadResult.Loaded(it, c, true) }
            if (!local) disk.remove(k)
        }
        if (local) return ArtworkLoadResult.Unavailable(listOf("Your image is no longer stored"))
        if (disk.isNegative(k)) return ArtworkLoadResult.Unavailable(listOf("Recently failed; will retry later"))
        if (!allowNetwork || !requestAllowed()) return ArtworkLoadResult.Unavailable(listOf("Not cached; network not allowed"))
        val policy = hostPolicyFor(c.provider) ?: return ArtworkLoadResult.Unavailable(listOf("Unknown artwork source"))
        val resp = try {
            http.get(c.url, policy.whileAllowed(requestAllowed), mapOf("Accept" to "image/jpeg,image/png,image/webp"), ArtworkBytesPolicy.MAX_BYTES)
        } catch (e: MediaHttpException) {
            // Revocation is not a provider/image failure and must not poison the shared cache.
            if (!requestAllowed()) return ArtworkLoadResult.Unavailable(listOf("Not cached; network not allowed"))
            if (e.failure != MediaHttpFailure.OFFLINE) disk.markFailed(k, NEGATIVE_TTL_MS)
            return ArtworkLoadResult.Unavailable(listOf(e.message ?: "Download failed"))
        }
        if (!requestAllowed()) return ArtworkLoadResult.Unavailable(listOf("Not cached; network not allowed"))
        if (resp.status == 404) { disk.markFailed(k, NOT_FOUND_TTL_MS); return ArtworkLoadResult.Unavailable(listOf("No image at source")) }
        if (resp.status != 200) { disk.markFailed(k, NEGATIVE_TTL_MS); return ArtworkLoadResult.Unavailable(listOf("Image source error ${resp.status}")) }
        val check = ArtworkBytesPolicy.check(resp.body, resp.header("Content-Type"))
        if (check is ArtworkBytesCheck.Rejected) { disk.markFailed(k, NOT_FOUND_TTL_MS); return ArtworkLoadResult.Unavailable(listOf(check.reason)) }
        val target = if (role == ArtworkRole.SQUARE) SQUARE_SIZE else POSTER_EDGE
        val (decoded, err) = ArtworkComposer.decodeBounded(resp.body, target)
        if (decoded == null) { disk.markFailed(k, NOT_FOUND_TTL_MS); return ArtworkLoadResult.Unavailable(listOf(err ?: "Decode failed")) }
        val processed = if (role == ArtworkRole.SQUARE) ArtworkComposer.square(decoded, SQUARE_SIZE) else ArtworkComposer.fit(decoded, POSTER_EDGE)
        if (processed !== decoded) decoded.recycle()
        disk.put(k, ArtworkComposer.encode(processed))
        memory.put(k, processed)
        return ArtworkLoadResult.Loaded(processed, c, false)
    }

    fun clearMemory() = memory.evictAll()
}
