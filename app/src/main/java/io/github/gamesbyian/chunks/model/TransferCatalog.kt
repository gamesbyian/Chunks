package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.organize.Classifier

enum class TransferFilter(val label: String) {
    ALL("All"), ACTIVE("Start set"), TRANSFERRING("Traffic now"),
    ZERO_DOWNLOAD("0 B/s down"), COMPLETE("Complete"), STOPPED("Stopped"),
    NEEDS_SORTING("Needs sorting")
}

enum class TransferOrder(val label: String, internal val kind: TransferSortOrdering.Kind) {
    NAME("Name", TransferSortOrdering.Kind.NAME),
    PROGRESS("Progress", TransferSortOrdering.Kind.PROGRESS),
    SIZE("Size", TransferSortOrdering.Kind.SIZE),
    /** Observed download rate. */
    SPEED("Speed", TransferSortOrdering.Kind.SPEED);

    val naturalDirection: UnifiedSortDirection get() = TransferSortOrdering.naturalDirection(kind)
    fun directionLabel(direction: UnifiedSortDirection): String = TransferSortOrdering.label(kind, direction)
}

object TransferCatalog {
    fun visible(
        items: List<RemoteTransfer>,
        query: String,
        filter: TransferFilter,
        order: TransferOrder,
        direction: UnifiedSortDirection = order.naturalDirection,
        displayName: (RemoteTransfer) -> String = { it.name }
    ): List<RemoteTransfer> {
        val needle = query.trim()
        // Resolve a potentially persisted alias exactly once per passing transfer.
        // Sorting a large remote queue must not rehash/read SharedPreferences on every
        // comparator invocation (O(N log N) alias lookups for ordinary name ordering).
        val subset = items.asSequence().filter { item ->
            when (filter) {
                TransferFilter.ALL -> true
                TransferFilter.ACTIVE -> item.started
                // Positive observed rates are evidence of traffic for one
                // loaded view; a missing rate is unknown, not measured zero.
                TransferFilter.TRANSFERRING ->
                    (item.downBytesPerSecond ?: 0L) > 0L ||
                    (item.upBytesPerSecond ?: 0L) > 0L
                // This is a diagnostic snapshot filter, not proof of a failed
                // tracker, dead swarm, or an inactive rTorrent engine.
                TransferFilter.ZERO_DOWNLOAD ->
                    item.started && item.sizeBytes > item.completedBytes &&
                        item.downBytesPerSecond == 0L
                TransferFilter.COMPLETE -> item.sizeBytes > 0 && item.completedBytes >= item.sizeBytes
                TransferFilter.STOPPED -> !item.started
                TransferFilter.NEEDS_SORTING -> Classifier.suggest(item.name).category == "Unsorted"
            }
        }.map { item -> item to displayName(item) }.filter { (item, label) ->
            needle.isEmpty() ||
                label.contains(needle, ignoreCase = true) ||
                item.name.contains(needle, ignoreCase = true) ||
                item.directory.contains(needle, ignoreCase = true)
        }.toList()

        // A zero-size (metadata-less) job has UNKNOWN size/progress and an
        // unreported rate is UNKNOWN, not 0 B/s: both stay last in either direction.
        fun known(item: RemoteTransfer): Long? = when (order) {
            TransferOrder.NAME -> null
            TransferOrder.PROGRESS -> item.completedBytes.coerceIn(0, item.sizeBytes)
                .takeIf { item.sizeBytes > 0 }?.let { it * 1_000_000 / item.sizeBytes }
            TransferOrder.SIZE -> item.sizeBytes.takeIf { it > 0 }
            TransferOrder.SPEED -> item.downBytesPerSecond?.takeIf { it >= 0 }
        }
        val ordered = subset.sortedWith(Comparator { x, y ->
            val primary = if (order == TransferOrder.NAME)
                TransferSortOrdering.compareNames(x.second, y.second, direction)
            else TransferSortOrdering.compareKnown(known(x.first), known(y.first), direction)
            if (primary != 0) primary
            // Fixed A–Z then exact hash: ties never flip with the direction toggle.
            else String.CASE_INSENSITIVE_ORDER.compare(x.second, y.second).takeIf { it != 0 }
                ?: x.first.hash.compareTo(y.first.hash)
        })
        return ordered.map { it.first }
    }

    fun places(items: List<RemoteTransfer>): List<Pair<String, Int>> =
        items.groupingBy { it.directory.ifBlank { "(server location not reported)" } }
            .eachCount().toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    fun discoveredCollections(items: List<RemoteTransfer>): List<Pair<String, Int>> =
        items.map { Classifier.suggest(it.name).collection }
            .filterNotNull().groupingBy { it }.eachCount().toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
}

object Units {
    fun bytes(size: Long): String {
        val safe = size.coerceAtLeast(0)
        val values = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
        var result = safe.toDouble()
        var index = 0
        while (result >= 1024 && index < values.lastIndex) {
            result /= 1024.0
            index++
        }
        return if (index == 0) "${safe} B" else String.format(java.util.Locale.US, "%.1f %s", result, values[index])
    }
}

data class AddDraft(val magnet: String, val destination: String) {
    val looksLikeMagnet: Boolean get() {
        val url = magnet.trim()
        if (url.length > 8192 || !url.startsWith("magnet:?", ignoreCase = true)) return false
        val xt = url.substringAfter("?").split("&").firstOrNull {
            it.startsWith("xt=", ignoreCase = true)
        }?.substringAfter("=") ?: return false
        val decoded = try {
            java.net.URLDecoder.decode(xt, "UTF-8")
        } catch (_: IllegalArgumentException) {
            return false
        }
        return when {
            decoded.startsWith("urn:btih:", ignoreCase = true) -> {
                val hash = decoded.substringAfterLast(":")
                Regex("[a-fA-F0-9]{40}").matches(hash) ||
                    Regex("[A-Za-z2-7]{32}").matches(hash)
            }
            decoded.startsWith("urn:btmh:", ignoreCase = true) -> {
                Regex("(?i)1220[a-f0-9]{64}").matches(decoded.substringAfterLast(":"))
            }
            else -> false
        }
    }

    val hasDestination: Boolean get() {
        val name = destination.trim()
        return name.startsWith("/") && name.length <= 4096 &&
            name.none { it.code < 32 } && name.split("/").none { it == ".." }
    }

    val canPreview: Boolean get() = looksLikeMagnet && hasDestination
}
