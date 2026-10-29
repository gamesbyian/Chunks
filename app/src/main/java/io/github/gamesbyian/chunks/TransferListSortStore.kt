package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.model.UnifiedSortDirection

/**
 * Remembers each environment's own Transfers sort key and direction across
 * navigation and restart. Keyed by stable environment identity ("phone" or
 * a remembered server profile ID), so one server's order never leaks onto
 * another. A presentation preference only: it never targets a command.
 */
internal class TransferListSortStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "chunks-transfer-list-sort-v1", Context.MODE_PRIVATE
    )

    fun <K : Enum<K>> get(environment: String, keys: Array<K>): Pair<K, UnifiedSortDirection>? {
        if (!validEnvironment(environment)) return null
        val raw = try { preferences.getString("sort_" + environment, null) }
            catch (_: ClassCastException) { null }
        return decode(raw, keys)
    }

    fun <K : Enum<K>> set(environment: String, key: K, direction: UnifiedSortDirection): Boolean {
        if (!validEnvironment(environment)) return false
        return preferences.edit().putString("sort_" + environment, encode(key, direction)).commit()
    }

    companion object {
        const val PHONE = "phone"

        fun validEnvironment(id: String): Boolean =
            id == PHONE || id.matches(Regex("[a-f0-9]{64}"))

        fun encode(key: Enum<*>, direction: UnifiedSortDirection): String = key.name + ":" + direction.name

        /** Unknown or renamed values fall back to the screen default instead of guessing. */
        fun <K : Enum<K>> decode(raw: String?, keys: Array<K>): Pair<K, UnifiedSortDirection>? {
            val parts = raw?.split(':')?.takeIf { it.size == 2 } ?: return null
            val key = keys.firstOrNull { it.name == parts[0] } ?: return null
            val direction = UnifiedSortDirection.entries.firstOrNull { it.name == parts[1] } ?: return null
            return key to direction
        }
    }
}
