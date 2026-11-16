package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalAddDisplayProjectorTest {
    @Test fun noAttemptHasNoReceipt() {
        assertNull(UniversalAddDisplayProjector.from(
            UniversalAddCoordinator.Phase.EMPTY, "Local"))
        assertNull(UniversalAddDisplayProjector.from(
            UniversalAddCoordinator.Phase.REVIEWED, "Wood"))
    }

    @Test fun equivalentEngineEvidenceProducesIdenticalStagesAndCopyExceptClientName() {
        val stages = listOf(
            UniversalAddCoordinator.Phase.WRITE_ATTEMPTED to UniversalAddDisplayStage.SUBMITTING,
            UniversalAddCoordinator.Phase.READBACK_REQUIRED to UniversalAddDisplayStage.REGISTERED_CHECKING,
            UniversalAddCoordinator.Phase.VERIFIED to UniversalAddDisplayStage.VERIFIED,
            UniversalAddCoordinator.Phase.SESSION_CONFIRMED to UniversalAddDisplayStage.SESSION_CONFIRMED,
            UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED to UniversalAddDisplayStage.START_UNVERIFIED
        )
        stages.forEach { (phase, expected) ->
            val submitting = expected == UniversalAddDisplayStage.SUBMITTING
            val local = requireNotNull(UniversalAddDisplayProjector.from(
                phase, "Local", submitting, registrationFromThisSubmission = true))
            val server = requireNotNull(UniversalAddDisplayProjector.from(
                phase, "Wood", submitting, registrationFromThisSubmission = true))
            assertEquals(expected, local.stage)
            assertEquals(local.stage, server.stage)
            assertEquals(local.title.replace("Local", "Wood"), server.title)
            assertEquals(local.explanation, server.explanation)
            assertEquals(local.verifiedJobObserved, server.verifiedJobObserved)
        }
    }

    @Test fun lostAckIsNeverLabeledNewRegistration() {
        val uncertain = requireNotNull(UniversalAddDisplayProjector.from(
            UniversalAddCoordinator.Phase.READBACK_REQUIRED, "Wood"))
        assertEquals(UniversalAddDisplayStage.UNCONFIRMED, uncertain.stage)
        assertFalse(uncertain.verifiedJobObserved)
        assertTrue(uncertain.explanation.contains("never retry"))
        val nativeReceipt = requireNotNull(UniversalAddDisplayProjector.from(
            UniversalAddCoordinator.Phase.READBACK_REQUIRED, "Local",
            registrationFromThisSubmission = true))
        assertEquals(UniversalAddDisplayStage.REGISTERED_CHECKING, nativeReceipt.stage)
        assertFalse(nativeReceipt.verifiedJobObserved)
    }

    @Test fun confirmedExistingSessionDoesNotClaimThisAppCreatedTheTorrent() {
        val confirmed = requireNotNull(UniversalAddDisplayProjector.from(
            UniversalAddCoordinator.Phase.SESSION_CONFIRMED, "Wood"))
        assertEquals(UniversalAddDisplayStage.SESSION_CONFIRMED, confirmed.stage)
        assertFalse(confirmed.title.startsWith("Added"))
        assertTrue(confirmed.verifiedJobObserved)
    }

    @Test fun unverifiedStartDoesNotClaimSuccessOrAllowRetry() {
        val state = requireNotNull(UniversalAddDisplayProjector.from(
            UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, "Local"))
        assertEquals(UniversalAddDisplayStage.START_UNVERIFIED, state.stage)
        assertTrue(state.explanation.contains("Start remains unverified"))
        assertFalse(state.title.startsWith("Added"))
    }

    @Test fun noWriteRefusalIsDistinctFromLostAcknowledgement() {
        val refused = requireNotNull(UniversalAddDisplayProjector.from(
            UniversalAddCoordinator.Phase.REVIEWED, "Wood", knownNoWrite = true))
        assertEquals(UniversalAddDisplayStage.NO_NEW_ADD, refused.stage)
        assertFalse(refused.verifiedJobObserved)
        val stillAttempted = requireNotNull(UniversalAddDisplayProjector.from(
            UniversalAddCoordinator.Phase.READBACK_REQUIRED, "Wood", knownNoWrite = true))
        assertEquals(UniversalAddDisplayStage.UNCONFIRMED, stillAttempted.stage)
    }

    @Test fun submittingPrecedesPreliminaryObservedRegistration() {
        val stage = UniversalAddDisplayProjector.from(
            UniversalAddCoordinator.Phase.READBACK_REQUIRED, "Local",
            submitting = true, registrationFromThisSubmission = true)
        assertEquals(UniversalAddDisplayStage.SUBMITTING, stage?.stage)
    }

    @Test fun labelsHaveExactScopeFallback() {
        assertEquals("Adding to this client",
            UniversalAddDisplayProjector.from(
                UniversalAddCoordinator.Phase.WRITE_ATTEMPTED, "  ", submitting = true)?.title)
    }
}
