package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.*
import org.junit.Test

class RemoteDeleteCapabilityTest {
    private val collect = "d.open= ; branch=d.custom5=,\"f.multicall=,file.append=/profile/erasedata/,f.frozen_path=\""
    private val finish = "branch=d.custom5=,execute={/web/erasedata/fin.sh,/profile/erasedata,system.pid=,d.base_path=,d.is_multi_file=}"
    @Test fun legacyRequiresBothActualPrincipalHooksAndBrowserCommands() {
        val hooks = mapOf("erasedata0alice" to collect, "erasedata1alice" to finish)
        assertTrue(RuTorrentDeleteData.registeredLegacyHooks(hooks, "alice"))
        assertFalse(RuTorrentDeleteData.registeredLegacyHooks(hooks, "bob"))
        assertFalse(RuTorrentDeleteData.registeredLegacyHooks(hooks.filterKeys { it.startsWith("erasedata0") }, "alice"))
        assertFalse(RuTorrentDeleteData.registeredLegacyHooks(mapOf("erasedata0alice" to "d.custom5=", "erasedata1alice" to finish), "alice"))
        assertFalse(RuTorrentDeleteData.legacyBrowserAdapter("d.set_custom5"))
    }
    @Test fun freshnessDoesNotCrossAccountsOrSurviveClockRollback() {
        val cap = RemoteDeleteCapability(RemoteCapabilityState.SUPPORTED,
            RemoteDeleteAdapter.REGISTERED_ERASE_HOOKS, "Observed hooks", 100_000, "alice@mount2")
        assertTrue(cap.isFresh("alice@mount2", 110_000))
        assertFalse(cap.isFresh("bob@mount2", 110_000))
        assertFalse(cap.isFresh("alice@mount1", 110_000))
        assertFalse(cap.isFresh("alice@mount2", 99_999))
        assertFalse(cap.isFresh("alice@mount2", 130_001))
    }
    @Test fun partialOrFaultedBatchIsNeverAQueueReceipt() {
        assertTrue(RuTorrentDeleteData.acceptedLegacyResponse(listOf(listOf("1"), listOf(0L), listOf(0L))))
        assertFalse(RuTorrentDeleteData.acceptedLegacyResponse(listOf(listOf("1"), listOf(0L))))
        assertFalse(RuTorrentDeleteData.acceptedLegacyResponse(listOf(listOf("1"), mapOf("faultCode" to -1L), listOf(0L))))
        assertFalse(RuTorrentDeleteData.acceptedLegacyResponse(listOf(listOf("1"), listOf(0L), listOf(1L))))
    }
}
