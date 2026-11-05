package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTransferStatus

/** A permission result cannot authorize a recycled job or a changed phone inventory. */
internal object LocalNotificationStartGuard {
    fun matches(expected: LocalTransferStatus, observed: LocalTransferStatus?,
                expectedGeneration: Long, generation: Long): Boolean =
        expectedGeneration == generation && observed != null && observed.stopped &&
            expected.infoHash == observed.infoHash && expected.savePath == observed.savePath &&
            expected.nativeReviewToken.isNotBlank() &&
            expected.nativeReviewToken == observed.nativeReviewToken
}
