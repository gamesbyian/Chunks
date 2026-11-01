package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.model.TransferFilter

/**
 * Remember the per-account remote Transfers filter, using the same exact
 * stable server profile identity as sort and environment color preferences.
 * Filters control *presentation only*: never infer a command target from one.
 * Search text is intentionally transient and is never saved.
 */
internal class RemoteTransferFilterStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "chunks-remote-transfer-filter-v1", Context.MODE_PRIVATE
    )

    fun load(environment: String?): TransferFilter {
        if (environment == null || !TransferListSortStore.validEnvironment(environment) ||
            environment == TransferListSortStore.PHONE) return TransferFilter.ALL
        val raw = try { preferences.getString("filter_" + environment, null) }
            catch (_: ClassCastException) { null }
        return decode(raw)
    }

    fun save(environment: String?, filter: TransferFilter): Boolean {
        if (environment == null || !TransferListSortStore.validEnvironment(environment) ||
            environment == TransferListSortStore.PHONE) return false
        return preferences.edit().putString("filter_" + environment, filter.name).commit()
    }

    companion object {
        fun decode(raw: String?): TransferFilter =
            TransferFilter.entries.firstOrNull { it.name == raw } ?: TransferFilter.ALL
    }
}

/** A filtered result is not evidence that a server has no transfers. */
internal object RemoteEmptyTransfers {
    fun explanation(totalInLoadedView: Int, loading: Boolean, stale: Boolean,
                    view: String, searching: Boolean, filtered: Boolean): String = when {
        loading -> "Checking this server's transfers…"
        stale -> "The last server list may be out of date. Refresh before relying on these results."
        totalInLoadedView == 0 && view != "main" ->
            "No transfers in the '$view' server view. Other server views may contain downloads."
        totalInLoadedView == 0 -> "No transfers reported in the last main-view observation."
        searching || filtered -> "No transfers match this search or filter."
        else -> "No transfers are visible in the current server view."
    }

    fun canClear(search: String, filter: TransferFilter): Boolean =
        search.isNotBlank() || filter != TransferFilter.ALL
}
