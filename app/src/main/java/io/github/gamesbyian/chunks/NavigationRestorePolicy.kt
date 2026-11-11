package io.github.gamesbyian.chunks

/**
 * Restore only view/navigation state. Old local and server-only transfer routes
 * migrate to the one Combined-derived Transfers browser, never to a mutation
 * executor. The exact selected profile, login session, source/Place/Start,
 * private magnet and approval never enter SavedState.
 *
 * A restored universal Add destination is an EMPTY source shell; Android
 * Share/Open With is admitted only on a fresh Intent, never replayed here.
 */
internal object NavigationRestorePolicy {
    /**
     * Restored canonical Transfers may exist before a remembered session is
     * live. Delegate once to the *existing* authenticated read-only Connect
     * owner instead of leaving the browser permanently disconnected.
     * A deliberate Disconnect, canceled attempt, or Remember opt-out must
     * not trigger a new automatic attempt.
     */
    fun requiresColdReconnectHandoff(
        section: String,
        savedCredentialPresent: Boolean,
        rememberingEnabled: Boolean,
        alreadyAttempted: Boolean,
        authenticated: Boolean
    ): Boolean = section == "COMBINED" && savedCredentialPresent &&
        rememberingEnabled && !alreadyAttempted && !authenticated

    fun restorableSection(name: String?): String = when (name) {
        "SERVER", "COMBINED", "MEDIA", "OPTIONS", "ADD_CHOOSE" -> name
        "LOCAL_ADD" -> "ADD_CHOOSE"
        "LOCAL", "TRANSFERS" -> "COMBINED"
        else -> "COMBINED"
    }
}
