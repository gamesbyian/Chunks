package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import java.util.LinkedHashMap

/**
 * Observed (not inferred) growth of a torrent's libtorrent counters.
 *
 * Only call after a genuinely fresh admitted native inventory observation.
 * This never polls JNI or starts transfers. A new process, rolled-back clock,
 * changed torrent identity or counter reset cannot reuse an old baseline.
 */
internal class LocalProgressHistory(
    private val maxTracked: Int = 512,
    private val horizonMs: Long = 60_000L,
    private val minWindowMs: Long = 3_000L
) {
    init {
        require(maxTracked in 1..4096)
        require(minWindowMs > 0 && horizonMs >= minWindowMs)
    }

    internal data class ObservedDelta(
        val seconds: Long,
        val verifiedBytes: Long,
        val peerPayloadBytes: Long,
        val uploadedBytes: Long,
        val peersNow: Int,
        val metadataKnown: Boolean
    )

    private data class Point(
        val millis: Long,
        val verifiedBytes: Long,
        val peerPayloadBytes: Long,
        val uploadedBytes: Long
    ) {
        fun precedes(other: Point): Boolean =
            millis < other.millis &&
                verifiedBytes <= other.verifiedBytes &&
                peerPayloadBytes <= other.peerPayloadBytes &&
                uploadedBytes <= other.uploadedBytes
    }

    private class Window(
        var first: Point,
        var last: Point,
        var validWindows: Int = 0
    )

    private val history = LinkedHashMap<String, Window>(16, 0.75f, true)

    fun clear() = history.clear()

    /**
     * Each accepted whole-list sample is one frame. The response only includes
     * hashes present in this specific fresh frame, never stale prior records.
     */
    fun observe(items: List<LocalTransferStatus>, elapsedMs: Long): Map<String, ObservedDelta> {
        if (elapsedMs < 0 || items.size > maxTracked ||
            items.map { it.infoHash }.toSet().size != items.size) {
            clear()
            return emptyMap()
        }
        val seen = items.mapTo(HashSet()) { it.infoHash }
        history.keys.retainAll(seen)
        val result = LinkedHashMap<String, ObservedDelta>()
        for (item in items) {
            val hash = item.infoHash
            if (!Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})").matches(hash) ||
                item.doneBytes < 0 || item.payloadDownloadedBytes < 0 ||
                item.payloadUploadedBytes < 0) {
                history.remove(hash)
                continue
            }
            val next = Point(elapsedMs, item.doneBytes,
                item.payloadDownloadedBytes, item.payloadUploadedBytes)
            val existing = history[hash]
            if (existing == null || !existing.last.precedes(next)) {
                history[hash] = Window(next, next)
                continue
            }
            if (next.millis - existing.first.millis > horizonMs) {
                // An old counter sample cannot provide a bounded-rate claim.
                // Begin a new measured window from this admitted observation.
                existing.first = next
                existing.last = next
                continue
            }
            existing.last = next
            val first = existing.first
            val elapsed = next.millis - first.millis
            if (elapsed < minWindowMs) continue
            val verifiedGrowth = next.verifiedBytes - first.verifiedBytes
            val peerGrowth = next.peerPayloadBytes - first.peerPayloadBytes
            val uploadGrowth = next.uploadedBytes - first.uploadedBytes
            result[hash] = ObservedDelta(
                seconds = elapsed / 1_000L,
                verifiedBytes = verifiedGrowth,
                peerPayloadBytes = peerGrowth,
                uploadedBytes = uploadGrowth,
                peersNow = item.peers,
                metadataKnown = item.metadataKnown
            )
        }
        // Bound age and memory even when native inventory churns.
        while (history.size > maxTracked) history.remove(history.keys.first())
        return result
    }
}
