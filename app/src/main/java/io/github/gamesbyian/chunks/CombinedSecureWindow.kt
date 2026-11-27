package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.privacy.TransferPrivacyVisibility

/**
 * Whether combined Transfers must hide its window from screenshots and Recents (FLAG_SECURE).
 * Required whenever any protected scope is currently shown: Local, the authenticated rTorrent
 * server, a saved rTorrent profile whose listing has been observed, or a typed
 * Transmission/qBittorrent/Deluge client. A locked scope shows nothing and needs no flag;
 * an unlocked scope whose lock mode is not NEVER does. Over-securing is safe; under-securing
 * leaks protected titles into screenshots and the app switcher.
 */
internal object CombinedSecureWindow {
    fun required(
        local: TransferPrivacyVisibility.Decision?,
        server: TransferPrivacyVisibility.Decision?,
        savedShown: Collection<TransferPrivacyVisibility.Decision>,
        protocol: Collection<TransferPrivacyVisibility.Decision>
    ): Boolean = sequenceOf(local, server).filterNotNull().plus(savedShown).plus(protocol)
        .any { it.needsSecureWindow && it.mayShow }
}
