package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.privacy.TransferLockMode
import io.github.gamesbyian.chunks.privacy.TransferPrivacySession
import org.junit.Assert.*
import org.junit.Test

class CombinedPrivacyEvidenceTest {
    private val phone = "on-this-phone"
    private val alice = "chunks-rtorrent-account-v1:" + "a".repeat(64)

    private fun evidence(
        modes: Map<String, TransferLockMode>,
        policyRevision: Int = 4,
        lifecycleRevision: Int = 9
    ) = CombinedPrivacyEvidence(modes.keys, policyRevision, lifecycleRevision, modes)

    @Test fun privacyEvidenceMustMatchExactRosterAndBothRevisions() {
        val current = evidence(mapOf(phone to TransferLockMode.NEVER, alice to TransferLockMode.ONCE_PER_PROCESS))
        assertTrue(current.current(setOf(phone, alice), 4, 9))
        assertFalse(current.current(setOf(phone), 4, 9))
        assertFalse(current.current(setOf(phone, alice), 5, 9))
        assertFalse(current.current(setOf(phone, alice), 4, 10))
        assertFalse(CombinedPrivacyEvidence(setOf(phone, alice), 4, 9, mapOf(phone to TransferLockMode.NEVER))
            .current(setOf(phone, alice), 4, 9))
    }

    @Test fun pendingOrMissingPolicyCannotRevealOptedOutOrOtherAccount() {
        val session = TransferPrivacySession { 0L }
        val current = evidence(mapOf(phone to TransferLockMode.NEVER))
        assertEquals(CombinedPrivacyEvidence.LOCKED, current.decision(alice, session, true))
        assertEquals(CombinedPrivacyEvidence.LOCKED, current.decision(null, session, true))
        assertTrue(current.decision(phone, session, false).mayShow)
    }

    @Test fun enabledPolicyNeedsExactSessionUnlockAndSecureDevice() {
        val session = TransferPrivacySession { 0L }
        val current = evidence(mapOf(alice to TransferLockMode.ONCE_PER_PROCESS))
        assertFalse(current.decision(alice, session, true).mayShow)
        assertTrue(current.decision(alice, session, true).needsSecureWindow)
        val ticket = requireNotNull(session.beginUnlock(alice))
        assertTrue(session.finishUnlock(ticket, true))
        assertFalse(current.decision(alice, session, false).mayShow)
        assertTrue(current.decision(alice, session, true).mayShow)
        session.lockNow(alice)
        assertFalse(current.decision(alice, session, true).mayShow)
    }

    @Test fun inconsistentStoredPolicyNeverUsesPreviouslyAuthorizedSession() {
        val session = TransferPrivacySession { 0L }
        val protected = evidence(mapOf(phone to TransferLockMode.ONCE_PER_PROCESS))
        assertFalse(protected.decision(phone, session, true).mayShow)
        assertTrue(session.finishUnlock(requireNotNull(session.beginUnlock(phone)), true))
        assertTrue(protected.decision(phone, session, true).mayShow)
        // A separate screen's persisted disable would require fresh
        // authorization; the combined view may not silently reconfigure it.
        val stale = evidence(mapOf(phone to TransferLockMode.NEVER))
        assertEquals(CombinedPrivacyEvidence.LOCKED, stale.decision(phone, session, true))
    }

    @Test fun damagedPolicyOrUnavailableReadIsAlwaysLocked() {
        val session = TransferPrivacySession { 0L }
        val evidence = CombinedPrivacyEvidence(setOf(phone), 3, 1, emptyMap())
        assertEquals(CombinedPrivacyEvidence.LOCKED, evidence.decision(phone, session, true))
        assertFalse(evidence.current(setOf(phone), 3, 1))
    }

    @Test fun expiredForegroundGrantIsReflectedWithoutReloadingPrivateData() {
        val session = TransferPrivacySession { 0L }
        val current = evidence(mapOf(alice to TransferLockMode.EACH_FOREGROUND_RETURN))
        assertFalse(current.decision(alice, session, true).mayShow)
        assertTrue(session.finishUnlock(requireNotNull(session.beginUnlock(alice)), true))
        assertTrue(current.decision(alice, session, true).mayShow)
        session.onBackground()
        session.onForeground()
        assertFalse(current.decision(alice, session, true).mayShow)
    }
}
