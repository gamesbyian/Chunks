package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.TransferAliasStore
import io.github.gamesbyian.chunks.model.AliasReviewWrite
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.rpc.RemoteTransfer

/** Exact-account, device-local alias operation after independent observed identity.
 * No rTorrent command, file rename or transfer-state mutation occurs. */
internal fun applyRemoteSnapshotAlias(
    aliases: TransferAliasStore, accountScope: String,
    entry: SnapshotRestorePreviewV1.Entry, observed: RemoteTransfer?
): String {
    val alias = entry.displayAlias
    val hash = entry.infoHashV1
    if (EnvironmentSnapshots.serverIdOfAliasScope(accountScope) == null ||
        alias == null || alias == entry.originalName ||
        entry.decision != SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT ||
        observed == null || !observed.hash.equals(hash, ignoreCase = true) ||
        observed.name != entry.originalName)
        return "The exact account and original transfer could not be confirmed. No alias was applied."
    val before = runCatching { aliases.get(accountScope, hash) }.getOrNull()
    if (before == alias) return "This account already uses the saved alias."
    if (before != null) return "This account's existing alias was kept. No alias was replaced."
    val saved = runCatching { aliases.saveReviewedBatch(accountScope,
        listOf(AliasReviewWrite(hash, entry.originalName, alias))) }.getOrDefault(false)
    val after = runCatching { aliases.get(accountScope, hash) }.getOrNull()
    return when {
        saved && after == alias -> "Saved remote display alias verified on this device."
        after != null && after != alias ->
            "This account's existing alias was kept. No alias was replaced."
        else -> "Alias save could not be verified. Inspect this transfer in Details."
    }
}
