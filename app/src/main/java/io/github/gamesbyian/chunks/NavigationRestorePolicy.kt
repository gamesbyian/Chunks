package io.github.gamesbyian.chunks

/**
 * Restore only navigation-safe destinations after Activity or process
 * recreation. The device Add destination may reopen as an empty shell, but
 * source bytes, private magnet links, selections, approvals and remote
 * session authority are never restored. All operational routes fall back
 * to device Transfers.
 *
 * A restored route never authenticates a server, starts an engine, fetches a
 * transfer, or replays a pending command. A new Android share is admitted
 * only on a fresh launch or onNewIntent; the original Intent must not be
 * replayed when the Activity is recreated after a consumed source.
 */
internal object NavigationRestorePolicy {
    fun restorableSection(name: String?): String = when (name) {
        // Media settings hold no transfer data, server session or command.
        // Restore only the Add shell: private sources and approvals never replay.
        "LOCAL", "LOCAL_ADD", "COMBINED", "MEDIA", "OPTIONS" -> name
        // A remote-only route without an authenticated server would otherwise
        // reopen the connection form instead of the requested transfer list.
        else -> "LOCAL"
    }
}
