package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.util.Locale

/**
 * A read-only common language for transfer lists. Engine commands continue to
 * use their existing identity, permission and readback gates.
 */
data class EnvironmentId(val value: String) {
    init {
        require(value.isNotBlank() && value == value.trim()) { "Environment ID must be stable and nonblank" }
    }
}

data class UnifiedTransferKey private constructor(val environment: EnvironmentId, val infoHash: String) {
    companion object {
        fun of(environment: EnvironmentId, hash: String): UnifiedTransferKey {
            val normalized = hash.lowercase(Locale.ROOT)
            require(normalized.matches(Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})"))) {
                "Transfer identity must be a valid 40- or 64-digit hex infohash"
            }
            return UnifiedTransferKey(environment, normalized)
        }
    }
}

/**
 * Nullable facts mean UNKNOWN. In particular, an unobserved remote rate
 * cannot become a measured zero, and a metadata-less magnet is not complete.
 */
data class UnifiedTransferObservation(
    val key: UnifiedTransferKey,
    val displayName: String,
    val originalName: String,
    val sizeBytes: Long?,
    val progressPpm: Int?,
    val started: Boolean?,
    val downloadBytesPerSecond: Long?,
    val uploadBytesPerSecond: Long?,
    val peers: Int?,
    val addedAtEpochMillis: Long? = null,
    val hasError: Boolean = false
) {
    init {
        require(sizeBytes == null || sizeBytes >= 0)
        require(progressPpm == null || progressPpm in 0..1_000_000)
        require(downloadBytesPerSecond == null || downloadBytesPerSecond >= 0)
        require(uploadBytesPerSecond == null || uploadBytesPerSecond >= 0)
        require(peers == null || peers >= 0)
    }
}

enum class EnvironmentAvailability { LIVE, STALE, OFFLINE, LOCKED }

data class EnvironmentTransferSnapshot(
    val environment: EnvironmentId,
    val availability: EnvironmentAvailability,
    val inventoryComplete: Boolean,
    val observedAtEpochMillis: Long?,
    val transfers: List<UnifiedTransferObservation>
) {
    init {
        require(transfers.all { it.key.environment == environment }) {
            "One source cannot report another environment's transfer"
        }
        require(transfers.map { it.key }.toSet().size == transfers.size) {
            "Duplicate same-environment transfer identity is ambiguous"
        }
    }

    /** A LIVE label alone is not a verified read: timestamp-less inventories remain unconfirmed. */
    val canClaimCompleteLiveInventory: Boolean
        get() = inventoryComplete && observedAtEpochMillis != null &&
            availability == EnvironmentAvailability.LIVE
}

sealed class EnvironmentViewScope {
    /** Dynamic All includes environments added after this view was saved. */
    object All : EnvironmentViewScope()
    data class Only(val ids: Set<EnvironmentId>) : EnvironmentViewScope() {
        init { require(ids.isNotEmpty()) { "A saved subset must be nonempty" } }
    }
}

enum class UnifiedTransferFilter { ALL, STARTED, STOPPED, COMPLETE, TRANSFERRING, ATTENTION }
enum class UnifiedSortKey { NAME, ADDED_AT, SIZE, PROGRESS, DOWNLOAD_SPEED, UPLOAD_SPEED, PEERS }
enum class UnifiedSortDirection { ASCENDING, DESCENDING }

data class UnifiedTransferQuery(
    val scope: EnvironmentViewScope = EnvironmentViewScope.All,
    val search: String = "",
    val filter: UnifiedTransferFilter = UnifiedTransferFilter.ALL,
    val sortKey: UnifiedSortKey = UnifiedSortKey.NAME,
    val direction: UnifiedSortDirection = UnifiedSortDirection.ASCENDING,
    val groupByEnvironment: Boolean = false,
    /** Only orders group headers, never reinterprets the transfer sort key. */
    val environmentOrder: List<EnvironmentId> = emptyList()
)

data class UnifiedTransferGroup(
    val source: EnvironmentTransferSnapshot,
    val transfers: List<UnifiedTransferObservation>
)

data class UnifiedTransferQueryResult(
    val transfers: List<UnifiedTransferObservation>,
    /** Selected sources, including those with no matching transfers. */
    val sources: List<EnvironmentTransferSnapshot>,
    val groups: List<UnifiedTransferGroup>,
    /** A saved subset may mention a disabled/missing environment. Do not silently retarget it. */
    val missingSelectedEnvironments: Set<EnvironmentId>
) {
    val hasIncompleteOrStaleSource: Boolean
        get() = missingSelectedEnvironments.isNotEmpty() ||
            sources.any { !it.canClaimCompleteLiveInventory }
}

/** Pure adapters; no network access, mutations, optimistic status or command authority. */
object UnifiedTransferObservations {
    fun fromPhone(environment: EnvironmentId, item: LocalTransferStatus) =
        UnifiedTransferObservation(
            key = UnifiedTransferKey.of(environment, item.infoHash),
            displayName = item.name,
            originalName = item.name,
            sizeBytes = item.wantedBytes.takeIf { item.metadataKnown && it > 0 },
            progressPpm = item.progressPpm.takeIf { item.metadataKnown },
            started = !item.stopped,
            downloadBytesPerSecond = item.downloadPayloadRateBytesPerSecond.toLong().takeIf { it >= 0 },
            uploadBytesPerSecond = item.uploadPayloadRateBytesPerSecond.toLong().takeIf { it >= 0 },
            peers = item.peers.takeIf { it >= 0 },
            addedAtEpochMillis = item.addedAtEpochMillis,
            hasError = !item.error.isNullOrBlank()
        )

