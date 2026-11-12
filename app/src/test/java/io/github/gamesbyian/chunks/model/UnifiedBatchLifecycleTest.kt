package io.github.gamesbyian.chunks.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UnifiedBatchLifecycleTest {
    private val local = EnvironmentId("local")
    private val serverA = EnvironmentId("a".repeat(64))
    private val serverB = EnvironmentId("b".repeat(64))
    private val hash = "c".repeat(40)

    private fun observation(environment: EnvironmentId, infoHash: String, started: Boolean?) =
        UnifiedTransferObservation(UnifiedTransferKey.of(environment, infoHash), "n", "n",
            100, 0, started, null, null, null)

    private fun source(environment: EnvironmentId, vararg items: UnifiedTransferObservation,
                       availability: EnvironmentAvailability = EnvironmentAvailability.LIVE) =
        EnvironmentTransferSnapshot(environment, availability, true, 1L, items.toList())

    private class Recording(private val reply: (String) -> LifecycleReply) : UnifiedBatchLifecycle.Adapter {
        val calls = ArrayList<Pair<String, Boolean>>()
        override suspend fun setStarted(infoHash: String, start: Boolean): LifecycleReply {
            calls += infoHash to start
            return reply(infoHash)
        }
    }

    private fun target(o: UnifiedTransferObservation) = UnifiedBatchLifecycle.Target(o.key, o.started)

    @Test fun sameHashInTwoEnvironmentsRunsOnlyOnEachOwnAdapter() = runBlocking {
        val onLocal = observation(local, hash, false)
        val onA = observation(serverA, hash, false)
        val onB = observation(serverB, hash, false)
        val localAdapter = Recording { LifecycleReply.VERIFIED }
        val aAdapter = Recording { LifecycleReply.ACKNOWLEDGED }
        val bAdapter = Recording { LifecycleReply.VERIFIED }
        // Server B is visible but NOT selected: it must never receive a command.
        val report = UnifiedBatchLifecycle.run(listOf(target(onLocal), target(onA)), start = true,
            sources = listOf(source(local, onLocal), source(serverA, onA), source(serverB, onB)),
            adapters = mapOf(local to localAdapter, serverA to aAdapter, serverB to bAdapter))
        assertEquals(listOf(hash to true), localAdapter.calls)
        assertEquals(listOf(hash to true), aAdapter.calls)
        assertTrue(bAdapter.calls.isEmpty())
        assertEquals(listOf(local, serverA), report.environments.map { it.environment })
        assertEquals(CommandEvidence.VERIFIED, report.environments[0].items.single().evidence)
        assertEquals(CommandEvidence.ACKNOWLEDGED, report.environments[1].items.single().evidence)
        // Acknowledged-but-unread is not reported as full success.
        assertFalse(report.fullyVerified)
    }

    @Test fun unavailableEnvironmentsRefuseWithoutRedirectingToAnotherEngine() = runBlocking {
        val states = listOf(EnvironmentAvailability.OFFLINE, EnvironmentAvailability.LOCKED,
            EnvironmentAvailability.STALE)
        for (state in states) {
            val onA = observation(serverA, hash, false)
            val onLocal = observation(local, hash, false)
            val localAdapter = Recording { LifecycleReply.VERIFIED }
            val aAdapter = Recording { LifecycleReply.VERIFIED }
            val report = UnifiedBatchLifecycle.run(listOf(target(onA)), true,
                listOf(source(local, onLocal), source(serverA, onA, availability = state)),
                mapOf(local to localAdapter, serverA to aAdapter))
            assertEquals(state.name, CommandEvidence.REFUSED, report.items.single().evidence)
            assertTrue(localAdapter.calls.isEmpty())
            assertTrue(aAdapter.calls.isEmpty())
        }
        // Missing snapshot, missing adapter, item not in list and unknown state also refuse.
        val known = observation(serverA, hash, false)
        val unknownState = observation(serverA, "d".repeat(40), null)
        val absent = UnifiedTransferKey.of(serverA, "e".repeat(40))
        val report = UnifiedBatchLifecycle.run(listOf(target(known), target(unknownState),
            UnifiedBatchLifecycle.Target(absent, false),
            UnifiedBatchLifecycle.Target(UnifiedTransferKey.of(serverB, hash), false)), true,
            listOf(source(serverA, known, unknownState)), adapters = emptyMap())
        assertTrue(report.items.all { it.evidence == CommandEvidence.REFUSED })
    }

    @Test fun incompleteAndUnobservedSourcesNeverReceiveCommandsButHealthySourceCan() = runBlocking {
        val onA = observation(serverA, "1".repeat(40), false)
        val onB = observation(serverB, "2".repeat(40), false)
        val onLocal = observation(local, "3".repeat(40), false)
        val aAdapter = Recording { LifecycleReply.VERIFIED }
        val bAdapter = Recording { LifecycleReply.VERIFIED }
        val localAdapter = Recording { LifecycleReply.VERIFIED }
        val incomplete = source(serverA, onA).copy(inventoryComplete = false)
        val unobserved = source(serverB, onB).copy(observedAtEpochMillis = null)
        val report = UnifiedBatchLifecycle.run(
            listOf(target(onA), target(onB), target(onLocal)), start = true,
            sources = listOf(incomplete, unobserved, source(local, onLocal)),
            adapters = mapOf(serverA to aAdapter, serverB to bAdapter, local to localAdapter)
        )
        assertTrue(aAdapter.calls.isEmpty())
        assertTrue(bAdapter.calls.isEmpty())
        assertEquals(listOf(onLocal.key.infoHash to true), localAdapter.calls)
        assertEquals(listOf(CommandEvidence.REFUSED, CommandEvidence.REFUSED,
            CommandEvidence.VERIFIED), report.items.map { it.evidence })
        assertTrue(report.items[0].reason!!.contains("incomplete"))
        assertTrue(report.items[1].reason!!.contains("no verified observation"))
        assertNull(report.haltedOn)
    }

    @Test fun changedStateBetweenSelectionAndExecutionRefusesWithoutSendingCommand() = runBlocking {
        val selected = observation(serverA, hash, false)
        val nowRunning = selected.copy(started = true)
        val adapter = Recording { LifecycleReply.VERIFIED }
        val report = UnifiedBatchLifecycle.run(
            listOf(target(selected)), true, listOf(source(serverA, nowRunning)),
            mapOf(serverA to adapter))
        assertEquals(CommandEvidence.REFUSED, report.items.single().evidence)
        assertTrue(report.items.single().reason!!.contains("changed"))
        assertTrue(adapter.calls.isEmpty())
    }

    @Test fun acknowledgedWithoutReadbackHaltsEveryLaterAccountAndPreservesEvidence() = runBlocking {
        val first = observation(serverA, "1".repeat(40), false)
        val anotherOnSameServer = observation(serverA, "2".repeat(40), false)
        val sameHashOnDifferentServer = observation(serverB, first.key.infoHash, false)
        val aAdapter = Recording { LifecycleReply.ACKNOWLEDGED }
        val bAdapter = Recording { LifecycleReply.VERIFIED }
        val report = UnifiedBatchLifecycle.run(
            listOf(first, anotherOnSameServer, sameHashOnDifferentServer).map(::target), true,
            listOf(source(serverA, first, anotherOnSameServer), source(serverB, sameHashOnDifferentServer)),
            mapOf(serverA to aAdapter, serverB to bAdapter))
        assertEquals(listOf(first.key.infoHash to true), aAdapter.calls)
        assertTrue("No other account can receive a follow-on command before readback", bAdapter.calls.isEmpty())
        assertEquals(first.key, report.haltedOn)
        assertEquals(listOf(CommandEvidence.ACKNOWLEDGED, CommandEvidence.NOT_ATTEMPTED,
            CommandEvidence.NOT_ATTEMPTED), report.items.map { it.evidence })
        assertFalse(report.fullyVerified)
        assertTrue(report.describe { it.value }.last().contains("has not been verified"))
        assertTrue(report.describe { it.value }.last().contains("nothing was retried"))
    }

    @Test fun actualNotFoundMutationHaltsBatchAcrossAccountsWithoutRetargeting() = runBlocking {
        val first = observation(serverA, "3".repeat(40), true)
        val duplicateHashOnOtherServer = observation(serverB, first.key.infoHash, true)
        val aAdapter = Recording { LifecycleReply.NOT_FOUND }
        val bAdapter = Recording { LifecycleReply.VERIFIED }
        val report = UnifiedBatchLifecycle.run(
            listOf(first, duplicateHashOnOtherServer).map(::target), false,
            listOf(source(serverA, first), source(serverB, duplicateHashOnOtherServer)),
            mapOf(serverA to aAdapter, serverB to bAdapter))
        assertEquals(listOf(first.key.infoHash to false), aAdapter.calls)
        assertTrue("An actual not-found on A must never redirect/retry on B", bAdapter.calls.isEmpty())
        assertEquals(first.key, report.haltedOn)
        assertEquals(listOf(CommandEvidence.REFUSED, CommandEvidence.NOT_ATTEMPTED),
            report.items.map { it.evidence })
        assertEquals("No longer in this environment", report.items[0].reason)
        assertTrue(report.describe { it.value }.last().contains("transfer disappeared"))
    }

    @Test fun preflightInvalidTargetNeverCallsAdapterAndDoesNotHaltAnotherEnvironment() = runBlocking {
        val invalid = observation(serverA, "e".repeat(64), true)
        val eligible = observation(serverB, "f".repeat(40), true)
        val aAdapter = Recording { LifecycleReply.NOT_FOUND }
        val bAdapter = Recording { LifecycleReply.VERIFIED }
        val report = UnifiedBatchLifecycle.run(
            listOf(target(invalid), target(eligible)), false,
            listOf(source(serverA, invalid), source(serverB, eligible)),
            mapOf(serverA to aAdapter, serverB to bAdapter),
            preflightRefusals = mapOf(invalid.key to "Unsupported server identity; no RPC sent")
        )
        assertTrue("Invalid exact identity must not invoke any backend RPC", aAdapter.calls.isEmpty())
        assertEquals(listOf(eligible.key.infoHash to false), bAdapter.calls)
        assertNull("A pre-dispatch refusal cannot halt an unrelated eligible account", report.haltedOn)
        assertEquals(listOf(CommandEvidence.REFUSED, CommandEvidence.VERIFIED),
            report.items.map { it.evidence })
        assertEquals("Unsupported server identity; no RPC sent", report.items.first().reason)
    }

    @Test fun firstUncertainHaltsEveryLaterItemAcrossEnvironmentsWithoutRetry() = runBlocking {
        val first = observation(local, "1".repeat(40), true)
        val second = observation(local, "2".repeat(40), true)
        val third = observation(serverA, "3".repeat(40), true)
        val already = observation(serverA, "4".repeat(40), false)
        val localAdapter = Recording { if (it == first.key.infoHash) LifecycleReply.UNCERTAIN else LifecycleReply.VERIFIED }
        val aAdapter = Recording { LifecycleReply.VERIFIED }
        val report = UnifiedBatchLifecycle.run(listOf(first, second, third, already).map(::target),
            start = false, sources = listOf(source(local, first, second), source(serverA, third, already)),
            adapters = mapOf(local to localAdapter, serverA to aAdapter))
        assertEquals(listOf(first.key.infoHash to false), localAdapter.calls)
        assertTrue(aAdapter.calls.isEmpty())
        assertEquals(first.key, report.haltedOn)
        assertEquals(listOf(CommandEvidence.UNCERTAIN, CommandEvidence.NOT_ATTEMPTED,
            CommandEvidence.NOT_ATTEMPTED, CommandEvidence.NOT_ATTEMPTED), report.items.map { it.evidence })
    }

    @Test fun explicitLocalRefusalHaltsCrossEnvironmentBatchWithoutRetry() = runBlocking {
        val localTransfer = observation(local, "1".repeat(40), false)
        val remoteTransfer = observation(serverA, "2".repeat(40), false)
        val localAdapter = Recording { LifecycleReply.REFUSED }
        val serverAdapter = Recording { LifecycleReply.VERIFIED }
        val report = UnifiedBatchLifecycle.run(
            listOf(target(localTransfer), target(remoteTransfer)), true,
            sources = listOf(source(local, localTransfer), source(serverA, remoteTransfer)),
            adapters = mapOf(local to localAdapter, serverA to serverAdapter)
        )
        assertEquals(listOf(localTransfer.key.infoHash to true), localAdapter.calls)
        assertTrue(serverAdapter.calls.isEmpty())
        assertEquals(localTransfer.key, report.haltedOn)
        assertEquals(listOf(CommandEvidence.REFUSED, CommandEvidence.NOT_ATTEMPTED),
            report.items.map { it.evidence })
        assertTrue(report.describe { it.value }.last().contains("explicitly refused"))
    }

    @Test fun thrownFailureIsUncertainAndAlreadySatisfiedSendsNoCommand() = runBlocking {
        val running = observation(local, "1".repeat(40), true)
        val stopped = observation(local, "2".repeat(40), false)
        val adapter = Recording { error("socket closed") }
        val report = UnifiedBatchLifecycle.run(listOf(target(running), target(stopped)), true,
            listOf(source(local, running, stopped)), mapOf(local to adapter))
        assertEquals(listOf(stopped.key.infoHash to true), adapter.calls)
        assertEquals(CommandEvidence.ALREADY_SATISFIED, report.items[0].evidence)
        assertEquals(CommandEvidence.UNCERTAIN, report.items[1].evidence)
        val clean = UnifiedBatchLifecycle.run(listOf(target(running)), true,
            listOf(source(local, running)), mapOf(local to adapter))
        assertTrue(clean.fullyVerified)
    }

    @Test fun rejectsEmptyOversizedAndDuplicateSelections() = runBlocking {
        val item = observation(local, hash, false)
        for (bad in listOf(emptyList(), listOf(target(item), target(item)))) {
            try {
                UnifiedBatchLifecycle.run(bad, true, listOf(source(local, item)), emptyMap())
                fail("accepted $bad")
            } catch (_: IllegalArgumentException) { }
        }
    }
}

