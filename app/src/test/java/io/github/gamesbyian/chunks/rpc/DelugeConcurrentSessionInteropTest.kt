package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.model.RemoteProtocolReadOnlyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * #2109: Chunks itself runs several Deluge Web UI sessions at once (the
 * Transfers inventory refresh, Details commands, policy ticks). An inventory
 * read used to call `web.connect` even when the shared slot was already bound
 * to its daemon, re-establishing it and breaking the other sessions' bracketed
 * reads and writes: concurrent reads failed with "not connected to the selected
 * daemon" and a Start came back Refused (reproduced on real daemons). With one
 * registered daemon and concurrent readers, every read and Start must succeed.
 * Env-gated like the other interop tests; fixture-only jobs.
 */
class DelugeConcurrentSessionInteropTest {
    @Test fun concurrentInventoryReadsNeverBreakAnotherSessionsStart(): Unit = runBlocking {
        val url = System.getenv("CHUNKS_INTEROP_DELUGE_URL").orEmpty()
        assumeTrue(url.isNotBlank())
        val credentials = RemoteCredentials(url, "", System.getenv("CHUNKS_INTEROP_DELUGE_PASS").orEmpty())
        val daemon = DelugeReadOnlyRpc(credentials, "unselected").registeredDaemonIds().single()
        val reader = RemoteProtocolReadOnlyClient()
        val configuration = RemoteProtocolReadOnlyClient.Configuration(RemoteBackendKind.DELUGE, credentials, daemon)
        fun ops() = ClientOperations(RemoteTorrentClients.create(RemoteBackendKind.DELUGE, credentials, daemon))
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        var overlapped = 0
        repeat(6) { round ->
            val name = "chunks-concurrent-$round-" + java.lang.Long.toString(System.nanoTime() and Long.MAX_VALUE, 36)
            val bytes = ByteArray(32 * 1024) { (it * 7 + round).toByte() }
            val fixture = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
                listOf(TorrentMetainfoBuilder.Entry(listOf(name), bytes.size.toLong()) { bytes.inputStream() }),
                trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024))
            try {
                val added = ops().add(ClientAddRequest(ClientAddSource.TorrentFile(fixture.torrent), null, startNow = false))
                assertTrue("fixture added: $added", added is ClientOperationOutcome.Verified)
                val startBegun = kotlinx.coroutines.CompletableDeferred<Unit>()
                val startDone = java.util.concurrent.atomic.AtomicBoolean(false)
                val duringStart = java.util.concurrent.atomic.AtomicInteger(0)
                coroutineScope {
                    // Readers keep reading from the moment the Start begins until it
                    // completes, so their brackets genuinely overlap its bracket.
                    val readers = (1..3).map {
                        async(Dispatchers.IO) {
                            startBegun.await()
                            do {
                                runCatching { reader.inspect(configuration) }.exceptionOrNull()
                                    ?.let { failures += "round $round reader: ${it.message}" }
                                if (!startDone.get()) duringStart.incrementAndGet()
                            } while (!startDone.get())
                        }
                    }
                    startBegun.complete(Unit)
                    val started = try { ops().setStarted(fixture.infoHashV1, true) } finally { startDone.set(true) }
                    if (started !is ClientOperationOutcome.Verified) failures += "round $round start=${started.javaClass.simpleName}"
                    readers.forEach { it.await() }
                    runCatching { reader.inspect(configuration) }.exceptionOrNull()
                        ?.let { failures += "round $round post-start read: ${it.message}" }
                }
                overlapped += duringStart.get()
            } finally {
                runCatching { ops().remove(fixture.infoHashV1, deleteData = false) }
            }
            assertTrue("fixture removed", ops().client.torrent(fixture.infoHashV1) == null)
        }
        assertTrue("readers overlapped the Start brackets", overlapped > 0)
        println("CHUNKS_DELUGE_CONCURRENT: rounds=6 concurrent_readers=3 overlapping_reads=$overlapped failures=${failures.size}")
        assertEquals("concurrent Chunks sessions must not disrupt each other: $failures", emptyList<String>(), failures)
    }
}