    fun fromRTorrent(environment: EnvironmentId, item: RemoteTransfer) =
        UnifiedTransferObservation(
            key = UnifiedTransferKey.of(environment, item.hash),
            displayName = item.name,
            originalName = item.name,
            sizeBytes = item.sizeBytes.takeIf { it > 0 },
            progressPpm = if (item.sizeBytes > 0) {
                (item.completedBytes.toDouble() / item.sizeBytes * 1_000_000)
                    .toInt().coerceIn(0, 1_000_000)
            } else null,
            started = item.started,
            downloadBytesPerSecond = item.downBytesPerSecond?.takeIf { it >= 0 },
            uploadBytesPerSecond = item.upBytesPerSecond?.takeIf { it >= 0 },
            peers = null
        )
}

/**
 * One selected union, filtered once, then globally sorted or partitioned.
 * Null sort facts are last in BOTH directions. Ties always use stable exact
 * environment/hash identity, not list position or a mutable display label.
 */
object UnifiedTransferCatalog {
    fun query(snapshots: List<EnvironmentTransferSnapshot>, query: UnifiedTransferQuery):
        UnifiedTransferQueryResult {
        require(snapshots.map { it.environment }.toSet().size == snapshots.size) {
            "Duplicate environment snapshots cannot be merged by guessing"
        }
        val selected = when (val scope = query.scope) {
            EnvironmentViewScope.All -> snapshots
            is EnvironmentViewScope.Only -> snapshots.filter { it.environment in scope.ids }
        }
        val missing = when (val scope = query.scope) {
            EnvironmentViewScope.All -> emptySet()
            is EnvironmentViewScope.Only -> scope.ids - selected.map { it.environment }.toSet()
        }
        val needle = query.search.trim()
        val filtered = selected.flatMap { it.transfers }.filter { item ->
            (needle.isEmpty() || item.displayName.contains(needle, ignoreCase = true) ||
                item.originalName.contains(needle, ignoreCase = true) ||
                item.key.infoHash.contains(needle, ignoreCase = true)) &&
                when (query.filter) {
                    UnifiedTransferFilter.ALL -> true
                    UnifiedTransferFilter.STARTED -> item.started == true
                    UnifiedTransferFilter.STOPPED -> item.started == false
                    UnifiedTransferFilter.COMPLETE -> item.progressPpm == 1_000_000
                    UnifiedTransferFilter.TRANSFERRING ->
                        (item.downloadBytesPerSecond ?: 0) > 0 ||
                        (item.uploadBytesPerSecond ?: 0) > 0
                    UnifiedTransferFilter.ATTENTION -> item.hasError
                }
        }
        val comparator = comparator(query.sortKey, query.direction)
        if (!query.groupByEnvironment) {
            return UnifiedTransferQueryResult(filtered.sortedWith(comparator), selected, emptyList(), missing)
        }

        // Index once instead of scanning the full combined list for every server.
        // Many saved environments and large rTorrent queues otherwise multiply work.
        val byEnvironment = filtered.groupBy { it.key.environment }
        val groups = selected.sortedWith(compareBy<EnvironmentTransferSnapshot> {
            val position = query.environmentOrder.indexOf(it.environment)
            if (position < 0) Int.MAX_VALUE else position
        }.thenBy { it.environment.value }).map { source ->
            UnifiedTransferGroup(source, byEnvironment[source.environment].orEmpty()
                .sortedWith(comparator))
        }
        return UnifiedTransferQueryResult(groups.flatMap { it.transfers }, selected, groups, missing)
    }

    private fun comparator(key: UnifiedSortKey, direction: UnifiedSortDirection):
        Comparator<UnifiedTransferObservation> = Comparator { a, b ->
        val primary = when (key) {
            UnifiedSortKey.NAME -> TransferSortOrdering.compareKnown(a.displayName.lowercase(Locale.ROOT),
                b.displayName.lowercase(Locale.ROOT), direction)
            else -> {
                fun value(item: UnifiedTransferObservation): Long? = when (key) {
                    UnifiedSortKey.ADDED_AT -> item.addedAtEpochMillis
                    UnifiedSortKey.SIZE -> item.sizeBytes
                    UnifiedSortKey.PROGRESS -> item.progressPpm?.toLong()
                    UnifiedSortKey.DOWNLOAD_SPEED -> item.downloadBytesPerSecond
                    UnifiedSortKey.UPLOAD_SPEED -> item.uploadBytesPerSecond
                    UnifiedSortKey.PEERS -> item.peers?.toLong()
                    UnifiedSortKey.NAME -> null
                }
                TransferSortOrdering.compareKnown(value(a), value(b), direction)
            }
        }
        if (primary != 0) primary
        else a.key.environment.value.compareTo(b.key.environment.value).takeIf { it != 0 }
            ?: a.key.infoHash.compareTo(b.key.infoHash)
    }

}
