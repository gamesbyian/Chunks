package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import io.github.gamesbyian.chunks.model.UnifiedSortDirection
import io.github.gamesbyian.chunks.model.UnifiedSortKey
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery

/**
 * Remembers the combined Transfers view (scope, sort, direction, grouping)
 * across restart. A saved subset keeps its exact environment IDs; dynamic
 * All is stored as All so newly enabled environments join it. View state
 * only: it is never an Add destination or command target.
 */
internal class CombinedViewPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "chunks-combined-view-v1", Context.MODE_PRIVATE
    )

    fun load(): UnifiedTransferQuery = decode(
        try { preferences.getString("view", null) } catch (_: ClassCastException) { null })

    fun save(query: UnifiedTransferQuery): Boolean =
        preferences.edit().putString("view", encode(query)).commit()

    companion object {
        private val validId = Regex("phone|[a-f0-9]{64}")

        /** Search text is deliberately not persisted. */
        fun encode(query: UnifiedTransferQuery): String {
            val scope = when (val s = query.scope) {
                EnvironmentViewScope.All -> "*"
                is EnvironmentViewScope.Only -> s.ids.map { it.value }.sorted().joinToString(",")
            }
            return listOf(scope, query.sortKey.name, query.direction.name,
                if (query.groupByEnvironment) "1" else "0").joinToString(";")
        }

        /** Anything malformed restores the default view rather than a guessed scope. */
        fun decode(raw: String?): UnifiedTransferQuery {
            val parts = raw?.split(';')?.takeIf { it.size == 4 } ?: return UnifiedTransferQuery()
            val scope = if (parts[0] == "*") EnvironmentViewScope.All else {
                val ids = parts[0].split(',').filter { it.matches(validId) }.map(::EnvironmentId).toSet()
                if (ids.isEmpty()) return UnifiedTransferQuery()
                EnvironmentViewScope.Only(ids)
            }
            val key = UnifiedSortKey.entries.firstOrNull { it.name == parts[1] } ?: return UnifiedTransferQuery()
            val direction = UnifiedSortDirection.entries.firstOrNull { it.name == parts[2] }
                ?: return UnifiedTransferQuery()
            if (parts[3] != "0" && parts[3] != "1") return UnifiedTransferQuery()
            return UnifiedTransferQuery(scope = scope, sortKey = key, direction = direction,
                groupByEnvironment = parts[3] == "1")
        }
    }
}
