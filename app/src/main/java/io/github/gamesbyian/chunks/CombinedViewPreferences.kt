package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import io.github.gamesbyian.chunks.model.UnifiedTransferFilter
import io.github.gamesbyian.chunks.model.UnifiedSortDirection
import io.github.gamesbyian.chunks.model.UnifiedSortKey
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery

/**
 * Remembers the combined Transfers view (scope, filter, sort, direction, grouping)
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
        /** A saved scope, search or filter may hide every result without hiding the recovery. */
        fun narrowingActive(query: UnifiedTransferQuery): Boolean =
            query.search.isNotBlank() || query.filter != UnifiedTransferFilter.ALL ||
                query.scope !is EnvironmentViewScope.All

        /** View-only escape hatch; retain the user's chosen sorting and grouping. */
        fun showAll(query: UnifiedTransferQuery): UnifiedTransferQuery =
            query.copy(search = "", filter = UnifiedTransferFilter.ALL,
                scope = EnvironmentViewScope.All)

        /**
         * A saved view load can finish after the user has already touched the
         * screen. In that case keep the user's current input exactly; a stale
         * preference read must not replace a freshly chosen scope or search.
         * This rule has no command or Add-destination implications.
         */
        fun reconcileLoadedView(
            current: UnifiedTransferQuery,
            loaded: UnifiedTransferQuery,
            userInteracted: Boolean
        ): UnifiedTransferQuery = if (userInteracted) current else loaded

        /** Search text is deliberately not persisted. */
        fun encode(query: UnifiedTransferQuery): String {
            val scope = when (val s = query.scope) {
                EnvironmentViewScope.All -> "*"
                is EnvironmentViewScope.Only -> s.ids.map { it.value }.sorted().joinToString(",")
            }
            return listOf(scope, query.sortKey.name, query.direction.name,
                if (query.groupByEnvironment) "1" else "0", query.filter.name).joinToString(";")
        }

        /** Anything malformed restores the default view rather than a guessed scope. */
        fun decode(raw: String?): UnifiedTransferQuery {
            val parts = raw?.split(';')?.takeIf { it.size == 4 || it.size == 5 }
                ?: return UnifiedTransferQuery()
            val scope = if (parts[0] == "*") EnvironmentViewScope.All else {
                val raw = parts[0].split(',')
                // One unreadable ID means the saved subset is not what was chosen: use the default.
                if (raw.isEmpty() || raw.any { !TransferListSortStore.validEnvironment(it) } ||
                    raw.toSet().size != raw.size) return UnifiedTransferQuery()
                val ids = raw.map(::EnvironmentId).toSet()
                EnvironmentViewScope.Only(ids)
            }
            val key = UnifiedSortKey.entries.firstOrNull { it.name == parts[1] } ?: return UnifiedTransferQuery()
            val direction = UnifiedSortDirection.entries.firstOrNull { it.name == parts[2] }
                ?: return UnifiedTransferQuery()
            if (parts[3] != "0" && parts[3] != "1") return UnifiedTransferQuery()
            // Older four-field preferences had no filter; keep their scope and sort intact.
            val filter = if (parts.size == 4) UnifiedTransferFilter.ALL else
                UnifiedTransferFilter.entries.firstOrNull { it.name == parts[4] }
                    ?: return UnifiedTransferQuery()
            return UnifiedTransferQuery(scope = scope, sortKey = key, direction = direction,
                groupByEnvironment = parts[3] == "1", filter = filter)
        }
    }
}
