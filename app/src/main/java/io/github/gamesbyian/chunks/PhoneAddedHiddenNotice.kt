package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A newly added phone transfer that the current search or filter hides.
 * Only an exact infohash present in the observed inventory counts; a pending,
 * refused or unobserved Add never produces this notice.
 */
internal object PhoneAddedVisibility {
    fun hiddenByView(addedHash: String?, inventory: List<String>, shown: List<String>): Boolean {
        val hash = addedHash ?: return false
        return inventory.any { it.equals(hash, ignoreCase = true) } &&
            shown.none { it.equals(hash, ignoreCase = true) }
    }
}

/**
 * Clearing changes only the view (search text and filter), never sort, jobs or destinations.
 * The same notice serves the phone and each rTorrent account; only the
 * environment wording differs, so the interaction stays identical.
 */
@Composable
internal fun PhoneAddedHiddenNotice(
    onShowAll: () -> Unit,
    showAllLabel: String = "Show all phone transfers"
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Your new transfer was added but is hidden by the current search or filter.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        OutlinedButton(onClick = onShowAll,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(showAllLabel)
        }
    }
}

/**
 * A verified rTorrent Add remembered with its exact account and the list view
 * (search + filter) in force when it was added. Changing the view, or another
 * account, retires the notice just as on the phone.
 */
internal data class RemoteAddedRecord(val aliasScope: String, val hash: String, val viewKey: String) {
    fun hiddenIn(
        scope: String, currentViewKey: String, inventory: List<String>, shown: List<String>
    ): Boolean = scope == aliasScope && currentViewKey == viewKey &&
        PhoneAddedVisibility.hiddenByView(hash, inventory, shown)

    /**
     * The exact Combined key for this verified Add, only while the same
     * authenticated account is current. Another numbered instance or account
     * never inherits it; Combined still requires a fresh observed inventory.
     */
    fun combinedKey(currentAliasScope: String): io.github.gamesbyian.chunks.model.UnifiedTransferKey? {
        if (currentAliasScope != aliasScope) return null
        val environment = io.github.gamesbyian.chunks.model.EnvironmentSnapshots
            .serverIdOfAliasScope(aliasScope) ?: return null
        return try {
            io.github.gamesbyian.chunks.model.UnifiedTransferKey.of(environment, hash)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        fun viewKey(query: String, filterName: String) = query + "\u0000" + filterName
    }
}
