package io.github.gamesbyian.chunks.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #2165: the owner names its current holder with value-free Chunks frames only. */
class DelugeRoutingOwnerDiagnosticsTest {
    private suspend fun holdTheBracket(owner: DelugeRoutingOwner, entered: CompletableDeferred<Unit>,
                                       release: CompletableDeferred<Unit>) =
        owner.bracket("daemon-a") { owner.step("web.get_hosts", "replied"); entered.complete(Unit); release.await() }

    @Test fun diagnosticsNameTheHolderWhileHeldAndNothingWhenFree() = runBlocking {
        val owner = DelugeRoutingOwner()
        assertEquals("locked=false queued=0 acquisitions=0 lastStep=none holder=none", owner.diagnostics())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch { holdTheBracket(owner, entered, release) }
        entered.await()
        val held = owner.diagnostics()
        assertTrue(held, held.startsWith("locked=true queued=0 acquisitions=1 lastStep="))
        assertTrue("the holder is named by its own Chunks frame: $held", "holdTheBracket" in held)
        assertFalse("no endpoint, host or daemon value leaks: $held", "daemon-a" in held)
        assertTrue("fits the CHUNKS_SAFE_DIAG charset: $held",
            Regex("[A-Za-z0-9 _=.,:/\\[\\]-]+").matches(held))
        release.complete(Unit)
        job.join()
        yield()
        assertEquals("locked=false queued=0 acquisitions=1 lastStep=web.get_hosts:replied holder=none", owner.diagnostics())
    }
}
