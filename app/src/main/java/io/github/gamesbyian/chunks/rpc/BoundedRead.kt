package io.github.gamesbyian.chunks.rpc

import java.io.InputStream

/**
 * Reads at most [limit] bytes, stopping early at end of stream: the same contract as
 * `InputStream.readNBytes(int)`, which Android provides only from API 33. Chunks
 * supports API 29 without core-library desugaring, where that call throws
 * NoSuchMethodError (an Error that ordinary `catch (Exception)` handlers miss).
 * Callers pass `max + 1` and reject an over-long result, exactly as before.
 */
internal fun InputStream.readAtMost(limit: Int): ByteArray {
    require(limit >= 0) { "Negative read limit" }
    val out = java.io.ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(minOf(limit, 8192).coerceAtLeast(1))
    var remaining = limit
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }
    return out.toByteArray()
}
