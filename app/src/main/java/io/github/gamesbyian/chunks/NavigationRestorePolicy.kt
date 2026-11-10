package io.github.gamesbyian.chunks

/**
 * Restore only non-mutating root destinations. Prior local and remote list
 * routes migrate to the one canonical Combined-derived Transfers catalog.
 * Neither a selected profile scope nor an authenticated session nor any
 * source/Place/Start/command approval is restored by this policy.
 *
 * The Universal Add shell is safe to reopen with an EMPTY source and no
 * execution ticket. A fresh Android Share is admitted only on a new Intent.
 */
internal object NavigationRestorePolicy {
    fun restorableSection(name: String?): String = when (name) {
        "SERVER", "COMBINED", "MEDIA", "OPTIONS", "ADD_CHOOSE" -> name
        "LOCAL_ADD" -> "ADD_CHOOSE"
        "LOCAL", "TRANSFERS" -> "COMBINED"
        else -> "COMBINED"
    }
}
