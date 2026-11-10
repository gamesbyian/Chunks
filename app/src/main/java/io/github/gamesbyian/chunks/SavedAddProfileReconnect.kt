package io.github.gamesbyian.chunks

/**
 * One non-persistent navigation promise, never remote Add or login authority.
 *
 * Universal Add may ask the user to authenticate another saved rTorrent
 * profile. Only a subsequently verified *exact* endpoint/route/principal may
 * resume that Add draft. A failed Connect retains the promise for an explicit
 * retry; any successful Connect consumes it, including an unexpected account.
 *
 * No magnet, token, password, metainfo or mutable job state is stored here.
 */
internal class SavedAddProfileReconnect {
    private var requestedAccountScope: String? = null

    fun requestAuthentication(exactAccountScope: String) {
        require(exactAccountScope.isNotBlank()) { "Saved Add target must have an exact account scope" }
        requestedAccountScope = exactAccountScope
    }

    /** Call only after the existing authenticated Connect's identity checks. */
    fun consumeAuthenticated(exactAuthenticatedScope: String): Boolean {
        val requested = requestedAccountScope
        requestedAccountScope = null
        return requested != null && exactAuthenticatedScope.isNotBlank() &&
            requested == exactAuthenticatedScope
    }

    /** Cancelled Add/navigation must not redirect a later unrelated login. */
    fun abandon() {
        requestedAccountScope = null
    }
}
