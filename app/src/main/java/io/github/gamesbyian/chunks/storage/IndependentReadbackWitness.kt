package io.github.gamesbyian.chunks.storage

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Read-only digest witness from a NEW independently opened, identity-checked stream. */
internal object IndependentReadbackWitness {
    /**
     * The opener must bind to the exact claimed object, not a rediscovered name.
     * This helper neither opens a pathname nor infers identity from its contents.
     * Returns null on absent, interrupted, stalled, oversized or truncated streams.
     */
    fun read(size: Long, openByIdentity: () -> InputStream?,
             cancelled: () -> Boolean = { false }): OwnedPublicationRecovery.Digest? {
        if (size < 0 || cancelled()) return null
        return try {
            val input = openByIdentity() ?: return null
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(32 * 1024)
            var count = 0L
            input.use { stream ->
                while (true) {
                    if (cancelled() || Thread.currentThread().isInterrupted) return@use null
                    val n = stream.read(bytes)
                    if (n < 0) break
                    if (n == 0 || n.toLong() > size - count) return@use null
                    count += n
                    digest.update(bytes, 0, n)
                }
                if (count != size) return@use null
                val sha256 = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                OwnedPublicationRecovery.Digest(count, sha256)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
