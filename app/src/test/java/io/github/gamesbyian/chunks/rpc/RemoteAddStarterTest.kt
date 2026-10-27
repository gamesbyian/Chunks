package io.github.gamesbyian.chunks.rpc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteAddStarterTest {
    private val hash = "ab".repeat(20)
    private val dest = "/downloads"

    @Test fun verifiedStoppedAddStartsOnlyItsNewExactHash() = runBlocking {
        val started = mutableListOf<String>()
        val result = RemoteAddStarter.run(true,
            add = { RemoteAddOutcome.VerifiedStopped(hash, dest) },
            start = { requested -> started += requested; LifecycleOutcome.Verified(true) })
        assertEquals(RemoteAddAndStart.Started(hash), result)
        assertEquals(listOf(hash), started)
    }

    @Test fun neverStartsExistingPendingOrUncertainAdds() = runBlocking {
        val adds = listOf(
            RemoteAddOutcome.AlreadyPresent(hash),
            RemoteAddOutcome.PendingVerification(hash),
            RemoteAddOutcome.Uncertain(hash)
        )
        for (add in adds) {
            var writes = 0
            val result = RemoteAddStarter.run(true, { add }, { writes++; LifecycleOutcome.Verified(true) })
            assertEquals(RemoteAddAndStart.AddedStopped(add), result)
            assertEquals(0, writes)
        }
    }

    @Test fun userCanChooseStoppedOnly() = runBlocking {
        var starts = 0
        val verified = RemoteAddOutcome.VerifiedStopped(hash, dest)
        assertEquals(RemoteAddAndStart.AddedStopped(verified),
            RemoteAddStarter.run(false, { verified }, { starts++; LifecycleOutcome.Verified(true) }))
        assertEquals(0, starts)
    }

    @Test fun failedOrUncertainStartNeverRetries() = runBlocking {
        var starts = 0
        val result = RemoteAddStarter.run(true,
            add = { RemoteAddOutcome.VerifiedStopped(hash, dest) },
            start = { starts++; LifecycleOutcome.Uncertain(true) })
        assertTrue(result is RemoteAddAndStart.StartNotVerified)
        assertEquals(1, starts)
        starts = 0
        val lostReply = RemoteAddStarter.run(true,
            add = { RemoteAddOutcome.VerifiedStopped(hash, dest) },
            start = { starts++; throw java.io.IOException("lost acknowledgement") })
        assertEquals(RemoteAddAndStart.StartNotVerified(hash, null), lostReply)
        assertEquals(1, starts)
    }

    @Test fun startCanReturnPendingOrWrongRunningState() = runBlocking {
        val result = RemoteAddStarter.run(true,
            add = { RemoteAddOutcome.VerifiedStopped(hash, dest) },
            start = { LifecycleOutcome.Verified(false) })
        assertTrue(result is RemoteAddAndStart.StartNotVerified)
    }
}
