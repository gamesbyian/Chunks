package io.github.gamesbyian.chunks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

/**
 * Remote-card subtree state belongs to the exact authenticated server account
 * AND torrent infohash. A LazyColumn row key using only the hash is not enough:
 * the same hash/name/directory may exist on another signed-in server.
 *
 * Changing account discards confirmations, remembered operation locks, file
 * selection and other child composition state. No command is issued here.
 */
@Composable
internal fun RemoteTransferCompositionBoundary(
    authenticatedAliasScope: String,
    infoHash: String,
    content: @Composable () -> Unit
) {
    key(authenticatedAliasScope, infoHash) { content() }
}
