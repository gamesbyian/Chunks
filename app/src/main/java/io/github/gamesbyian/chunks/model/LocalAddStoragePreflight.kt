package io.github.gamesbyian.chunks.model

/**
 * Advisory-only projection for one reviewed local .torrent Add.
 *
 * A torrent's declared length is a worst-case *additional* allocation, not
 * observed physical growth: files may already exist or be sparse and a
 * multi-file download may store only selected files. Nothing here controls
 * native torrent writes, pauses, or deletes files.
 */
data class LocalAddStoragePreflight(
    val torrentBytes: Long,
    val existingLogicalBytes: Long?,
    val projectedLogicalMaximum: Long?,
    val folderCapBytes: Long?,
    val availableVolumeBytes: Long?,
    val projectedCapExceeded: Boolean?,
    val additionalBytesExceedFree: Boolean?
)

object LocalAddStoragePlanner {
    fun assess(
        torrentBytes: Long,
        folder: LocalFolderInventory?,
        capBytes: Long?,
        availableVolumeBytes: Long?
    ): LocalAddStoragePreflight {
        require(torrentBytes >= 0L)
        val used = folder?.logicalBytes?.takeIf { folder.complete && it >= 0L }
        val projected = used?.let { before ->
            try { Math.addExact(before, torrentBytes) } catch (_: ArithmeticException) { null }
        }
        val cap = capBytes?.takeIf { it > 0 }
        val free = availableVolumeBytes?.takeIf { it >= 0 }
        // Overflow produces an unknown projection, never a false 'within cap'.
        return LocalAddStoragePreflight(
            torrentBytes = torrentBytes,
            existingLogicalBytes = used,
            projectedLogicalMaximum = projected,
            folderCapBytes = cap,
            availableVolumeBytes = free,
            projectedCapExceeded = if (cap != null && projected != null) projected > cap else null,
            additionalBytesExceedFree = free?.let { torrentBytes > it }
        )
    }
}
