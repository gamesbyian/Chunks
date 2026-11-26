package io.github.gamesbyian.chunks

import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import io.github.gamesbyian.chunks.privacy.RemoteTransferPrivacyScope
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.seeding.RemoteSeeding
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The extracted rTorrent policy loop without Compose (#2134, invariant control
 * `policy-loop-ignores-owner-currency`): a tick acts only while its owner is current, and a
 * credential change revokes it. Loopback gateway only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemotePolicyLoopTest {
    private val requests = CopyOnWriteArrayList<String>()

    /** Milliseconds every reply is held before it is sent (a slow server). */
    @Volatile private var stallMillis = 0L

    private fun withRemote(block: (RTorrentRemote) -> Unit) {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        http.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            requests += Regex("<methodName>([^<]+)</methodName>").find(xml)?.groupValues?.get(1).orEmpty()
            if (stallMillis > 0) Thread.sleep(stallMillis)
            val reply = ("<methodResponse><params><param><value><array><data></data></array></value></param>" +
                "</params></methodResponse>").toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        http.start()
        try {
            block(RTorrentRemote(RemoteCredentials("http://127.0.0.1:${http.address.port}/RPC2", "", "")))
        } finally { http.stop(0) }
    }

    private class Host(
        private val remote: RTorrentRemote,
        /** Called on every ownership check; may change credentials as a side effect. */
        private val onCheck: (Int) -> Unit
    ) : RemotePolicyHost {
        var checks = 0
        override val currentServer get() = remote
        override val currentAliasScope get() = remote.addAccountScope()
        override val isLoading = false
        override var isStale = false
        override val currentTransfers: List<RemoteTransfer> = emptyList()
        override val connectInFlight = false
        override val mutationGate = RemoteMutationReadbackGate()
        override fun mayRun(): Boolean { onCheck(++checks); return true }
        override fun requestReadback() {}
        override fun refresh() {}
    }

    private fun runLoopFor(host: Host, remote: RTorrentRemote, millis: Long) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(RemoteSeeding.EnforcementSwitch(context).set(requireNotNull(RemoteSeeding.environmentOf(remote)), true))
        val scope = requireNotNull(RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(remote.addAccountScope()))
        runBlocking {
            withTimeoutOrNull(millis) {
                runRemotePolicyLoop(context, host, remote, remote.addAccountScope(), scope, tickMillis = 40)
            }
        }
    }

    @Test fun anEnabledRuleTalksToTheServerWhileTheOwnerStaysCurrent() = withRemote { remote ->
        runLoopFor(Host(remote) { }, remote, 1_500)
        assertTrue("positive control: ticks read the inventory: $requests", requests.isNotEmpty())
    }

    @Test fun aCredentialChangeBetweenTheChecksRevokesEveryTick() = withRemote { remote ->
        // The 2nd check of each tick is the owner-currency test; a Connect/Forget/lock-style
        // generation bump lands right before it, so no tick may ever act.
        val host = Host(remote) { n -> if (n % 2 == 0) RemoteCredentialLock.invalidate() }
        runLoopFor(host, remote, 1_500)
        assertTrue("the loop did run its ownership checks: ${host.checks}", host.checks >= 4)
        assertEquals("a revoked owner sends no RPC", emptyList<String>(), requests.toList())
    }

    /**
     * A2611-06 measurement: cancelling the loop while a policy read is in flight. The adapter call
     * is blocking inside `withContext(Dispatchers.IO)`, so the loop returns only once that read
     * ends (bounded in production by the RPC read timeout), and it sends nothing after the
     * cancellation. Recorded, not changed: interrupting an in-flight write would turn a known
     * outcome into an uncertain one.
     */
    @Test fun cancellingDuringAnInFlightReadWaitsForThatReadAndSendsNothingAfterwards() = withRemote { remote ->
        stallMillis = 1_500
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(RemoteSeeding.EnforcementSwitch(context).set(requireNotNull(RemoteSeeding.environmentOf(remote)), true))
        val scope = requireNotNull(RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(remote.addAccountScope()))
        val host = Host(remote) { }
        var sentAtCancel = -1
        val elapsed = runBlocking {
            val loop = launch(kotlinx.coroutines.Dispatchers.Default) {
                runRemotePolicyLoop(context, host, remote, remote.addAccountScope(), scope, tickMillis = 40)
            }
            withTimeout(5_000) { while (requests.isEmpty()) delay(5) }   // a read is now in flight
            sentAtCancel = requests.size
            val started = System.nanoTime()
            loop.cancelAndJoin()
            (System.nanoTime() - started) / 1_000_000
        }
        println("CHUNKS_COST policy-loop cancel-latency-ms=$elapsed stall-ms=$stallMillis")
        assertTrue("the blocking read is waited for, not abandoned: $elapsed ms", elapsed >= 1_000)
        assertTrue("bounded by that one read: $elapsed ms", elapsed < 4_000)
        assertEquals("no RPC after cancellation", sentAtCancel, requests.size)
    }
}
