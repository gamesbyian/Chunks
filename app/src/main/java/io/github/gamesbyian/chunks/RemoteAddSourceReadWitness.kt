package io.github.gamesbyian.chunks

/**
 * A local, non-authorizing witness for an in-flight remote Add source read.
 *
 * Used only before accepting a fetched HTTPS .torrent or Android picker
 * result. A newer typed URL/magnet, selected metainfo (even with the same
 * infohash), or file/text mode change revokes the old asynchronous result.
 * The caller still uses its existing exact-account Add coordinator and
 * explicit Add transfer click; this witness never mutates an engine.
 */
internal class RemoteAddSourceReadWitness(
    private val text: String,
    private val reviewedBytes: ByteArray?,
    private val fileMode: Boolean
) {
    fun stillSelected(
        currentText: String,
        currentReviewedBytes: ByteArray?,
        currentFileMode: Boolean
    ): Boolean =
        currentText == text &&
            currentReviewedBytes === reviewedBytes &&
            currentFileMode == fileMode
}
