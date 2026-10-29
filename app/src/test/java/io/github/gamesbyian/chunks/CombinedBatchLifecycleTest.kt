package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.CommandEvidence
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import io.github.gamesbyian.chunks.model.describe
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CombinedBatchLifecycleTest {
    private val phone = EnvironmentSnapshots.PHONE
    private val server = EnvironmentId("a".repeat(64))
    private val other = EnvironmentId("b".repeat(64))
    private val hash = "c".repeat(40)

    private fun row(env: EnvironmentId, started: Boolean) = UnifiedTransferObservation(
        UnifiedTransferKey.of(env, hash), "n", "n", 1, 0, started, null, null, null)
    private fun source(env: EnvironmentId, vararg rows: UnifiedTransferObservation,
                       availability: EnvironmentAvailability = EnvironmentAvailability.LIVE) =
        EnvironmentTransferSnapshot(env, availability, true, 1, rows.toList())

    private class FakeGate(private val grant: Boolean) {
        var begins = 0; var settles = 0
        val calls = ArrayList<Pair<String, Boolean>>()
        val lifecycle = CombinedServerLifecycle(
            begin = { begins++; grant },
            settled = { settles++ },
            setStarted = { h, on -> calls += h to on; LifecycleOutcome.Verified(on) })
    }

    @Test fun serverRunsUnderItsOwnLeaseAndSettlesExactlyOnce() = runBlocking {
        val gate = FakeGate(grant = true)
        val onServer = row(server, false)
        val onPhone = row(phone, false)
        val report = runCombinedLifecycle(setOf(onServer.key, onPhone.key), true,
            listOf(source(phone, onPhone), source(server, onServer)), gate.lifecycle, server,
            phoneEngine = { null })
        assertEquals(listOf(hash to true), gate.calls)
        assertEquals(1, gate.begins)
        assertEquals(1, gate.settles)
        val byEnv = report.environments.associate { it.environment to it.items.single() }
        assertEquals(CommandEvidence.VERIFIED, byEnv.getValue(server).evidence)
        assertEquals(CommandEvidence.REFUSED, byEnv.getValue(phone).evidence)
        assertEquals("phone engine is not running", byEnv.getValue(phone).reason)
        // The phone fence was never taken, so it is still free.
        assertFalse(PhoneMutationFence.revision.blocksMutations())
    }

    @Test fun busyGateStaleSourceAndUnconnectedProfileNeverReceiveCommands() = runBlocking {
        val busy = FakeGate(grant = false)
        val report = runCombinedLifecycle(setOf(row(server, true).key), false,
            listOf(source(server, row(server, true))), busy.lifecycle, server, phoneEngine = { null })
        assertTrue(busy.calls.isEmpty())
        assertEquals(0, busy.settles)
        assertEquals("server is busy or refreshing", report.items.single().reason)

        val gate = FakeGate(grant = true)
        val stale = runCombinedLifecycle(setOf(row(server, true).key), false,
            listOf(source(server, row(server, true), availability = EnvironmentAvailability.STALE)),
            gate.lifecycle, server, phoneEngine = { null })
        assertEquals(0, gate.begins)
        assertEquals(CommandEvidence.REFUSED, stale.items.single().evidence)

        val offline = runCombinedLifecycle(setOf(row(other, true).key), false,
            listOf(source(other, row(other, true))), gate.lifecycle, server, phoneEngine = { null })
        assertTrue(gate.calls.isEmpty())
        assertEquals("only listed here; sign in to this server to change it", offline.items.single().reason)
    }

    @Test fun previewAndResultsNameEveryEnvironment() = runBlocking {
        val labels = mapOf(phone to "On this phone", server to "Server · ian @ box")
        val preview = combinedBatchPreview(false, setOf(row(phone, true).key, row(server, true).key), labels)
        assertTrue(preview, preview.startsWith("Stop 2 selected"))
        assertTrue(preview, preview.contains("On this phone: 1; Server · ian @ box: 1"))
        val gate = FakeGate(grant = true)
        val report = runCombinedLifecycle(setOf(row(server, true).key, row(phone, true).key), false,
            listOf(source(phone, row(phone, true)), source(server, row(server, true))), gate.lifecycle, server,
            phoneEngine = { null })
        // Environments run in stable-ID order, independent of selection order.
        assertEquals(listOf("Server · ian @ box: 1 confirmed stopped.",
            "On this phone: 1 not attempted (phone engine is not running)."), report.describe { labels.getValue(it) })
    }
}