class LifecycleReplyMappingTest {
    @Test fun engineOutcomesMapWithoutUpgradingEvidence() {
        assertEquals(LifecycleReply.VERIFIED, io.github.gamesbyian.chunks.engine.LocalCommandOutcome.VERIFIED.toLifecycleReply())
        assertEquals(LifecycleReply.REFUSED, io.github.gamesbyian.chunks.engine.LocalCommandOutcome.REFUSED.toLifecycleReply())
        assertEquals(LifecycleReply.UNCERTAIN, io.github.gamesbyian.chunks.engine.LocalCommandOutcome.UNCERTAIN.toLifecycleReply())
        assertEquals(LifecycleReply.NOT_FOUND, io.github.gamesbyian.chunks.engine.LocalCommandOutcome.NOT_FOUND.toLifecycleReply())
        assertEquals(LifecycleReply.VERIFIED, io.github.gamesbyian.chunks.rpc.LifecycleOutcome.Verified(true).toLifecycleReply(true))
        assertEquals(LifecycleReply.UNCERTAIN, io.github.gamesbyian.chunks.rpc.LifecycleOutcome.Verified(false).toLifecycleReply(true))
        assertEquals(LifecycleReply.ACKNOWLEDGED, io.github.gamesbyian.chunks.rpc.LifecycleOutcome.Pending(true, false).toLifecycleReply(true))
        assertEquals(LifecycleReply.UNCERTAIN, io.github.gamesbyian.chunks.rpc.LifecycleOutcome.Uncertain(true).toLifecycleReply(true))
    }
}
