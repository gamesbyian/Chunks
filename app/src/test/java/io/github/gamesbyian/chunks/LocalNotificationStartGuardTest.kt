package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import org.junit.Assert.*
import org.junit.Test

class LocalNotificationStartGuardTest {
    private val target = LocalTransferStatus("a".repeat(40), "fixture", LocalTransferState.DOWNLOADING,
        true, 0, 1024, 0, 0, 0, 0, "/phone/chosen", null, nativeReviewToken = "native-generation-one")
    @Test fun unchangedStoppedTargetCanContinueAfterEitherNotificationChoice() {
        assertTrue(LocalNotificationStartGuard.matches(target, target, 4, 4))
    }
    @Test fun recycledHashCannotInheritAnEarlierStartRequest() {
        assertFalse(LocalNotificationStartGuard.matches(target, target.copy(nativeReviewToken = "native-generation-two"), 4, 4))
    }
    @Test fun changedPathMissingTargetOrAlreadyRunningTargetCannotContinue() {
        for (observed in listOf(null, target.copy(savePath = "/another-place"), target.copy(stopped = false)))
            assertFalse(LocalNotificationStartGuard.matches(target, observed, 4, 4))
    }
    @Test fun changedInventoryGenerationRequiresFreshReview() {
        assertFalse(LocalNotificationStartGuard.matches(target, target, 4, 6))
    }
}
