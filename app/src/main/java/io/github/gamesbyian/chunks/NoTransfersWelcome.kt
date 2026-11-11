package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A genuine empty inventory, with no connected or saved remote environment.
 * Filtering, privacy locking, stale remote inventory and saved disconnected
 * connections must never be mistaken for first launch.
 *
 * These are navigation links only. No engine, RPC, provider or mutation runs
 * until the user proceeds through the existing authorized Add/Connect flow.
 */
@Composable
internal fun NoTransfersWelcome(onAdd: () -> Unit, onConnections: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Welcome to Chunks", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(ChunksSemantics.WELCOME_HEADING).semantics { heading() })
        Text("Start a download on this device, or connect a remote BitTorrent client. " +
            "You can use Chunks with either, or both.",
            style = MaterialTheme.typography.bodyMedium)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)
                .testTag(ChunksSemantics.WELCOME_ADD)) {
                Text("Add a torrent")
            }
            if (onConnections != null) TextButton(onClick = onConnections,
                modifier = Modifier.heightIn(min = 48.dp)
                .testTag(ChunksSemantics.WELCOME_CONNECTIONS)) {
                Text("Connections")
            }
        }
    }
}

/**
 * A verified login retained in the legacy vault is a configured remote client
 * even before the asynchronously loaded roster contains its profile. Passing
 * only its presence prevents an incorrect first-install declaration without
 * granting the browser access to credentials or an RPC session.
 */
internal fun configuredRemoteForWelcome(
    connected: Boolean, hasRosterProfile: Boolean, hasRememberedVaultLogin: Boolean
): Boolean = connected || hasRosterProfile || hasRememberedVaultLogin

/** An initial/stale/filtered empty list is not evidence of an unused setup. */
internal fun showPhoneFirstRunWelcome(
    inventoryConfirmed: Boolean,
    inventoryStale: Boolean,
    inventoryEmpty: Boolean,
    hasRemoteEnvironment: Boolean,
    search: String,
    addOpen: Boolean,
    filterIsDefault: Boolean = true,
    restoreHealthy: Boolean = true,
    noIncomingSource: Boolean = true
): Boolean = inventoryConfirmed && !inventoryStale && inventoryEmpty &&
    !hasRemoteEnvironment && search.isBlank() && !addOpen && filterIsDefault &&
    restoreHealthy && noIncomingSource

/**
 * Fresh-install welcome requires positive *absence* evidence. A process with
 * no running native engine cannot call its unobserved empty list "confirmed":
 * durable resume discovery must first prove that there are no jobs to restore.
 * Conversely, a currently observed empty engine is sufficient when fresh.
 */
internal fun showCanonicalFirstRunWelcome(
    noPersistedJobs: Boolean?,
    nativeRunning: Boolean,
    nativeConfirmed: Boolean,
    nativeStale: Boolean,
    nativeCount: Int,
    hasRemoteProfile: Boolean,
    restrictedView: Boolean,
    privacyAllowed: Boolean,
    pendingExternalSource: Boolean
): Boolean {
    // Even after the engine exits, a previously observed nonempty frame must
    // never be covered by first-install chrome during a restore transition.
    // A current empty engine poll cannot erase known durable resume work.
    // Unknown disk state may be bypassed only by a confirmed live inventory.
    val genuinelyEmpty = nativeCount == 0 && !nativeStale &&
        noPersistedJobs != false &&
        (if (nativeRunning) nativeConfirmed else noPersistedJobs == true)
    return genuinelyEmpty && !hasRemoteProfile && !restrictedView &&
        privacyAllowed && !pendingExternalSource
}
