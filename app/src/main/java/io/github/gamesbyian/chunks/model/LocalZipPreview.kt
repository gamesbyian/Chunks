package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Read-only bridge from an Android SAF InputStream to the existing ZIP inspector.
 *
 * Only an app-private bounded temporary copy is created. The input provider and
 * torrent backing files are never written, and no archive member is extracted.
 * Temporary staging is removed on all exits, including malformed archives,
 * provider read failures and input exceeding the explicit cap.
 *
 * File length and SAF-reported size are untrusted. Count actual incoming bytes.
 */
object LocalZipPreview {
    const val MAX_STAGING_BYTES = 32L * 1024 * 1024
    private const val MIN_FREE_BYTES = 8L * 1024 * 1024

    fun inspect(
        source: InputStream,
        privateCacheDir: File,
        limits: ZipPreflightLimits = ZipPreflightLimits(),
        maxStagingBytes: Long = MAX_STAGING_BYTES
    ): ZipPreflightResult {
        require(maxStagingBytes > 0)
        if (!privateCacheDir.isDirectory || !privateCacheDir.canWrite())
            return blocked("App-private temporary storage is unavailable")
        if (privateCacheDir.usableSpace < MIN_FREE_BYTES)
            return blocked("Device storage is too low for a temporary ZIP preview")

        var temporary: File? = null
        return try {
            temporary = File.createTempFile("chunks-zip-preview-", ".zip", privateCacheDir)
            var total = 0L
            var overLimit = false
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(16_384)
                while (true) {
                    val length = source.read(buffer)
                    if (length < 0) break
                    if (length == 0) continue
                    // Compare without adding first, preventing Long overflow.
                    if (length.toLong() > maxStagingBytes - total) {
                        overLimit = true
                        break
                    }
                    total += length
                    output.write(buffer, 0, length)
                }
            }
            if (overLimit) blocked("ZIP preview exceeds the local 32 MiB staging limit; no extraction attempted")
            else ZipArchiveInventory.inspect(temporary, limits, maxStagingBytes)
        } catch (_: IOException) {
            blocked("The selected ZIP could not be read or staged safely")
        } catch (_: SecurityException) {
            blocked("Storage permission was refused for this ZIP preview")
        } finally {
            // Never leave archive bytes behind in the app's private cache.
            temporary?.delete()
        }
    }

    private fun blocked(reason: String) = ZipPreflightResult(
        status = ZipPreflightStatus.BLOCKED,
        reasons = listOf(reason),
        declaredOutputBytes = null,
        fileCount = 0,
        names = emptyList()
    )
}
