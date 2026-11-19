package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Local (libtorrent4j) read-only observation of a retained Universal Add
 * ticket, in the same [UniversalAddObserver] shape as rTorrent and the
 * protocol clients. It replaces the Local device screen's own post-Add
 * reconciliation, so an Add keeps being verified when the person leaves Add
 * or the Activity is recreated.
 *
 * It never writes. A complete native list is sampled once; the result is the
 * session's existing exact-hash/reviewed-Place/payload-Start/native-token
 * evidence. A list read started while ANY Local command was pending (or that
 * straddled one) is not accepted, exactly like the screen's own fence.
 */
internal class LocalNativeAddObserver(
    private val session: LocalAddAttemptSession,
    private val read: suspend () -> List<LocalTransferStatus>? = LocalAddReadbackReader.shared::read,
    private val fence: LocalScreenObservationRevision = LocalMutationFence.revision,
    private val clock: () -> Long = System::currentTimeMillis
) : UniversalAddObserver {
    override suspend fun observe(
        ticket: UniversalAddCoordinator.Ticket
    ): UniversalAddCoordinator.ExactSessionObservation? {
        val revision = fence.snapshot()
        if (!fence.isCurrent(revision)) return null
        // Stamp before the read starts: a late native completion can never
        // acquire a newer sampling time than its actual start.
        val observedAt = clock()
        val listed = read() ?: return null
        if (!fence.isCurrent(revision)) return null
        return session.observationFor(ticket, listed, observedAt)
    }
}

/**
 * JNI-safe, single-flight native list read for Add observation. Coroutine
 * cancellation cannot interrupt a native call, so the read runs on its own
 * daemon thread and is awaited with a timeout. A read that never returns
 * occupies its one slot; later observations return "no proof" (null) until
 * it finishes, never an empty inventory and never unbounded threads.
 */
internal class LocalAddReadbackReader(
    private val list: () -> List<LocalTransferStatus>?,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "chunks-local-add-readback").apply { isDaemon = true }
    },
    private val timeoutMillis: Long = 10_000L
) {
    private val inFlight = AtomicBoolean(false)

    suspend fun read(): List<LocalTransferStatus>? {
        if (!inFlight.compareAndSet(false, true)) return null
        val result = CompletableDeferred<List<LocalTransferStatus>?>()
        try {
            executor.execute {
                try {
                    result.complete(list())
                } catch (_: Exception) {
                    // A failed list is unknown, not an authoritative empty queue.
                    result.complete(null)
                } finally {
                    inFlight.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            inFlight.set(false)
            return null
        }
        return withTimeoutOrNull(timeoutMillis) { result.await() }
    }

    companion object {
        /** The running engine only; observation never starts libtorrent or networking. */
        val shared = LocalAddReadbackReader({ LocalEngineHolder.current()?.list() })
    }
}
