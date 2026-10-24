package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.organize.Classifier

enum class TransferFilter(val label: String) {
    ALL("All"), ACTIVE("Active"), COMPLETE("Complete"), STOPPED("Stopped"), NEEDS_SORTING("Needs sorting")
}

enum class TransferOrder(val label: String) {
    NAME("Name"), PROGRESS("Progress"), SIZE("Size"), SPEED("Speed")
}

object TransferCatalog {
    fun visible(
        items: List<RemoteTransfer>,
        query: String,
        filter: TransferFilter,
        order: TransferOrder
    ): List<RemoteTransfer> {
        val needle = query.trim()
        val subset = items.filter { item ->
            val matches = needle.isEmpty() ||
                item.name.contains(needle, ignoreCase = true) ||
                item.directory.contains(needle, ignoreCase = true)
            val passes = when (filter) {
                TransferFilter.ALL -> true
                TransferFilter.ACTIVE -> item.started
                TransferFilter.COMPLETE -> item.sizeBytes > 0 && item.completedBytes >= item.sizeBytes
                TransferFilter.STOPPED -> !item.started
                TransferFilter.NEEDS_SORTING -> Classifier.suggest(item.name).category == "Unsorted"
            }
            matches && passes
        }
        return when (order) {
            TransferOrder.NAME -> subset.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            TransferOrder.PROGRESS -> subset.sortedWith(compareByDescending<RemoteTransfer> { it.fractionComplete }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            TransferOrder.SIZE -> subset.sortedWith(compareByDescending<RemoteTransfer> { it.sizeBytes }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            TransferOrder.SPEED -> subset.sortedWith(compareByDescending<RemoteTransfer> { it.downBytesPerSecond ?: 0 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
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
    val looksLikeMagnet: Boolean get() =
        magnet.startsWith("magnet:?") && Regex("(?i)(?:[?&])xt=urn:btih:[A-Za-z0-9]+(?:&|$)").containsMatchIn(magnet)
    val hasDestination: Boolean get() = destination.startsWith("/") && !destination.contains('\n')
    val canPreview: Boolean get() = looksLikeMagnet && hasDestination
}
