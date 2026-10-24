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
        order: TransferOrder,
        displayName: (RemoteTransfer) -> String = { it.name }
    ): List<RemoteTransfer> {
        val needle = query.trim()
        val subset = items.filter { item ->
            val matches = needle.isEmpty() ||
                displayName(item).contains(needle, ignoreCase = true) ||
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
            TransferOrder.NAME -> subset.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { displayName(it) })
            TransferOrder.PROGRESS -> subset.sortedWith(compareByDescending<RemoteTransfer> { it.fractionComplete }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { displayName(it) })
            TransferOrder.SIZE -> subset.sortedWith(compareByDescending<RemoteTransfer> { it.sizeBytes }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { displayName(it) })
            TransferOrder.SPEED -> subset.sortedWith(compareByDescending<RemoteTransfer> { it.downBytesPerSecond ?: 0 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { displayName(it) })
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
