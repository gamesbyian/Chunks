package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.rpc.RemoteTransfer

/**
 * Observed rates are nullable, even when other fields on the same transfer
 * are known. A missing value is not a measured zero and a subtotal is not
 * a complete server traffic total.
 */
internal data class RemoteRateSubtotal(
    val reportedRows: Int,
    val unknownRows: Int,
    val knownBytesPerSecond: Long,
    val beyondRange: Boolean
) {
    fun tileLabel(): String = when {
        beyondRange -> "Above range"
        reportedRows == 0 -> "Not reported"
        unknownRows > 0 -> "Known " + Units.bytes(knownBytesPerSecond) + "/s"
        else -> Units.bytes(knownBytesPerSecond) + "/s"
    }

    fun caveat(kind: String): String? = when {
        beyondRange && unknownRows > 0 ->
            "$kind rates: sum exceeds the reporting range; $unknownRows transfer(s) also have no reported rate."
        beyondRange -> "$kind rates: sum exceeds the reporting range."
        unknownRows > 0 -> "$kind rates: $unknownRows transfer(s) not reported; tile is a known-only subtotal."
        else -> null
    }
}

internal object RemoteRateEvidence {
    private fun subtotal(
        transfers: List<RemoteTransfer>,
        rate: (RemoteTransfer) -> Long?
    ): RemoteRateSubtotal {
        var sum = 0L
        var observed = 0
        var unknown = 0
        var overflow = false
        for (transfer in transfers) {
            val value = rate(transfer)?.takeIf { it >= 0L }
            if (value == null) {
                unknown++
                continue
            }
            observed++
            if (value > Long.MAX_VALUE - sum) {
                sum = Long.MAX_VALUE
                overflow = true
            } else sum += value
        }
        return RemoteRateSubtotal(observed, unknown, sum, overflow)
    }

    fun download(transfers: List<RemoteTransfer>): RemoteRateSubtotal =
        subtotal(transfers) { it.downBytesPerSecond }

    fun upload(transfers: List<RemoteTransfer>): RemoteRateSubtotal =
        subtotal(transfers) { it.upBytesPerSecond }

    /**
     * Each fact gets its own line for 320dp/200%-font screens and screen readers.
     * Known zero, unknown, and genuinely negative/invalid remote values differ.
     */
    fun detailLines(transfer: RemoteTransfer): List<String> = listOf(
        "Download: " + (transfer.downBytesPerSecond?.takeIf { it >= 0L }?.let {
            Units.bytes(it) + "/s"
        } ?: "not reported"),
        "Upload: " + (transfer.upBytesPerSecond?.takeIf { it >= 0L }?.let {
            Units.bytes(it) + "/s"
        } ?: "not reported"),
        "Ratio: " + (transfer.ratioThousandths?.takeIf { it >= 0L }?.let {
            (it / 1000.0).toString()
        } ?: "not reported")
    )
}
