package io.github.gamesbyian.chunks.privacy

import org.junit.Assert.*
import org.junit.Test

class TransferPrivacySessionTest {
    private var elapsed = 1_000_000L
    private fun session() = TransferPrivacySession { elapsed }
    private val local = "local"
    private val alice = "https://remote.example/RPC2\u0000alice"
    private val bob = "https://remote.example/RPC2\u0000bob"

    @Test fun enabledStartsLockedAndUnlocksExactlyOneScope() {
        val p = session()
        assertTrue(p.configure(local, TransferLockMode.ONCE_PER_PROCESS, false))
        assertTrue(p.configure(alice, TransferLockMode.ONCE_PER_PROCESS, false))
        assertTrue(p.configure(bob, TransferLockMode.ONCE_PER_PROCESS, false))
        assertFalse(p.mayView(local))
        val t = requireNotNull(p.beginUnlock(alice))
        assertFalse(p.mayAct(alice))
        assertTrue(p.finishUnlock(t, true))
        assertTrue(p.mayView(alice))
        assertFalse(p.mayView(local))
        assertFalse(p.mayView(bob))
        assertNull(p.beginUnlock(alice))
        assertFalse(p.finishUnlock(t, true))
    }

    @Test fun freshProcessNeverPersistsAccessEvenWhenSameSettingsRestored() {
        val first = session()
        first.configure(local, TransferLockMode.ONCE_PER_PROCESS, false)
        assertTrue(first.finishUnlock(requireNotNull(first.beginUnlock(local)), true))
        val next = session()
        next.configure(local, TransferLockMode.ONCE_PER_PROCESS, false)
        assertFalse(next.mayAct(local))
    }

    @Test fun cancellationAndStaleCallbacksCannotUnlockAfterLockNowOrPolicyChange() {
        val p = session()
        p.configure(local, TransferLockMode.ONCE_PER_PROCESS, false)
        val ticket = requireNotNull(p.beginUnlock(local))
        assertNull(p.beginUnlock(local)) // serialize prompt
        p.onCancelUnlock(ticket)
        assertFalse(p.finishUnlock(ticket, true))
        val ticket2 = requireNotNull(p.beginUnlock(local))
        p.lockNow(local)
        assertFalse(p.finishUnlock(ticket2, true))
        assertFalse(p.mayView(local))
        assertTrue(p.finishUnlock(requireNotNull(p.beginUnlock(local)), true))
        assertFalse(p.configure(local, TransferLockMode.NEVER, false))
        assertTrue(p.configure(local, TransferLockMode.NEVER, true))
        assertTrue(p.mayView(local))
        assertTrue(p.configure(local, TransferLockMode.EVERY_ENTRY, false))
        assertFalse(p.mayView(local))
    }

    @Test fun oncePerProcessSurvivesBackgroundButManualLockRevokes() {
        val p = session()
        p.configure(local, TransferLockMode.ONCE_PER_PROCESS, false)
        assertTrue(p.finishUnlock(requireNotNull(p.beginUnlock(local)), true))
        p.onBackground()
        elapsed += 600_000L
        p.onForeground()
        assertTrue(p.mayView(local))
        p.lockNow(local)
        assertFalse(p.mayView(local))
    }

    @Test fun everyForegroundReturnAndEveryEntryRevokeOnDistinctEvents() {
        val p = session()
        p.configure(local, TransferLockMode.EACH_FOREGROUND_RETURN, false)
        p.configure(alice, TransferLockMode.EVERY_ENTRY, false)
        assertTrue(p.finishUnlock(requireNotNull(p.beginUnlock(local)), true))
        assertTrue(p.finishUnlock(requireNotNull(p.beginUnlock(alice)), true))
        p.onExit(alice)
        assertFalse(p.mayView(alice))
        assertTrue(p.mayView(local))
        p.onBackground()
        assertFalse(p.mayView(local))
    }

    @Test fun fiveMinuteBackgroundThresholdAndClockRollbackFailClosed() {
        val p = session()
        p.configure(local, TransferLockMode.AFTER_FIVE_MINUTES_BACKGROUND, false)
        assertTrue(p.finishUnlock(requireNotNull(p.beginUnlock(local)), true))
        p.onBackground()
        elapsed += 299_999L
        p.onForeground()
        assertTrue(p.mayView(local))
        p.onBackground()
        elapsed += 300_000L
        p.onForeground()
        assertFalse(p.mayView(local))
        assertTrue(p.finishUnlock(requireNotNull(p.beginUnlock(local)), true))
        p.onBackground()
        elapsed -= 1_000L
        p.onForeground()
        assertFalse(p.mayView(local))
    }

    @Test fun disabledByDefaultAndFailedAuthNeverUnlock() {
        val p = session()
        assertTrue(p.mayView("unconfigured"))
        assertNull(p.beginUnlock("unconfigured"))
        p.configure(local, TransferLockMode.NEVER, false)
        assertTrue(p.mayAct(local))
        p.configure(alice, TransferLockMode.ONCE_PER_PROCESS, false)
        val ticket = requireNotNull(p.beginUnlock(alice))
        assertFalse(p.finishUnlock(ticket, false))
        assertFalse(p.mayView(alice))
        assertFalse(p.configure(alice, TransferLockMode.NEVER, false))
    }
}
