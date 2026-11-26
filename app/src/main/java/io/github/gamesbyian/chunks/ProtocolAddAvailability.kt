package io.github.gamesbyian.chunks

/**
 * When the typed-client (Transmission, qBittorrent, Deluge) Add control may act. Pure, so every
 * condition has a JVM witness (A2611-08); the composable only supplies the facts.
 */
internal object ProtocolAddAvailability {
    fun canSubmit(
        hasSession: Boolean,
        hasSource: Boolean,
        supported: Boolean,
        folderValid: Boolean,
        attemptBusy: Boolean,
        mine: Boolean,
        aliasValid: Boolean,
        unresolved: Boolean,
        externalSourceReading: Boolean,
        readingTorrent: Boolean,
        linkFetching: Boolean,
        originalDeletionPending: Boolean,
        noFileChosen: Boolean
    ): Boolean = hasSession && hasSource && supported && folderValid && !attemptBusy && mine &&
        aliasValid && !unresolved && !externalSourceReading && !readingTorrent && !linkFetching &&
        !originalDeletionPending && !noFileChosen
}
