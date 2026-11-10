package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import io.github.gamesbyian.chunks.model.CommandEvidence
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * No network access. Each fixture has the SAME 40-hex infohash; the route and
 * authenticated transport object, not the hash or a display name, decide
 * whether canonical Details/Batch can offer and lease any remote command.
 * Real authenticated Start/Stop and exact file checks are separate CI gates.
 */
class CombinedCanonicalPrincipalGuardTest {
    private val hash = "c".repeat(40)
    private val alice = RTorrentRemote(RemoteCredentials(
        "https://box.example/rutorrent1/RPC2", "alice", "disposable"))
    private val bob = RTorrentRemote(RemoteCredentials(
        "https://box.example/rutorrent2/RPC2", "bob", "disposable"))
    private val aliceScope = alice.addAccountScope()
    private val aliceId = requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(aliceScope))
    private val row = RemoteTransfer(hash, "Duplicate name and hash", 10, 5,
        false, "/data", 0, 0)
    private var begins = 0
    private var commands = 0
    private var settles = 0

    private fun lifecycle(bound: RTorrentRemote) = CombinedServerLifecycle(
        begin = { begins++; true },
        settled = { settles++ },
        setStarted = { _, start -> commands++; LifecycleOutcome.Verified(start) },
        ownerSession = bound
    )

    private fun source(advertisedScope: String, session: Any?, gate: CombinedServerLifecycle) =
        CombinedServerSource(advertisedScope, listOf(row), stale = false,
            fullView = true, observedAtEpochMillis = System.currentTimeMillis(),
            lifecycle = gate, authenticatedSession = session)

    @Test fun onlyExactBoundTransportIsEligibleForCanonicalLifecycle() {
        val good = source(aliceScope, alice, lifecycle(alice))
        assertSame(alice, verifiedCombinedLifecycleOwner(good, aliceId))
        assertNull(verifiedCombinedLifecycleOwner(good,
            requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(bob.addAccountScope()))))
        assertNull(verifiedCombinedLifecycleOwner(source(aliceScope, alice, lifecycle(bob)), aliceId))
        assertNull(verifiedCombinedLifecycleOwner(source(aliceScope, bob, lifecycle(bob)), aliceId))
        assertNull(verifiedCombinedLifecycleOwner(source(aliceScope, null, lifecycle(alice)), aliceId))
        assertNull(verifiedCombinedLifecycleOwner(source(aliceScope, alice,
            CombinedServerLifecycle(begin = { true }, settled = {},
                setStarted = { _, started -> LifecycleOutcome.Verified(started) })), aliceId))
    }

    @Test fun sameHashDifferentNumberedInstanceCannotAcquireLeaseOrDispatch() = runBlocking {
        for (wrong in listOf(
            source(aliceScope, alice, lifecycle(bob)),
            source(aliceScope, bob, lifecycle(bob)),
            source(aliceScope, null, lifecycle(alice))
        )) {
            begins = 0; commands = 0; settles = 0
            val key = UnifiedTransferKey.of(aliceId, hash)
            val observation = UnifiedTransferObservation(
                key, row.name, row.name, 10L, 5L, false, null, null, null)
            val snapshot = EnvironmentTransferSnapshot(aliceId,
                EnvironmentAvailability.LIVE, true, System.currentTimeMillis(),
                listOf(observation))
            val result = runCombinedLifecycle(setOf(key), true, listOf(snapshot),
                wrong.lifecycle, aliceId, serverSource = wrong, phoneEngine = { null })
            assertEquals(CommandEvidence.REFUSED, result.items.single().evidence)
            assertTrue(result.items.single().reason.orEmpty().contains("Authenticated rTorrent"))
            assertEquals("No foreign account may acquire the command gate", 0, begins)
            assertEquals("No foreign same-hash RPC callback can run", 0, commands)
            assertEquals("No unacquired command lease is settled", 0, settles)
        }
    }
}
