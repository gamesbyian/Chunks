package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The routing owner itself: reentrancy, mutual exclusion, cancellation, affinity, endpoint scoping. */
class DelugeRoutingOwnerTest {
    @Test fun nestedBracketsOnTheSameOwnerDoNotDeadlock(): Unit = runBlocking {
        val owner = DelugeRoutingOwner()
        val depth = withTimeout(5_000) {
            owner.bracket("a") { owner.bracket("a") { owner.bracket("b") { withContext(Dispatchers.IO) { owner.bracket("a") { 4 } } } } }
        }
        assertEquals(4, depth)
        assertTrue(owner.isIdle)
        assertEquals("nested brackets are not new acquisitions", 1L, owner.acquisitions)
    }

    @Test fun reentrancySurvivesANestedBracketOnAnotherOwner(): Unit = runBlocking {
        val one = DelugeRoutingOwner(); val two = DelugeRoutingOwner()
        withTimeout(5_000) { one.bracket("a") { two.bracket("a") { one.bracket("a") { two.bracket("a") {} } } } }
        assertTrue(one.isIdle && two.isIdle)
    }

    @Test fun bracketsAreMutuallyExclusiveUnderLoad(): Unit = runBlocking {
        val owner = DelugeRoutingOwner()
        val inside = AtomicInteger(0); val maxInside = AtomicInteger(0); val done = AtomicInteger(0)
        withTimeout(30_000) {
            (0 until 400).map { i ->
                async(Dispatchers.Default) {
                    owner.bracket(if (i % 2 == 0) "a" else "b") {
                        maxInside.updateAndGet { maxOf(it, inside.incrementAndGet()) }
                        yield(); if (i % 7 == 0) delay(1)
                        inside.decrementAndGet(); done.incrementAndGet()
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, maxInside.get())
        assertEquals(400, done.get())
        assertTrue(owner.isIdle)
    }

    @Test fun aContextCopiedOutOfABracketCannotBypassTheLockLater(): Unit = runBlocking {
        val owner = DelugeRoutingOwner()
        var leaked: CoroutineContext? = null
        owner.bracket("a") { leaked = coroutineContext }
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) { owner.bracket("a") { entered.complete(Unit); gate.await() } }
        entered.await()
        val intruder = launch(Dispatchers.Default + leaked!!) { owner.bracket("a") {} }
        delay(100)
        assertTrue("stale context must queue behind the real holder", intruder.isActive)
        gate.complete(Unit); holder.join(); withTimeout(5_000) { intruder.join() }
    }

    @Test fun waiterCancelledWhileQueuedLeavesTheLockUsableAndNeverRuns(): Unit = runBlocking {
        val owner = DelugeRoutingOwner()
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val ran = AtomicInteger(0)
        val holder = launch(Dispatchers.Default) { owner.bracket("a") { entered.complete(Unit); gate.await() } }
        entered.await()
        val waiter = launch(Dispatchers.Default) { owner.bracket("b") { ran.incrementAndGet() } }
        val survivor = async(Dispatchers.Default) { owner.bracket("b") { ran.incrementAndGet(); "ok" } }
        delay(100)
        waiter.cancelAndJoin()
        assertTrue(waiter.isCancelled)
        gate.complete(Unit); holder.join()
        assertEquals("ok", withTimeout(5_000) { survivor.await() })
        assertEquals("only the surviving waiter ran", 1, ran.get())
        assertTrue(owner.isIdle)
    }

    @Test fun holderCancelledMidBracketPassesTheLockOn(): Unit = runBlocking {
        val owner = DelugeRoutingOwner()
        val entered = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) { owner.bracket("a") { entered.complete(Unit); delay(60_000) } }
        entered.await()
        val next = async(Dispatchers.Default) { owner.bracket("b") { "next" } }
        delay(50)
        holder.cancelAndJoin()
        assertEquals("next", withTimeout(5_000) { next.await() })
        assertTrue(owner.isIdle)
    }

    @Test fun cancellationRacingTheHandOffNeverLosesTheLock(): Unit = runBlocking {
        repeat(40) { round ->
            val owner = DelugeRoutingOwner()
            val jobs = (0 until 60).map { i ->
                launch(Dispatchers.Default) { owner.bracket("h${i % 3}") { if (i % 5 == 0) yield() } }
            }
            // Cancel a rolling subset while the hand-off is in flight.
            jobs.filterIndexed { i, _ -> (i + round) % 2 == 0 }.forEach { it.cancel() }
            withTimeout(20_000) { jobs.forEach { it.join() } }
            withTimeout(5_000) { owner.bracket("z") {} }
            assertTrue("round $round left the lock held or queued", owner.isIdle)
        }
    }

    @Test fun waitersForTheBoundDaemonOvertakeButTheHeadIsNeverStarved(): Unit = runBlocking {
        val owner = DelugeRoutingOwner()
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val holder = launch(Dispatchers.Default) { owner.bracket("a") { entered.complete(Unit); gate.await() } }
        entered.await()
        // Queue: b0 (head), then a1..a20. With affinity, a's run while b0 is overtaken, but only MAX_OVERTAKES times.
        val waiters = mutableListOf<kotlinx.coroutines.Job>()
        waiters += launch(Dispatchers.Default) { owner.bracket("b") { order += "b0" } }
        delay(20)
        (1..20).forEach { i ->
            waiters += launch(Dispatchers.Default) { owner.bracket("a") { order += "a$i" } }
            delay(5)
        }
        delay(50)
        gate.complete(Unit); holder.join(); withTimeout(10_000) { waiters.forEach { it.join() } }
        assertEquals(21, order.size)
        val b0 = order.indexOf("b0")
        assertEquals("head waits for exactly the bounded number of overtakes", DelugeRoutingOwner.MAX_OVERTAKES, b0)
        assertEquals(DelugeRoutingOwner.MAX_OVERTAKES.toLong(), owner.overtakes)
    }

    @Test fun mayRebindOnlyWhereChunksItselfLastLeftTheSlot(): Unit = runBlocking {
        val owner = DelugeRoutingOwner()
        assertFalse("nothing known: never rebind", owner.mayRebind("a") { true })
        owner.noteBound("a")
        assertFalse("last own bind is this host: slot moved by someone else", owner.mayRebind("a") { true })
        owner.noteBound("b")
        assertTrue("sibling Chunks bracket left it on b and it is still there", owner.mayRebind("a") { it == "b" })
        assertFalse("slot is no longer where Chunks left it", owner.mayRebind("a") { false })
    }

    @Test fun ownersAreScopedToEndpointAndCredentials(): Unit = runBlocking {
        DelugeRoutingOwner.clearForTest()
        fun owner(url: String, pass: String = "pw", user: String = "") =
            DelugeRoutingOwner.forEndpoint(URI(url), RemoteCredentials(url, user, pass))
        val base = owner("http://127.0.0.1:8112/json")
        assertSame(base, owner("HTTP://127.0.0.1:8112/json"))
        assertSame(owner("https://box.example/a/json"), owner("https://BOX.example:443/a/json"))
        assertNotSame("different password", base, owner("http://127.0.0.1:8112/json", pass = "other"))
        assertNotSame("different port", base, owner("http://127.0.0.1:8113/json"))
        assertNotSame("different path (another tenant behind a proxy)", owner("https://box.example/a/json"), owner("https://box.example/b/json"))
        assertNotSame("different host", base, owner("http://localhost:8112/json"))
        assertNotSame("different scheme and port", owner("https://box.example/a/json"), owner("http://box.example/a/json"))
    }

    @Test fun registryDoesNotGrowWithoutBound(): Unit = runBlocking {
        DelugeRoutingOwner.clearForTest()
        repeat(1_000) { DelugeRoutingOwner.forEndpoint(URI("http://127.0.0.1:1/p$it/json"), RemoteCredentials("x", "", "pw")) }
        assertTrue("registry bounded: ${DelugeRoutingOwner.registrySize()}", DelugeRoutingOwner.registrySize() <= 256)
        // Idle owners are evicted past the cap; a fresh lookup still returns a working owner.
        val owner = DelugeRoutingOwner.forEndpoint(URI("http://127.0.0.1:1/last/json"), RemoteCredentials("x", "", "pw"))
        assertEquals(1, withTimeout(5_000) { owner.bracket("a") { 1 } })
    }
}
