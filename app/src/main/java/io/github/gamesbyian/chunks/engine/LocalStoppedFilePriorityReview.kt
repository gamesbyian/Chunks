package io.github.gamesbyian.chunks.engine

/** Complete file choices and the native registration observed in one stopped Local job. */
internal data class LocalStoppedFilePriorityReview(
    val infoHash: String,
    val name: String,
    val place: String,
    val registration: String,
    val files: List<LocalTransferFile>
) {
    companion object {
        const val MAX_FILES = 1_024
    }
}
