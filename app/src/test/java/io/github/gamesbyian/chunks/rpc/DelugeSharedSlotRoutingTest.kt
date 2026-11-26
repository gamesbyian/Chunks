package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #2072: two Chunks profiles on DIFFERENT daemons behind ONE Deluge Web UI share the Web UI's
 * single daemon slot. Chunks' own brackets (bind, read/write, post-check) must be serialized
 * per Web UI so one profile never disturbs the other's bracket. External switches stay refused.
 */
class DelugeSharedSlotRoutingTest {
    private val a = "aaaa0000aaaa0000aaaa0000aaaa0000"
    private val b = "bbbb0000bbbb0000bbbb0000bbbb0000"
    private val c = "cccc0000cccc0000cccc0000cccc0000"
    private val rounds = 12

    private fun ownerOf(path: String) = DelugeRoutingOwner.forEndpoint(java.net.URI(creds(path).endpoint))
    private suspend fun awaitQueued(owner: DelugeRoutingOwner, n: Int) {
        withTimeout(10_000) { while (owner.queued != n) kotlinx.coroutines.delay(2) }
    }

    private fun creds(path: String) = RemoteCredentials("http://127.0.0.1:18999/$path/json", "", "web-password-$path")
    private fun magnet(seed: Int) = "%040x".format(seed.toLong() * 7919L + 0xABCDEFL).let {
        ClientAddSource.Magnet("magnet:?xt=urn:btih:$it&dn=fixture")
    }
    private suspend fun attempt(block: suspend () -> Unit): String? = try { block(); null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message}" }

    /** Concurrent A/B refreshes with fresh clients (each one connects) AND long-lived clients. */
    private suspend fun hammer(ui: FakeSharedSlotWebUi, c: RemoteCredentials, persistent: Boolean): List<String> {
        val pa = DelugeClient(c, a, ui); val pb = DelugeClient(c, b, ui)
        // Warm up: each persistent client has connected once, as in a running app.
        if (persistent) { pa.torrents(); pb.torrents() }
        fun clientA() = if (persistent) pa else DelugeClient(c, a, ui)
        fun clientB() = if (persistent) pb else DelugeClient(c, b, ui)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        coroutineScope {
            (0 until rounds).flatMap { r ->
                listOf(
                    async(Dispatchers.IO) { attempt { clientA().torrents() }?.let { failures += "A read $r $it" } },
                    async(Dispatchers.IO) { attempt { clientB().torrents() }?.let { failures += "B read $r $it" } },
                    async(Dispatchers.IO) { attempt { clientA().add(ClientAddRequest(magnet(r), null, startNow = false)) }?.let { failures += "A add $r $it" } },
                    async(Dispatchers.IO) { attempt { clientB().add(ClientAddRequest(magnet(1000 + r), null, startNow = false)) }?.let { failures += "B add $r $it" } },
                )
            }.awaitAll()
        }
        return failures
    }

    @Test fun concurrentTwoDaemonBracketsNeverInterfereWithFreshClients(): Unit = runBlocking {
        val ui = FakeSharedSlotWebUi(listOf(a, b))
        val failures = hammer(ui, creds("fresh"), persistent = false)
        assertEquals("Chunks' own brackets disturbed each other: ${failures.take(5)}", emptyList<String>(), failures)
        assertEquals((0 until rounds).map { "%040x".format(it.toLong() * 7919L + 0xABCDEFL) }.toSet(), ui.inventories.getValue(a).keys.toSet())
        assertTrue(ui.writes.filter { it.first == a }.size == rounds && ui.writes.filter { it.first == b }.size == rounds)
    }

    @Test fun concurrentTwoDaemonBracketsNeverInterfereWithPersistentClients(): Unit = runBlocking {
        val ui = FakeSharedSlotWebUi(listOf(a, b))
        val failures = hammer(ui, creds("persistent"), persistent = true)
        assertEquals("Chunks' own brackets disturbed each other: ${failures.take(5)}", emptyList<String>(), failures)
        assertEquals(rounds, ui.inventories.getValue(a).size)
        assertEquals(rounds, ui.inventories.getValue(b).size)
        // Every write executed on the daemon the issuing client selected.
        assertTrue(ui.writes.all { (daemon, hash) -> ui.inventories.getValue(daemon!!).containsKey(hash) })
    }

