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
    fun restorableSection(name: String?): String = when (name) {
        "SERVER", "COMBINED", "MEDIA", "OPTIONS", "ADD_CHOOSE" -> name
        "LOCAL_ADD" -> "ADD_CHOOSE"
        "LOCAL", "TRANSFERS" -> "COMBINED"
        else -> "COMBINED"
    }
}
