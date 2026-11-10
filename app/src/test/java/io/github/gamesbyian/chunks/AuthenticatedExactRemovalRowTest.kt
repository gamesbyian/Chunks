package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Destructive presentation eligibility makes NO RPC calls: two real-looking
 * endpoint/principal transports can expose the SAME hash, yet only the
 * adapter whose own credential scope equals the selected source is eligible.
 * The installed collector/canary proof is a separate disposable-peer gate.
 */
class AuthenticatedExactRemovalRowTest {
    private val hash = "ab".repeat(20)
    private val alice = RemoteCredentials(
        "http://127.0.0.1:18080/rutorrent1/RPC2", "alice", "dummy-alice")
    private val bob = RemoteCredentials(
        "http://127.0.0.1:18080/rutorrent2/RPC2", "bob", "dummy-bob")
    private val aliceAdapter = RTorrentRemote(alice)
    private val bobAdapter = RTorrentRemote(bob)
    private val aliceScope = aliceAdapter.addAccountScope()
    private val bobScope = bobAdapter.addAccountScope()
    private val aliceId = requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(aliceScope))
    private val bobId = requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(bobScope))
    private val aliceKey = UnifiedTransferKey.of(aliceId, hash)
    private val aliceRow = RemoteTransfer(hash, "Same hash on A", 100, 80, false, "/a", 0, 0)
    private val bobRow = RemoteTransfer(hash, "Same hash on B", 100, 20, false, "/b", 0, 0)

    private fun source(alias: String, adapter: Any?, rows: List<RemoteTransfer>) =
        CombinedServerSource(alias, rows, stale = false, fullView = true,
            observedAtEpochMillis = 1000L, authenticatedSession = adapter)

    @Test fun sameHashWrongPrincipalOrNumberedRouteIsNotEligibleForDelete() {
        val crossWired = source(aliceScope, bobAdapter, listOf(aliceRow))
        assertNull(authenticatedExactRemovalRow(aliceKey, crossWired, aliceId))
        // Even matching displayed ID + hash + name + Place cannot grant the
        // wrong adapter a session Remove or ruTorrent erasedata button.
        assertNull(authenticatedExactRemovalRow(aliceKey,
            source(bobScope, aliceAdapter, listOf(bobRow)), bobId))
        assertNull(authenticatedExactRemovalRow(aliceKey,
            source(aliceScope, aliceAdapter, listOf(aliceRow)), bobId))
    }

    @Test fun exactPrincipalAndUniqueHashCanPresentExistingGuardedControl() {
        val source = source(aliceScope, aliceAdapter, listOf(aliceRow))
        assertSame(aliceRow, authenticatedExactRemovalRow(aliceKey, source, aliceId))
        assertNull(authenticatedExactRemovalRow(
            UnifiedTransferKey.of(bobId, hash), source, aliceId))
        assertNull(authenticatedExactRemovalRow(
            UnifiedTransferKey.of(aliceId, "cd".repeat(20)), source, aliceId))
        assertNull(authenticatedExactRemovalRow(
            aliceKey, source(aliceScope, aliceAdapter, listOf(aliceRow, aliceRow)), aliceId))
    }

    @Test fun unverifiedOrAbsentAdapterCannotGrantDelete() {
        assertNull(authenticatedExactRemovalRow(aliceKey, source(aliceScope, null, listOf(aliceRow)), aliceId))
        assertNull(authenticatedExactRemovalRow(aliceKey, source(aliceScope, Any(), listOf(aliceRow)), aliceId))
        assertNull(authenticatedExactRemovalRow(aliceKey, null, aliceId))
        assertEquals(aliceScope, aliceAdapter.addAccountScope())
    }
}
