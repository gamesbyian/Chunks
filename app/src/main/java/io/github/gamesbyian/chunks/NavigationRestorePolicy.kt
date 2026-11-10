package io.github.gamesbyian.chunks

/**
 * Restore only navigation-safe destinations after Activity or process
 * recreation. The device Add destination may reopen as an empty shell, but
 * source bytes, private magnet links, selections, approvals and remote
 * session authority are never restored. All operational routes fall back
 * to device Transfers.
 *
 * Configuration changes may retain private draft content in ViewModel RAM,
 * but process death never restores private sources or authenticated authority.
 * A restored route never authenticates a server, starts an engine, fetches a
 * transfer, or replays a pending command. A new Android share is admitted
 * only on a fresh launch or onNewIntent; the original Intent must not be
 * replayed when the Activity is recreated after a consumed source.
 */
internal object NavigationRestorePolicy {
    fun restorableSection(name: String?): String = when (name) {
        // Media settings hold no transfer data, server session or command.
        // Restore only the Add shell: private sources and approvals never replay.
        "COMBINED", "SERVER", "MEDIA", "OPTIONS", "ADD_CHOOSE" -> name
        // Legacy owning-list routes cannot restore command authority.
        "LOCAL", "TRANSFERS" -> "COMBINED"
        // Universal Add reopens only the empty shell, never source or review.
        "LOCAL_ADD", "ADD" -> "ADD_CHOOSE"
        else -> "COMBINED"
    }
}
