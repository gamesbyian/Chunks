package io.github.gamesbyian.chunks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Offline ordering proof, no Keystore or network required. */
class RemoteCredentialActionQueueTest {
    @Test fun earlierOptOutMustCommitBeforeRapidOptInAndOnlyLatestMayChangeUi() = runBlocking {
        val queue = RemoteCredentialActionQueue()
        val releaseOldAction = CompletableDeferred<Unit>()
        val commits = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val first = queue.submit(this) { revision ->
            releaseOldAction.await()
            commits += "opt-out and erase"
            if (queue.isLatest(revision)) notices += "off"
        }
        val second = queue.submit(this) { revision ->
            commits += "opt-in"
            if (queue.isLatest(revision)) notices += "on"
        }
        // Allow the later submission to be scheduled; it cannot pass the
        // unfinished earlier durable opt-out, even on another dispatcher.
        kotlinx.coroutines.yield()
        assertTrue(commits.isEmpty())
        releaseOldAction.complete(Unit)
        joinAll(first, second)
        assertEquals(listOf("opt-out and erase", "opt-in"), commits)
        assertEquals(listOf("on"), notices)
    }

    @Test fun finalForgetCannotBeUndoneByPriorEnableThatFinishesLate() = runBlocking {
        val queue = RemoteCredentialActionQueue()
        val gate = CompletableDeferred<Unit>()
        val commits = mutableListOf<String>()
        val first = queue.submit(this) { _ ->
            gate.await()
            commits += "enable"
        }
        val second = queue.submit(this) { revision ->
            commits += "forget all"
            assertTrue(queue.isLatest(revision))
        }
        gate.complete(Unit)
        joinAll(first, second)
        assertEquals(listOf("enable", "forget all"), commits)
    }
}
