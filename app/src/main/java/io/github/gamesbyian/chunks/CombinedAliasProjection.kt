package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferKey

/**
 * Presentation-only lookup identity. A torrent hash without its environment and
 * authenticated alias scope can never select the right user's label.
 */
internal data class CombinedAliasRequest(
    val key: UnifiedTransferKey,
    val aliasScope: String
) {
    init { require(aliasScope.isNotBlank()) { "Alias lookups need an explicit environment scope" } }
}

/**
 * Pure read-only alias projection. Disk access belongs to the asynchronous
 * alias loader, never composition, LazyColumn or a transfer-row callback.
 */
internal object CombinedAliasProjection {
    fun requests(
        sources: List<EnvironmentTransferSnapshot>,
        scopes: Map<EnvironmentId, String>
    ): Set<CombinedAliasRequest> = buildSet {
        sources.forEach { source ->
            if (source.availability == EnvironmentAvailability.LOCKED) return@forEach
            val scope = scopes[source.environment]?.takeIf { it.isNotBlank() } ?: return@forEach
            source.transfers.forEach { row ->
                add(CombinedAliasRequest(row.key, scope))
            }
        }
    }

    fun apply(
        sources: List<EnvironmentTransferSnapshot>,
        scopes: Map<EnvironmentId, String>,
        aliases: Map<CombinedAliasRequest, String>
    ): List<EnvironmentTransferSnapshot> = sources.map { source ->
        if (source.availability == EnvironmentAvailability.LOCKED) return@map source
        val scope = scopes[source.environment]?.takeIf { it.isNotBlank() } ?: return@map source
        source.copy(transfers = source.transfers.map { row ->
            val alias = aliases[CombinedAliasRequest(row.key, scope)]
            if (alias == null) row else row.copy(displayName = alias)
        })
    }
}
