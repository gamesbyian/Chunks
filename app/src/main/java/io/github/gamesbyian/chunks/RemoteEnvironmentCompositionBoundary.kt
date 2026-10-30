package io.github.gamesbyian.chunks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

/**
 * Owns all Compose-owned selection, review, scrolling and dialog state for one
 * authenticated remote session. A different server/account OR a reconnect
 * cannot inherit a remembered batch target from the previous connection.
 *
 * The explicitly persisted filter/sort model is supplied by the caller and is
 * independent of this per-session state boundary.
 */
@Composable
internal fun RemoteEnvironmentCompositionBoundary(
    authenticatedAliasScope: String,
    authenticatedSession: Any,
    content: @Composable () -> Unit
) {
    key(authenticatedAliasScope, authenticatedSession) { content() }
}
