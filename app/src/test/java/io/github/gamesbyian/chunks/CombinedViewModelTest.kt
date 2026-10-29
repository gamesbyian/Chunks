package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import io.github.gamesbyian.chunks.model.UnifiedSortDirection
import io.github.gamesbyian.chunks.model.UnifiedSortKey
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import io.github.gamesbyian.chunks.privacy.TransferLockMode
import io.github.gamesbyian.chunks.privacy.TransferPrivacyVisibility
import org.junit.Assert.*
import org.junit.Test

class CombinedViewModelTest {
    private val phone = EnvironmentId("phone")
    private val server = EnvironmentId("a".repeat(64))

    @Test fun viewPreferencesRoundTripWithoutSearchAndRejectMalformedValues() {
        val query = UnifiedTransferQuery(scope = EnvironmentViewScope.Only(setOf(server, phone)),
            search = "secret words", sortKey = UnifiedSortKey.SIZE,
            direction = UnifiedSortDirection.ASCENDING, groupByEnvironment = true)
        val decoded = CombinedViewPreferences.decode(CombinedViewPreferences.encode(query))
        assertEquals(query.copy(search = ""), decoded)
        assertFalse(CombinedViewPreferences.encode(query).contains("secret"))
        assertEquals(EnvironmentViewScope.All, CombinedViewPreferences.decode(
            CombinedViewPreferences.encode(UnifiedTransferQuery())).scope)
        for (bad in listOf(null, "", "*;SIZE;UP;0", "*;HEIGHT;ASCENDING;0", "*;SIZE;ASCENDING;2",
            "not-an-id;SIZE;ASCENDING;0", "phone,not-an-id;SIZE;ASCENDING;0", "*;SIZE;ASCENDING")) {
            assertEquals(bad.toString(), UnifiedTransferQuery(), CombinedViewPreferences.decode(bad))
        }
    }

    @Test fun scopeChipsSelectOneThenToggleAndNeverProduceAnEmptySubset() {
        val one = toggleScope(EnvironmentViewScope.All, server)
        assertEquals(EnvironmentViewScope.Only(setOf(server)), one)
        val two = toggleScope(one, phone)
        assertEquals(EnvironmentViewScope.Only(setOf(server, phone)), two)
        assertEquals(EnvironmentViewScope.Only(setOf(phone)), toggleScope(two, server))
        assertEquals(EnvironmentViewScope.All, toggleScope(EnvironmentViewScope.Only(setOf(phone)), phone))
    }

    @Test fun privacyDecisionFailsClosedAndNeverRevealsWithoutDeviceLock() {
        fun d(mode: TransferLockMode, sync: Boolean = true, secure: Boolean = true, may: Boolean = true) =
            TransferPrivacyVisibility.decide(mode, sync, secure, may)
        assertEquals(TransferPrivacyVisibility.Decision(true, false), d(TransferLockMode.NEVER))
        assertTrue(d(TransferLockMode.EVERY_ENTRY).mayShow)
        assertTrue(d(TransferLockMode.EVERY_ENTRY).needsSecureWindow)
        assertFalse(d(TransferLockMode.EVERY_ENTRY, may = false).mayShow)
        assertFalse(d(TransferLockMode.ONCE_PER_PROCESS, secure = false).mayShow)
        // A failed configure never turns a stale opt-out into disclosure.
        assertFalse(d(TransferLockMode.NEVER, sync = false, may = false).mayShow)
        assertTrue(d(TransferLockMode.NEVER, sync = false).needsSecureWindow)
    }

    @Test fun rowFactsSayUnknownInsteadOfZero() {
        val unknown = UnifiedTransferObservation(UnifiedTransferKey.of(server, "b".repeat(40)), "x", "x",
            null, null, null, null, null, null)
        assertEquals("State unknown · Progress unknown · Size unknown · ↓ not reported · ↑ not reported",
            combinedRowFacts(unknown))
        val known = unknown.copy(sizeBytes = 2048, progressPpm = 500_000, started = true,
            downloadBytesPerSecond = 0, uploadBytesPerSecond = 1024)
        assertEquals("Started · 50% · 2.0 KiB · ↓ 0 B/s · ↑ 1.0 KiB/s", combinedRowFacts(known))
    }
}
