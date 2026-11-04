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

/** Clearing changes only the view (search text and filter), never sort, jobs or destinations. */
@Composable
internal fun PhoneAddedHiddenNotice(onShowAll: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Your new transfer was added but is hidden by the current search or filter.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        OutlinedButton(onClick = onShowAll,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Show all phone transfers")
        }
    }
}
