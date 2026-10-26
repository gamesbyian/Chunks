package io.github.gamesbyian.chunks.model

import java.math.BigDecimal

/**
 * Decimal GiB input for an OPTIONAL, advisory-only local download-folder limit.
 * Never feeds filesystem delete or libtorrent pause/resume commands.
 */
object LocalCapInput {
    private val decimal = Regex("(?:[1-9][0-9]{0,10}|0)(?:\\.[0-9]{1,3})?")
    private val gib = BigDecimal(1024L * 1024 * 1024)

    fun parseGiB(text: String): Long? {
        val trimmed = text.trim()
        if (!decimal.matches(trimmed)) return null
        val bytes = BigDecimal(trimmed).multiply(gib)
        if (bytes < BigDecimal.ONE || bytes > BigDecimal.valueOf(Long.MAX_VALUE)) return null
        return bytes.toLong()
    }

    fun formatGiB(bytes: Long): String {
        require(bytes > 0)
        return BigDecimal.valueOf(bytes).divide(gib, 3, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
    }

    /** Incomplete/inaccessible scan is unknown, never a precise quota state. */
    fun assessment(capBytes: Long?, folder: LocalFolderInventory?, freeBytes: Long?): StorageAssessment {
        val complete = folder?.complete == true && folder.logicalBytes != null
        val used = folder?.logicalBytes?.takeIf { complete }
        return StorageBudgetAdvisor.assess(StorageBudget("phone-app-download-folder", capBytes, used, freeBytes))
    }
}
