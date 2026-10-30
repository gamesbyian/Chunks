package io.github.gamesbyian.chunks.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UnifiedBatchLifecycleTest {
    private val phone = EnvironmentId("phone")
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
        val onPhone = observation(phone, hash, false)
        val onA = observation(serverA, hash, false)
        val onB = observation(serverB, hash, false)
        val phoneAdapter = Recording { LifecycleReply.VERIFIED }
        val aAdapter = Recording { LifecycleReply.ACKNOWLEDGED }
        val bAdapter = Recording { LifecycleReply.VERIFIED }
        // Server B is visible but NOT selected: it must never receive a command.
        val report = UnifiedBatchLifecycle.run(listOf(target(onPhone), target(onA)), start = true,
            sources = listOf(source(phone, onPhone), source(serverA, onA), source(serverB, onB)),
            adapters = mapOf(phone to phoneAdapter, serverA to aAdapter, serverB to bAdapter))
        assertEquals(listOf(hash to true), phoneAdapter.calls)
        assertEquals(listOf(hash to true), aAdapter.calls)
        assertTrue(bAdapter.calls.isEmpty())
        assertEquals(listOf(phone, serverA), report.environments.map { it.environment })
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
            val onPhone = observation(phone, hash, false)
            val phoneAdapter = Recording { LifecycleReply.VERIFIED }
            val aAdapter = Recording { LifecycleReply.VERIFIED }
            val report = UnifiedBatchLifecycle.run(listOf(target(onA)), true,
                listOf(source(phone, onPhone), source(serverA, onA, availability = state)),
                mapOf(phone to phoneAdapter, serverA to aAdapter))
            assertEquals(state.name, CommandEvidence.REFUSED, report.items.single().evidence)
            assertTrue(phoneAdapter.calls.isEmpty())
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
        val onPhone = observation(phone, "3".repeat(40), false)
        val aAdapter = Recording { LifecycleReply.VERIFIED }
        val bAdapter = Recording { LifecycleReply.VERIFIED }
        val phoneAdapter = Recording { LifecycleReply.VERIFIED }
        val incomplete = source(serverA, onA).copy(inventoryComplete = false)
        val unobserved = source(serverB, onB).copy(observedAtEpochMillis = null)
        val report = UnifiedBatchLifecycle.run(
            listOf(target(onA), target(onB), target(onPhone)), start = true,
            sources = listOf(incomplete, unobserved, source(phone, onPhone)),
            adapters = mapOf(serverA to aAdapter, serverB to bAdapter, phone to phoneAdapter)
        )
        assertTrue(aAdapter.calls.isEmpty())
        assertTrue(bAdapter.calls.isEmpty())
        assertEquals(listOf(onPhone.key.infoHash to true), phoneAdapter.calls)
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

    @Test fun firstUncertainHaltsEveryLaterItemAcrossEnvironmentsWithoutRetry() = runBlocking {
        val first = observation(phone, "1".repeat(40), true)
        val second = observation(phone, "2".repeat(40), true)
        val third = observation(serverA, "3".repeat(40), true)
        val already = observation(serverA, "4".repeat(40), false)
        val phoneAdapter = Recording { if (it == first.key.infoHash) LifecycleReply.UNCERTAIN else LifecycleReply.VERIFIED }
        val aAdapter = Recording { LifecycleReply.VERIFIED }
        val report = UnifiedBatchLifecycle.run(listOf(first, second, third, already).map(::target),
            start = false, sources = listOf(source(phone, first, second), source(serverA, third, already)),
            adapters = mapOf(phone to phoneAdapter, serverA to aAdapter))
        assertEquals(listOf(first.key.infoHash to false), phoneAdapter.calls)
        assertTrue(aAdapter.calls.isEmpty())
        assertEquals(first.key, report.haltedOn)
        assertEquals(listOf(CommandEvidence.UNCERTAIN, CommandEvidence.NOT_ATTEMPTED,
            CommandEvidence.NOT_ATTEMPTED, CommandEvidence.NOT_ATTEMPTED), report.items.map { it.evidence })
    }

    @Test fun explicitPhoneRefusalHaltsCrossEnvironmentBatchWithoutRetry() = runBlocking {
        val phoneTransfer = observation(phone, "1".repeat(40), false)
        val remoteTransfer = observation(serverA, "2".repeat(40), false)
        val phoneAdapter = Recording { LifecycleReply.REFUSED }
        val serverAdapter = Recording { LifecycleReply.VERIFIED }
        val report = UnifiedBatchLifecycle.run(
            listOf(target(phoneTransfer), target(remoteTransfer)), true,
            sources = listOf(source(phone, phoneTransfer), source(serverA, remoteTransfer)),
            adapters = mapOf(phone to phoneAdapter, serverA to serverAdapter)
        )
        assertEquals(listOf(phoneTransfer.key.infoHash to true), phoneAdapter.calls)
        assertTrue(serverAdapter.calls.isEmpty())
        assertEquals(phoneTransfer.key, report.haltedOn)
        assertEquals(listOf(CommandEvidence.REFUSED, CommandEvidence.NOT_ATTEMPTED),
            report.items.map { it.evidence })
        assertTrue(report.describe { it.value }.last().contains("explicitly refused"))
    }

    @Test fun thrownFailureIsUncertainAndAlreadySatisfiedSendsNoCommand() = runBlocking {
        val running = observation(phone, "1".repeat(40), true)
        val stopped = observation(phone, "2".repeat(40), false)
        val adapter = Recording { error("socket closed") }
        val report = UnifiedBatchLifecycle.run(listOf(target(running), target(stopped)), true,
            listOf(source(phone, running, stopped)), mapOf(phone to adapter))
        assertEquals(listOf(stopped.key.infoHash to true), adapter.calls)
        assertEquals(CommandEvidence.ALREADY_SATISFIED, report.items[0].evidence)
        assertEquals(CommandEvidence.UNCERTAIN, report.items[1].evidence)
        val clean = UnifiedBatchLifecycle.run(listOf(target(running)), true,
            listOf(source(phone, running)), mapOf(phone to adapter))
        assertTrue(clean.fullyVerified)
    }

    @Test fun rejectsEmptyOversizedAndDuplicateSelections() = runBlocking {
        val item = observation(phone, hash, false)
        for (bad in listOf(emptyList(), listOf(target(item), target(item)))) {
            try {
                UnifiedBatchLifecycle.run(bad, true, listOf(source(phone, item)), emptyMap())
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
