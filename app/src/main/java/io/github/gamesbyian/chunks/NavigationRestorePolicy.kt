package io.github.gamesbyian.chunks

/**
 * Restore only two read-only/navigation-safe entry points after Activity or
 * process recreation. Every route capable of opening an Add draft, selecting
 * an operation, inspecting file content or depending on an authenticated
 * rTorrent session returns to the disconnected Transfers entry screen.
 *
 * A restored route never authenticates a server, starts an engine, fetches a
 * transfer, or replays a pending command. External Android shares are handled
 * separately by the Activity's currently delivered intent.
 */
internal object NavigationRestorePolicy {
    fun restorableSection(name: String?): String = when (name) {
        "LOCAL", "COMBINED" -> name
        else -> "TRANSFERS"
    }
}