    @Test fun inventoryReadsAndClientBracketsShareOneRouteAcrossAdapters(): Unit = runBlocking {
        val ui = FakeSharedSlotWebUi(listOf(a, b))
        val cr = creds("adapters")
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        coroutineScope {
            (0 until rounds).flatMap { r ->
                listOf(
                    async(Dispatchers.IO) { attempt { DelugeReadOnlyRpc(cr, a, ui).inspect() }?.let { failures += "inspect A $r $it" } },
                    async(Dispatchers.IO) { attempt { DelugeReadOnlyRpc(cr, b, ui).inspect() }?.let { failures += "inspect B $r $it" } },
                    async(Dispatchers.IO) { attempt { DelugeClient(cr, a, ui).add(ClientAddRequest(magnet(r), null, false)) }?.let { failures += "add A $r $it" } },
                    async(Dispatchers.IO) { attempt { DelugeClient(cr, b, ui).add(ClientAddRequest(magnet(500 + r), null, false)) }?.let { failures += "add B $r $it" } },
                )
            }.awaitAll()
        }
        assertEquals("$failures", emptyList<String>(), failures.take(5))
    }

    @Test fun identicalHashesWithDistinctPayloadFoldersStayOnTheirOwnDaemon(): Unit = runBlocking {
        val ui = FakeSharedSlotWebUi(listOf(a, b))
        val cr = creds("identical")
        val hash = "%040x".format(0x1234567L)
        val source = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash&dn=same")
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val ca = DelugeClient(cr, a, ui); val cb = DelugeClient(cr, b, ui)
        coroutineScope {
            repeat(8) {
                launch(Dispatchers.IO) { attempt { ca.add(ClientAddRequest(source, "/payload/a", false)) }
                    ?.takeUnless { m -> "duplicate" in m }?.let { failures += "A $it" } }
                launch(Dispatchers.IO) { attempt { cb.add(ClientAddRequest(source, "/payload/b", false)) }
                    ?.takeUnless { m -> "duplicate" in m }?.let { failures += "B $it" } }
                launch(Dispatchers.IO) { attempt { ca.torrent(hash)?.let { r -> if (r.downloadDirectory != "/payload/a") failures += "A read ${r.downloadDirectory}" } } }
                launch(Dispatchers.IO) { attempt { cb.torrent(hash)?.let { r -> if (r.downloadDirectory != "/payload/b") failures += "B read ${r.downloadDirectory}" } } }
            }
        }
        assertEquals("$failures", emptyList<String>(), failures.take(5))
        assertEquals("/payload/a", ca.torrent(hash)!!.downloadDirectory)
        assertEquals("/payload/b", cb.torrent(hash)!!.downloadDirectory)
        assertEquals("/payload/a", ui.inventories.getValue(a)[hash])
        assertEquals("/payload/b", ui.inventories.getValue(b)[hash])
    }

    @Test fun externalSlotSwitchBetweenBracketsIsStillRefusedAndNothingIsWritten(): Unit = runBlocking {
        val ui = FakeSharedSlotWebUi(listOf(a, b, c))
        val cr = creds("external")
        val ca = DelugeClient(cr, a, ui); val cb = DelugeClient(cr, b, ui)
        ca.torrents(); cb.torrents(); ca.torrents() // Chunks' own switching is repaired: slot ends on A.
        assertEquals(a, ui.slot)
        ui.slot = c // an actor outside Chunks takes the shared slot
        assertTrue("A read must be refused", attempt { ca.torrents() } != null)
        assertTrue("B read must be refused", attempt { cb.torrents() } != null)
        val before = ui.writes.size
        assertTrue("A write must be refused", attempt { ca.add(ClientAddRequest(magnet(9), null, false)) } != null)
        assertTrue("B write must be refused", attempt { cb.add(ClientAddRequest(magnet(10), null, false)) } != null)
        assertEquals("nothing written while the slot was foreign", before, ui.writes.size)
        assertEquals(c, ui.slot)
    }

    @Test fun externalSwitchBetweenTwoOwnBracketsIsNotMistakenForOwnInterference(): Unit = runBlocking {
        val ui = FakeSharedSlotWebUi(listOf(a, b, c))
        val cr = creds("external2")
        val ca = DelugeClient(cr, a, ui); val cb = DelugeClient(cr, b, ui)
        cb.torrents(); ca.torrents() // last own bind = A
        ui.slot = b // external actor binds B, a daemon Chunks also uses
        assertTrue("A must refuse: its own last bind was moved by someone else", attempt { ca.torrents() } != null)
        assertEquals(b, ui.slot)
    }

