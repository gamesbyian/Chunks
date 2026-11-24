package io.github.gamesbyian.chunks

/**
 * A process-only receipt for the draft that authorized an asynchronous Android
 * Share/Open With document read. A successful content:// parse is not allowed
 * to replace a newer source chosen while the provider was reading.
 *
 * Compare reference identity for reviewed .torrent selections: equal infohash
 * alone is insufficient because distinct metainfo can carry different private
 * trackers and authorization tokens. No source text, URI or metainfo is logged
 * or retained beyond the lifetime of this one read.
 *
 * A target switch is harmless when the newly selected remote has no competing
 * reviewed file. An explicit replacement of an existing draft remains allowed
 * when that exact prior draft is still selected on completion.
 */
internal class IncomingDocumentSelectionWitness(
    private val text: String,
    private val localDocument: PendingLocalAdd?,
    private val remoteHandoff: ByteArray?,
    private val remoteSession: RemoteAddSession?
) {
    private val remoteDocument = remoteSession?.selectedBytes
    private val remoteFileMode = remoteSession?.useFile

    fun canApply(
        currentText: String,
        currentLocalDocument: PendingLocalAdd?,
        currentRemoteHandoff: ByteArray?,
        currentRemoteSession: RemoteAddSession?
    ): Boolean {
        if (currentText != text ||
            currentLocalDocument !== localDocument ||
            currentRemoteHandoff !== remoteHandoff) return false

        // The original account may no longer be selected. A late provider
        // must still not erase a file chosen in that account during the read.
        if (remoteSession != null &&
            (remoteSession.selectedBytes !== remoteDocument ||
                remoteSession.useFile != remoteFileMode)) return false

        if (currentRemoteSession === remoteSession) return true
        // A destination/account switch alone does not discard a reviewed
        // source, but the new destination's own file selection takes priority.
        return currentRemoteSession == null ||
            (!currentRemoteSession.useFile && currentRemoteSession.selectedBytes == null)
    }
}
