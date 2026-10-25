package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Read-only central-directory inspection for a *local staged file*.
 *
 * No member payload is decompressed, no destination is opened and no files are
 * written. The returned preflight is advisory and is NOT permission to extract.
 * SAF document URIs and actual streaming integrity are separate Android gates.
 */
object ZipArchiveInventory {
    const val DEFAULT_MAX_INPUT_BYTES: Long = 256L * 1024 * 1024

    fun inspect(
        stagedZip: File,
        limits: ZipPreflightLimits = ZipPreflightLimits(),
        maxInputBytes: Long = DEFAULT_MAX_INPUT_BYTES
    ): ZipPreflightResult {
        require(maxInputBytes > 0) { "Invalid input byte limit" }
        val length = stagedZip.length()
        if (!stagedZip.isFile || length <= 0 || length > maxInputBytes)
            return blocked("ZIP file is missing, empty or beyond the local preview size limit")

        return try {
            // ZipEntry omits Unix file-type metadata. Refuse links/special files
            // before reporting an archive eligible for further streaming checks.
            val typeFailure = ZipCentralDirectoryTypes.rejectReason(stagedZip, limits.maxEntries)
            if (typeFailure != null) return blocked(typeFailure)
            ZipFile(stagedZip).use { archive ->
                val entries = ArrayList<ZipMemberDescriptor>()
                val members = archive.entries()
                while (members.hasMoreElements() && entries.size <= limits.maxEntries) {
                    val member = members.nextElement()
                    entries += ZipMemberDescriptor(
                        rawName = member.name,
                        directory = member.isDirectory,
                        declaredSizeBytes = member.size.takeIf { it >= 0 },
                        compressedSizeBytes = member.compressedSize.takeIf { it >= 0 }
                    )
                }
                ZipEntryPreflight.inspect(entries, limits)
            }
        } catch (_: IOException) {
            blocked("ZIP central directory is invalid or could not be read")
        } catch (_: SecurityException) {
            blocked("ZIP file cannot be read with the current permissions")
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
