package io.github.gamesbyian.chunks.storage

import io.github.gamesbyian.chunks.storage.OwnedPublicationRecovery as Recovery
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnedPublicationRecoveryTest {
    private val expected = Recovery.Digest(4, "a".repeat(64))
    private val owned = Recovery.ObjectIdentity("content://provider/tree/real-id", "opaque-child-123")
    private val other = Recovery.ObjectIdentity("content://provider/tree/real-id", "opaque-child-456")
    private val created = Recovery.Created("operation", owned, true, true)
    private val journal = Recovery.JournalSnapshot("operation", created, expected, expected, "Library")
    private val observed = Recovery.PublishedObservation(owned, expected, true, true)

    @Test fun positiveIndependentObjectIdentityAndReadbackCanConfirmPublication() {
        assertEquals(Recovery.Verdict.VERIFIED_PUBLISHED, Recovery.evaluate(journal, observed))
        // The same physical observation is required even if the post-commit log line is absent.
        assertEquals(Recovery.Verdict.VERIFIED_PUBLISHED,
            Recovery.evaluate(journal.copy(publishedRecord = true), observed))
    }

    @Test fun namesAndPublishIntentsNeverProveOwnership() {
        assertEquals(Recovery.Verdict.NEEDS_ATTENTION, Recovery.evaluate(journal, null))
        assertEquals(Recovery.Verdict.NEEDS_ATTENTION,
            Recovery.evaluate(journal.copy(created = null, publishedRecord = true), observed))
        assertEquals(Recovery.Verdict.NEEDS_ATTENTION,
            Recovery.evaluate(journal.copy(publishIntentName = null, publishedRecord = true), observed))
    }

    @Test fun providerExistingUriAndNonexclusiveCreationAreRefused() {
        assertEquals(Recovery.Verdict.NEEDS_ATTENTION,
            Recovery.evaluate(journal.copy(created = created.copy(creationExclusive = false)), observed))
        assertEquals(Recovery.Verdict.NEEDS_ATTENTION,
            Recovery.evaluate(journal.copy(created = created.copy(outputHandleBound = false)), observed))
        assertEquals(Recovery.Verdict.NEEDS_ATTENTION,
            Recovery.evaluate(journal, observed.copy(nonReplacingPublish = false)))
    }

    @Test fun hostileCollisionsAndUriSubstitutionDoNotConfirmPublication() {
        assertEquals(Recovery.Verdict.NEEDS_ATTENTION,
            Recovery.evaluate(journal, observed.copy(identity = other)))
        assertEquals(Recovery.Verdict.NEEDS_ATTENTION,
            Recovery.evaluate(journal, observed.copy(identity = owned.copy(scope = "other-account"))))
        assertEquals(Recovery.Verdict.NEEDS_ATTENTION,
            Recovery.evaluate(journal, observed.copy(identityVerifiedByBoundHandle = false)))
        assertEquals(Recovery.Verdict.NEEDS_ATTENTION,
            Recovery.evaluate(journal.copy(operationId = "another-op"), observed))
    }

    @Test fun sourceIndependentDigestIsRequiredAtEveryStage() {
        assertEquals(Recovery.Verdict.NEEDS_ATTENTION,
            Recovery.evaluate(journal.copy(stagedReadback = expected.copy(size = 3)), observed))
        assertEquals(Recovery.Verdict.NEEDS_ATTENTION,
            Recovery.evaluate(journal, observed.copy(digest = expected.copy(sha256 = "b".repeat(64)))))
        assertEquals(Recovery.Verdict.NEEDS_ATTENTION,
            Recovery.evaluate(journal.copy(expected = null), observed))
    }
}
