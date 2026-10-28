package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.time.Instant

/**
 * Offline support evidence from one accepted (or explicitly stale) UI frame.
 *
 * No engine/network calls. Never includes infohashes, paths, trackers,
 * endpoints, accounts, passkeys, error strings, or raw magnet URLs.
 * Original torrent titles are included only after an explicit per-export choice.
 */
internal object TransferHealthReport {
    const val MAX_ROWS = 512
    const val MAX_BYTES = 256 * 1024

    data class Prepared(
        val bytes: ByteArray,
        val observed: Int,
        val reported: Int,
        val omitted: Int,
        val includesNames: Boolean
    )

    private fun safeCount(n: Long) = n.coerceAtLeast(0L)
    private fun safePpm(n: Int) = n.coerceIn(0, 1_000_000)

    private fun jsonQuoted(value: String): String = buildString(value.length + 8) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 32 || ch.code in 0x7F..0x9F ||
                    ch.code in 0x202A..0x202E || ch.code in 0x2066..0x2069 ||
                    ch.code == 0x2028 || ch.code == 0x2029)
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else append(ch)
            }
        }
        append('"')
    }

    private fun title(value: String) = jsonQuoted(value.take(140))
    private fun knownRate(rate: Long?): String = when {
        rate == null || rate < 0L -> "unreported"
        rate == 0L -> "zero"
        else -> "positive"
    }

    fun remote(
        transfers: List<RemoteTransfer>,
        serverView: String,
        stale: Boolean,
        includeTitles: Boolean = false,
        capturedAt: Instant = Instant.now()
    ): Prepared = build("rtorrent", transfers.size, includeTitles) { out ->
        val view = serverView.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,48}")) } ?: "custom"
        out.append(",\"serverView\":").append(jsonQuoted(view))
        out.append(",\"fresh\":").append(!stale)
        out.append(",\"observationCaveat\":").append(jsonQuoted(
            "rTorrent started is intent, not proof of active peers; zero or unknown rate is one sample, not evidence of future progress."))
        out.append(",\"entries\":[")
        transfers.take(MAX_ROWS).forEachIndexed { index, item ->
            if (index > 0) out.append(',')
            out.append("{\"index\":").append(index)
                .append(",\"startedFlag\":").append(item.started)
                .append(",\"complete\":").append(
                    item.sizeBytes > 0L && item.completedBytes >= item.sizeBytes)
                .append(",\"totalBytes\":").append(safeCount(item.sizeBytes))
                .append(",\"completedBytes\":").append(safeCount(item.completedBytes))
                .append(",\"downloadRateState\":").append(jsonQuoted(knownRate(item.downBytesPerSecond)))
                .append(",\"uploadRateState\":").append(jsonQuoted(knownRate(item.upBytesPerSecond)))
            item.downBytesPerSecond?.takeIf { it >= 0L }?.let {
                out.append(",\"downloadBytesPerSecond\":").append(it)
            }
            item.upBytesPerSecond?.takeIf { it >= 0L }?.let {
                out.append(",\"uploadBytesPerSecond\":").append(it)
            }
            if (includeTitles) out.append(",\"title\":").append(title(item.name))
            out.append('}')
        }
        out.append(']')
    }

    fun local(
        transfers: List<LocalTransferStatus>,
        stale: Boolean,
        includeTitles: Boolean = false,
        capturedAt: Instant = Instant.now()
    ): Prepared = build("local", transfers.size, includeTitles) { out ->
        out.append(",\"fresh\":").append(!stale)
        out.append(",\"observationCaveat\":").append(jsonQuoted(
            "Native status and peer count are one observation. Download counters are not a claim of newly hash-verified payload growth."))
        out.append(",\"entries\":[")
        transfers.take(MAX_ROWS).forEachIndexed { index, item ->
            if (index > 0) out.append(',')
            out.append("{\"index\":").append(index)
                .append(",\"state\":").append(jsonQuoted(item.state.name))
                .append(",\"stopped\":").append(item.stopped)
                .append(",\"metadataKnown\":").append(item.metadataKnown)
                .append(",\"uploadOnly\":").append(item.uploadOnly)
                .append(",\"errorPresent\":").append(!item.error.isNullOrBlank())
                .append(",\"peersObserved\":").append(item.peers.coerceAtLeast(0))
                .append(",\"progressPpm\":").append(safePpm(item.progressPpm))
                .append(",\"wantedBytes\":").append(safeCount(item.wantedBytes))
                .append(",\"verifiedCompletedBytes\":").append(safeCount(item.doneBytes))
                .append(",\"allTimePayloadDownloadedBytes\":")
                .append(safeCount(item.payloadDownloadedBytes))
            if (includeTitles) out.append(",\"title\":").append(title(item.name))
            out.append('}')
        }
        out.append(']')
    }

    private fun build(
        source: String,
        observed: Int,
        includeTitles: Boolean,
        writeRows: (StringBuilder) -> Unit
    ): Prepared {
        require(observed >= 0)
        val out = StringBuilder()
        out.append("{\"schema\":\"chunks-health-v1\",\"source\":")
            .append(jsonQuoted(source))
            .append(",\"capturedAtUtc\":").append(jsonQuoted(Instant.now().toString()))
            .append(",\"identitiesOmitted\":true")
            .append(",\"torrentTitlesIncluded\":").append(includeTitles)
            .append(",\"observed\":").append(observed)
            .append(",\"reported\":").append(observed.coerceAtMost(MAX_ROWS))
            .append(",\"omittedDueToLimit\":").append((observed - MAX_ROWS).coerceAtLeast(0))
        writeRows(out)
        out.append('}')
        val bytes = out.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_BYTES) { "Health report exceeds safe document bound" }
        return Prepared(bytes, observed, observed.coerceAtMost(MAX_ROWS),
            (observed - MAX_ROWS).coerceAtLeast(0), includeTitles)
    }
}
