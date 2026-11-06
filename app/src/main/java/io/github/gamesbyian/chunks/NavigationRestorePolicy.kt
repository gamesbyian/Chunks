package io.github.gamesbyian.chunks

/**
 * Restore only two read-only/navigation-safe entry points after Activity or
 * process recreation. Every route capable of opening an Add draft, selecting
 * an operation, inspecting file content or depending on an authenticated
 * rTorrent session returns to the disconnected Transfers entry screen.
 *
 * A restored route never authenticates a server, starts an engine, fetches a
 * transfer, or replays a pending command. A new Android share is admitted
 * only on a fresh launch or onNewIntent; the original Intent must not be
 * replayed when the Activity is recreated after a consumed source.
 */
internal object NavigationRestorePolicy {
    fun restorableSection(name: String?): String = when (name) {
        // Media settings hold no transfer data, server session or command.
        "LOCAL", "COMBINED", "MEDIA" -> name
        else -> "TRANSFERS"
    }
}
