package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.model.LocalQueueFilter

/**
 * One phone-only queue filter preference. Never carries transfer identity,
 * search text or command authority. A combined-view scope and the server's
 * own saved filter are intentionally separate presentation preferences.
 */
internal class LocalTransferFilterStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "chunks-local-transfer-filter-v1", Context.MODE_PRIVATE
    )

    fun load(): LocalQueueFilter {
        val raw = try { preferences.getString("filter", null) }
            catch (_: ClassCastException) { null }
        return decode(raw)
    }

    /** Commit on the explicit filter choice so an immediate process kill cannot lose it. */
    fun save(filter: LocalQueueFilter): Boolean =
        preferences.edit().putString("filter", filter.name).commit()

    companion object {
        fun decode(raw: String?): LocalQueueFilter =
            LocalQueueFilter.entries.firstOrNull { it.name == raw } ?: LocalQueueFilter.ALL
    }
}
