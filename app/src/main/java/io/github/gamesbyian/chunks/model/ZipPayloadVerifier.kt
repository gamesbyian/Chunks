package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipFile

enum class ZipPayloadStatus { VERIFIED_READ_ONLY, BLOCKED }

/**
 * Verification of *actual inflated ZIP payload bytes*, without extracting or
 * creating any output file. Success never grants filesystem write authority.
 */
data class ZipPayloadVerification(
    val status: ZipPayloadStatus,
    val verifiedFiles: Int,
    val actualOutputBytes: Long?,
    val reason: String?
)

object ZipPayloadVerifier {
    const val DEFAULT_MAX_EXPANDED_BYTES: Long = 64L * 1024 * 1024
    private const val MAX_ACCEPTED_EXPANDED_BYTES: Long = 1024L * 1024 * 1024

    fun verify(
        stagedZip: File,
        limits: ZipPreflightLimits = ZipPreflightLimits(),
        maxExpandedBytes: Long = DEFAULT_MAX_EXPANDED_BYTES
    ): ZipPayloadVerification {
        require(maxExpandedBytes in 1..MAX_ACCEPTED_EXPANDED_BYTES) {
            "Invalid streamed verification byte budget"
        }
        val preview = ZipArchiveInventory.inspect(stagedZip, limits)
        if (preview.status == ZipPreflightStatus.BLOCKED)
            return blocked(preview.reasons.firstOrNull() ?: "Archive metadata is unsafe")
        if (preview.declaredOutputBytes != null && preview.declaredOutputBytes > maxExpandedBytes)
            return blocked("Declared ZIP output exceeds the streamed verification budget")

        return try {
            ZipFile(stagedZip).use verification@ { zip ->
                val buffer = ByteArray(32 * 1024)
                var files = 0
                var total = 0L
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    // Refuse missing checksums and sizes; silent partial verification
                    // would be worse than reporting a bounded unsupported archive.
                    if (entry.size < 0 || entry.crc < 0 || entry.compressedSize < 0)
                        return@verification blocked("ZIP member has unknown size or CRC")
                    files++
                    if (files > limits.maxEntries)
                        return@verification blocked("ZIP file count exceeds verification limit")
                    var actual = 0L
                    val crc = CRC32()
                    zip.getInputStream(entry).use { stream ->
                        while (true) {
                            if (Thread.currentThread().isInterrupted)
                                return@verification blocked("ZIP verification was interrupted")
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count == 0) return@verification blocked("ZIP stream made no progress")
                            if (count.toLong() > maxExpandedBytes - total)
                                return@verification blocked("Actual ZIP expansion exceeded byte budget")
                            if (count.toLong() > maxExpandedBytes - actual)
                                return@verification blocked("ZIP member exceeded verification byte budget")
                            total += count
                            actual += count
                            crc.update(buffer, 0, count)
                            val compressed = entry.compressedSize
                            if (compressed == 0L && actual > 0L ||
                                compressed > 0L && (actual / compressed > limits.maxRatio ||
                                    actual / compressed == limits.maxRatio &&
                                    actual % compressed > 0L))
                                return@verification blocked("Actual ZIP member expansion ratio exceeded limit")
                        }
                    }
                    if (actual != entry.size)
                        return@verification blocked("ZIP member actual output size differs from its header")
                    if (crc.value != entry.crc)
                        return@verification blocked("ZIP member CRC mismatch")
                }
                if (files != preview.fileCount)
                    return@verification blocked("ZIP file inventory changed during verification")
                ZipPayloadVerification(ZipPayloadStatus.VERIFIED_READ_ONLY, files, total, null)
            }
        } catch (_: IOException) {
            blocked("ZIP data was unreadable or incomplete")
        } catch (_: SecurityException) {
            blocked("ZIP file read permission was denied")
        }
    }

    private fun blocked(reason: String): ZipPayloadVerification =
        ZipPayloadVerification(ZipPayloadStatus.BLOCKED, 0, null, reason)
}
