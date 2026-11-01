package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferKey

/**
 * Navigation authority is the exact selected (environment, infohash), never
 * the currently displayed remote account or a matching torrent title/hash.
 * Resolving a route does not authenticate, fetch, queue or change a torrent.
 */
internal object ExactOwningListNavigation {
    enum class Route { PHONE, AUTHENTICATED_SERVER, UNAVAILABLE }

    fun route(key: UnifiedTransferKey, authenticatedServer: EnvironmentId?): Route = when {
        key.environment == EnvironmentSnapshots.PHONE -> Route.PHONE
        authenticatedServer != null && key.environment == authenticatedServer ->
            Route.AUTHENTICATED_SERVER
        else -> Route.UNAVAILABLE
    }

    /**
     * The signed-in list may currently be a filtered rTorrent view. Only a
     * complete "main" fetch can open a transfer found in combined inventory
     * without conflating a missing row with absence from the server.
     */
    fun needsMainViewRefresh(currentView: String, requestedView: String): Boolean =
        currentView != "main" || requestedView != "main"
}
