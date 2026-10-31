package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.LocalServiceTimeoutNotice.PauseSummary
import io.github.gamesbyian.chunks.privacy.TransferLockMode
import org.junit.Assert.*
import org.junit.Test

class LocalServiceTimeoutNoticeTest {
    private fun text(listed: Int?, confirmed: Int, policy: TransferLockMode = TransferLockMode.NEVER) =
        LocalServiceTimeoutNotice.text(PauseSummary(listed, confirmed), policy)

    @Test fun claimsOnlyReadbackConfirmedPauses() {
        assertEquals("Android's background time limit was reached. Chunks paused 2 transfers on this phone. " +
            "Open Chunks to resume.", text(2, 2))
        assertEquals("Android's background time limit was reached. Chunks paused 1 transfer on this phone. " +
            "Open Chunks to resume.", text(1, 1))
        assertEquals("Android's background time limit was reached. Chunks paused 1 of 3 transfers; " +
            "2 could not be confirmed. Open Chunks to check.", text(3, 1))
        assertEquals("Android's background time limit was reached. " +
            "Chunks tried to pause transfers on this phone but could not confirm it. " +
            "Open Chunks to check.", text(null, 0))
        assertTrue(text(0, 0).contains("No transfers were running"))
    }

    @Test fun privacyLockedScopeRevealsNoCounts() {
        for (mode in TransferLockMode.entries - TransferLockMode.NEVER) {
            val locked = text(4, 4, mode)
            assertFalse(mode.name, locked.any { it.isDigit() })
            assertEquals("Android's background time limit was reached. Open Chunks to check transfers on this phone.",
                locked)
        }
    }

    @Test fun rejectsMoreConfirmationsThanTransfers() {
        try { PauseSummary(1, 2); fail() } catch (_: IllegalArgumentException) { }
    }
}
