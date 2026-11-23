package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.TransferAliasStore
import io.github.gamesbyian.chunks.TransferCollectionStore
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.AliasReviewWrite

/** Only entered through the separate settings action after a fresh exact Local observation. */
internal fun applyLocalSnapshotAlias(
    aliases: TransferAliasStore,
    entry: SnapshotRestorePreviewV1.Entry,
    observed: LocalTransferStatus?
): String {
    val alias = entry.displayAlias
    val hash = entry.infoHashV1
    if (alias == null || entry.decision != SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT ||
        observed?.infoHash != hash || observed.name != entry.originalName) {
        return "The exact Local transfer could not be confirmed. No alias was applied."
    }
    val scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE
    val before = runCatching { aliases.get(scope, hash) }.getOrNull()
    if (before == alias) return "This transfer already has the saved alias."
    if (before != null) return "An existing alias was kept. No alias was replaced."
    val saved = runCatching {
        aliases.saveReviewedBatch(scope, listOf(AliasReviewWrite(hash, entry.originalName, alias)))
    }.getOrDefault(false)
    val readback = runCatching { aliases.get(scope, hash) }.getOrNull()
    return when {
        saved && readback == alias -> "Saved alias verified on this device."
        readback != null && readback != alias -> "An existing alias was kept. No alias was replaced."
        else -> "Alias application could not be verified. Check transfer Details."
    }
}