    @Test fun observingAnExternallyMovedSlotDoesNotLaunderItIntoOwnInterference(): Unit = runBlocking {
        val ui = FakeSharedSlotWebUi(listOf(a, b))
        val cr = creds("launder")
        val ca = DelugeClient(cr, a, ui); val cb = DelugeClient(cr, b, ui)
        cb.torrents(); ca.torrents() // Chunks' own web.connect left the slot on A
        ui.slot = b // outside actor
        cb.torrents() // B merely observes the slot on itself and proceeds
        assertTrue("A stays refused", attempt { ca.torrents() } != null)
        assertTrue("A write stays refused", attempt { ca.add(ClientAddRequest(magnet(21), null, false)) } != null)
        assertEquals(b, ui.slot)
        assertTrue(ui.writes.isEmpty())
    }

    @Test fun externalSwitchDuringABracketIsUncertainForWritesAndNeverRepaired(): Unit = runBlocking {
        val ui = FakeSharedSlotWebUi(listOf(a, b))
        val cr = creds("midbracket")
        val ca = DelugeClient(cr, a, ui)
        ca.torrents()
        ui.switchSlotAfterNextCore = b // an outside actor moves the slot while the read executes
        val read = attempt { ca.torrents() }
        assertTrue("read bracket must fail when the slot moved during it: $read", read != null && "IOException" in read)
        // The slot now sits on B, which Chunks never bound: not Chunks' own doing, so never rebound.
        assertTrue("moved slot stays refused", attempt { ca.torrents() } != null)

        val ub = FakeSharedSlotWebUi(listOf(a, b))
        val cw = DelugeClient(creds("midbracket-write"), a, ub)
        cw.torrents()
        ub.switchSlotAfterNextCore = b
        val write = attempt { cw.add(ClientAddRequest(magnet(3), null, false)) }
        assertTrue("write must be uncertain, not success or a replay: $write", write != null && "ClientWriteUncertainException" in write)
        assertEquals("the write was dispatched exactly once, never replayed", 1, ub.count("core.add_torrent_magnet"))
        assertTrue(attempt { cw.add(ClientAddRequest(magnet(3), null, false)) } != null)
        assertEquals("no second dispatch while the slot is foreign", 1, ub.count("core.add_torrent_magnet"))
    }

    @Test fun nestedOperationsDoNotDeadlock(): Unit = runBlocking {
        val ui = FakeSharedSlotWebUi(listOf(a, b))
        val cr = creds("nested")
        val ca = DelugeClient(cr, a, ui)
        withTimeout(10_000) {
            // setFilePriorities -> files -> connect -> call, each already inside a bracket.
            ca.setFilePriorities("%040x".format(5L), mapOf(0 to ClientFilePriority.HIGH))
            ca.session()
            ca.seedingFacts("%040x".format(5L))
        }
        assertEquals(1, ui.writes.size)
    }

    @Test fun unrelatedEndpointsDoNotContend(): Unit = runBlocking {
        val ui1 = FakeSharedSlotWebUi(listOf(a)); val ui2 = FakeSharedSlotWebUi(listOf(a))
        val c1 = DelugeClient(creds("unrelated-1"), a, ui1); val c2 = DelugeClient(creds("unrelated-2"), a, ui2)
        c1.torrents()
        ui1.gateNext("core.get_torrents_status")
        val blocked = async(Dispatchers.IO) { c1.torrents() }
        ui1.gateEntered.await()
        withTimeout(5_000) { c2.torrents() } // a different Web UI is not held up
        ui1.gateRelease.complete(Unit)
        blocked.await()
    }

    @Test fun sameEndpointWithDifferentCredentialsIntentionallySharesTheLock(): Unit = runBlocking {
        // Deluge's Web UI is password-only and its daemon slot is global, so a second profile with other
        // credentials behind the SAME endpoint must queue behind the first, not fight it over the slot.
        val ui = FakeSharedSlotWebUi(listOf(a, b))
        val first = DelugeClient(creds("shared-cred"), a, ui)
        val other = DelugeClient(RemoteCredentials(creds("shared-cred").endpoint, "someone", "another-password"), b, ui)
        first.torrents()
        ui.gateNext("core.get_torrents_status")
        val holder = async(Dispatchers.IO) { first.torrents() }
        ui.gateEntered.await()
        val queuedOne = async(Dispatchers.IO) { other.torrents() }
        awaitQueued(ownerOf("shared-cred"), 1)
        assertTrue("the other credential's profile waits for the lock", queuedOne.isActive)
        ui.gateRelease.complete(Unit)
        withTimeout(10_000) { holder.await(); queuedOne.await() }
    }

