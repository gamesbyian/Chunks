package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRemoveReadbackFeedbackTest {
    @Test fun acknowledgedAndAbsentIsTheOnlyQuietSuccess() {
        assertNull(localRemoveReadbackFeedback(true, false))
        assertTrue(localRemoveReadbackFeedback(true, true)!!.contains("still listed"))
        assertTrue(localRemoveReadbackFeedback(true, null)!!.contains("could not be verified"))
    }

    @Test fun failedPersistenceAfterNativeRemovalCannotBeCalledACompleteSuccess() {
        val response = localRemoveReadbackFeedback(false, false)!!
        assertTrue(response.contains("absent"))
        assertTrue(response.contains("persistence was not confirmed"))
        assertTrue(response.contains("do not repeat Remove blindly"))
    }

    @Test fun presentTransferPermitsOnlyFreshHumanReviewedRetry() {
        val response = localRemoveReadbackFeedback(false, true)!!
        assertTrue(response.contains("still present"))
        assertTrue(response.contains("review a new Remove attempt"))
        assertTrue(response.contains("Downloaded files were kept"))
    }

    @Test fun exceptionAfterNativeRemovalGetsSpecificReadOnlyReconciliation() {
        val absent = localRemoveReadbackFeedback(null, false)!!
        assertTrue(absent.contains("absent"))
        assertTrue(absent.contains("uncertain Remove"))
        assertTrue(absent.contains("Never repeat Remove blindly"))
        assertTrue(absent.contains("not requested for deletion"))
    }

    @Test fun exceptionBeforeNativeRemovalCannotBeMisreportedAsGone() {
        val present = localRemoveReadbackFeedback(null, true)!!
        assertTrue(present.contains("still present"))
        assertTrue(present.contains("no automatic retry"))
        assertTrue(present.contains("not requested for deletion"))
    }

    @Test fun unavailableEngineIsNeverReportedAsRemoved() {
        val unknown = localRemoveReadbackFeedback(null, null)!!
        assertEquals(unknown, localRemoveReadbackFeedback(false, null))
        assertTrue(unknown.contains("could not be verified"))
    }
}
