package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Configuration recreation may cancel a reader without consuming its source. */
class ExternalDocumentReadCancellationTest {
    @Test fun onlyTheLiveCompletedReaderCanConsumeItsCurrentDocument() {
        assertTrue(shouldConsumeReviewedSourceOnExit(
            isCurrentDocument = true, lifecycleCancelled = false))
        assertFalse(shouldConsumeReviewedSourceOnExit(
            isCurrentDocument = true, lifecycleCancelled = true))
        assertFalse(shouldConsumeReviewedSourceOnExit(
            isCurrentDocument = false, lifecycleCancelled = false))
        assertFalse(shouldConsumeReviewedSourceOnExit(
            isCurrentDocument = false, lifecycleCancelled = true))
    }
}