    @Test fun outsideRoundTripBackToTheLastBoundDaemonIsRebindNotRefusalButStillWritesOnlyToTheSelectedDaemon(): Unit = runBlocking {
        // DOCUMENTED RESIDUAL (not closed): Chunks cannot tell a sibling Chunks bracket from an outside
        // actor's round trip that ends on the daemon Chunks last bound. Here last own bind = B, an outside
        // actor goes B -> C -> B, and A's write is accepted (rebind) instead of refused. It is still routed
        // only to A and verified by the post-call bound check; B and C see nothing.
        val ui = FakeSharedSlotWebUi(listOf(a, b, c))
        val cr = creds("roundtrip")
        val ca = DelugeClient(cr, a, ui); val cb = DelugeClient(cr, b, ui)
        ca.torrents(); cb.torrents() // Chunks' last own web.connect: B
        ui.slot = c; ui.slot = b // outside round trip, indistinguishable from a sibling bracket
        val hashMagnet = magnet(31)
        ca.add(ClientAddRequest(hashMagnet, null, false))
        assertEquals("write executed on the selected daemon only", listOf(a), ui.writes.map { it.first })
        assertEquals(1, ui.inventories.getValue(a).size)
        assertTrue(ui.inventories.getValue(b).isEmpty() && ui.inventories.getValue(c).isEmpty())
        assertEquals("verified readback through the post-call check", 1, ca.torrents().size)
        assertEquals(a, ui.slot)
    }

    @Test fun cancelledWaiterLeavesTheRouteUsableAndNeverRunsItsWrite(): Unit = runBlocking {
        val ui = FakeSharedSlotWebUi(listOf(a, b))
        val cr = creds("cancel-queued")
        val ca = DelugeClient(cr, a, ui); val cb = DelugeClient(cr, b, ui)
        ca.torrents()
        ui.gateNext("core.get_torrents_status")
        val holder = async(Dispatchers.IO) { ca.torrents() }
        ui.gateEntered.await()
        val writesBefore = ui.writes.size
        val queued = launch(Dispatchers.IO) { cb.add(ClientAddRequest(magnet(77), null, false)) }
        awaitQueued(ownerOf("cancel-queued"), 1) // really queued behind the holder
        queued.cancelAndJoin()
        awaitQueued(ownerOf("cancel-queued"), 0)
        ui.gateRelease.complete(Unit)
        holder.await()
        assertEquals("cancelled queued write must not dispatch", writesBefore, ui.writes.size)
        withTimeout(5_000) { cb.torrents(); ca.torrents() }
    }

    @Test fun cancelledHolderReleasesTheRouteAndCancellationPropagates(): Unit = runBlocking {
        val ui = FakeSharedSlotWebUi(listOf(a, b))
        val cr = creds("cancel-holder")
        val ca = DelugeClient(cr, a, ui); val cb = DelugeClient(cr, b, ui)
        ca.torrents()
        ui.gateNext("core.get_torrents_status")
        val holder = launch(Dispatchers.IO) { ca.torrents() }
        ui.gateEntered.await()
        val next = async(Dispatchers.IO) { cb.torrents() }
        awaitQueued(ownerOf("cancel-holder"), 1)
        holder.cancelAndJoin()
        assertTrue(holder.isCancelled)
        withTimeout(5_000) { next.await() }
        withTimeout(5_000) { ca.torrents() }
    }

    @Test fun cancellationStormLeavesNoStuckRoute(): Unit = runBlocking {
        val ui = FakeSharedSlotWebUi(listOf(a, b), latencyMs = 1)
        val cr = creds("storm")
        val clients = listOf(DelugeClient(cr, a, ui), DelugeClient(cr, b, ui))
        val jobs = (0 until 200).map { i ->
            launch(Dispatchers.IO) { runCatching { clients[i % 2].torrents() }.exceptionOrNull()?.let { if (it is CancellationException) throw it } }
        }
        kotlinx.coroutines.delay(15)
        jobs.filterIndexed { i, _ -> i % 3 != 0 }.forEach { it.cancel() }
        withTimeout(30_000) { jobs.forEach { it.join() } }
        withTimeout(5_000) { clients[0].torrents(); clients[1].torrents() }
    }

    @Test fun measureContentionForConcurrentRefreshes(): Unit = runBlocking {
        for (mode in listOf("fresh" to false, "persistent" to true)) {
            val ui = FakeSharedSlotWebUi(listOf(a, b), latencyMs = 3)
            val cr = creds("measure-${mode.first}")
            val started = System.nanoTime()
            val failures = hammer(ui, cr, mode.second)
            val wallMs = (System.nanoTime() - started) / 1_000_000
            println("CHUNKS_DELUGE_ROUTING_MEASURE: mode=${mode.first} ops=${rounds * 4} failures=${failures.size} " +
                "web.connect=${ui.count("web.connect")} get_host_status=${ui.count("web.get_host_status")} " +
                "core=${ui.coreCalls()} total_rpc=${ui.totalCalls()} wall_ms=$wallMs")
        }
    }
}
