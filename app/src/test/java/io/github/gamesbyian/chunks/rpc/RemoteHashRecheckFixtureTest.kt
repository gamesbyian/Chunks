package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * These are real loopback HTTP/XML-RPC requests, never a protected server.
 * A 200 acknowledgment alone cannot certify that piece hashes were checked.
 */
class RemoteHashRecheckFixtureTest {
    private val hash = "ab".repeat(20)
    private val other = "cd".repeat(20)
    private val name = "sample.bin"
    private val directory = "/srv/disposable"

    private enum class Scenario {
        OBSERVED, QUEUED, ALREADY_CHECKING,
        WRONG_HASH, WRONG_NAME, WRONG_DIRECTORY, INVALID_FLAG,
        LOST_ACK_AFTER_APPLY, WRONG_HASH_AFTER_APPLY
    }

    private inner class Gateway(private val scenario: Scenario) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var writes = 0
        private var hashReads = 0
        val endpoint get() = "http://127.0.0.1:${server.address.port}/RPC2"

        init {
            server.createContext("/RPC2") { exchange ->
                val raw = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(raw)?.groupValues?.get(1).orEmpty()
                methods += method
                val payload = when (method) {
                    "d.hash" -> {
                        hashReads++
                        val changed = scenario == Scenario.WRONG_HASH ||
                            (scenario == Scenario.WRONG_HASH_AFTER_APPLY && writes > 0)
                        "<string>${if (changed) other else hash}</string>"
                    }
                    "d.name" -> "<string>${if (scenario == Scenario.WRONG_NAME) "other.bin" else name}</string>"
                    "d.directory" -> "<string>${if (scenario == Scenario.WRONG_DIRECTORY) "/srv/other" else directory}</string>"
                    "d.is_hash_checking" -> "<i8>${when {
                        scenario == Scenario.INVALID_FLAG -> 2
                        scenario == Scenario.ALREADY_CHECKING -> 1
                        scenario == Scenario.OBSERVED && writes > 0 -> 1
                        else -> 0
                    }}</i8>"
                    "d.check_hash" -> {
                        writes++
                        if (scenario == Scenario.LOST_ACK_AFTER_APPLY) {
                            exchange.sendResponseHeaders(503, -1)
                            exchange.close()
                            return@createContext
                        }
                        "<i8>0</i8>"
                    }
                    else -> error("Unexpected RPC method: $method")
                }
                val response = ("<methodResponse><params><param><value>$payload" +
                    "</value></param></params></methodResponse>").toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private fun remote(g: Gateway) = RTorrentRemote(RemoteCredentials(g.endpoint, "", ""))

    @Test fun recheckRequiresFreshExactHashNameDirectoryAndBooleanBeforeAnyWrite() = runBlocking {
        for (scenario in listOf(Scenario.WRONG_HASH, Scenario.WRONG_NAME,
                Scenario.WRONG_DIRECTORY, Scenario.INVALID_FLAG)) {
            Gateway(scenario).use { gateway ->
                try {
                    remote(gateway).requestHashRecheck(hash, name, directory)
                    fail("Preflight must refuse a stale or malformed Recheck review: $scenario")
                } catch (expected: Exception) {
                    assertTrue(expected is IllegalStateException ||
                        expected is IllegalArgumentException)
                }
                assertEquals(0, gateway.writes)
                assertFalse(gateway.methods.contains("d.check_hash"))
            }
        }
    }

    @Test fun invalidInputCannotReachEvenAReadOnlyServer() = runBlocking {
        Gateway(Scenario.OBSERVED).use { gateway ->
            for ((h, n, dir) in listOf(
                    Triple("../hash", name, directory),
                    Triple(hash, "", directory),
                    Triple(hash, name, "relative/path"))) {
                try {
                    remote(gateway).requestHashRecheck(h, n, dir)
                    fail("Malformed Recheck target accepted")
                } catch (_: IllegalArgumentException) { }
            }
            assertTrue(gateway.methods.isEmpty())
        }
    }

    @Test fun existingHashCheckDoesNotScheduleAnother() = runBlocking {
        Gateway(Scenario.ALREADY_CHECKING).use { gateway ->
            assertEquals(RemoteHashRecheckOutcome.AlreadyChecking(hash),
                remote(gateway).requestHashRecheck(hash, name, directory))
            assertEquals(0, gateway.writes)
        }
    }

    @Test fun acceptedRequestNeverClaimsCompletedPiecesWithoutHashingReadback() = runBlocking {
        for ((scenario, expected) in listOf(
                Scenario.OBSERVED to RemoteHashRecheckOutcome.HashingObserved(hash),
                Scenario.QUEUED to RemoteHashRecheckOutcome.SubmittedUnobserved(hash))) {
            Gateway(scenario).use { gateway ->
                assertEquals(expected, remote(gateway).requestHashRecheck(hash, name, directory))
                assertEquals(1, gateway.writes)
                assertEquals(1, gateway.methods.count { it == "d.check_hash" })
                assertEquals(4, gateway.methods.count { it == "d.hash" })
            }
        }
    }

    @Test fun readOnlyReconciliationNeverClaimsCompletionOrSchedulesAWrite() = runBlocking {
        val expected = listOf(
            Scenario.QUEUED to RemoteHashRecheckObservation.NotChecking,
            Scenario.ALREADY_CHECKING to RemoteHashRecheckObservation.Checking,
            Scenario.WRONG_HASH to RemoteHashRecheckObservation.Changed,
            Scenario.WRONG_NAME to RemoteHashRecheckObservation.Changed,
            Scenario.WRONG_DIRECTORY to RemoteHashRecheckObservation.Changed,
            Scenario.INVALID_FLAG to RemoteHashRecheckObservation.Unavailable
        )
        for ((scenario, observation) in expected) {
            Gateway(scenario).use { gateway ->
                assertEquals(observation,
                    remote(gateway).observeHashRecheck(hash, name, directory))
                assertEquals("No read-only reconciliation may start Recheck", 0, gateway.writes)
                assertFalse(gateway.methods.contains("d.check_hash"))
            }
        }
    }

    @Test fun appliedButLostAcknowledgementCannotBeProvedByIdleObservation() = runBlocking {
        Gateway(Scenario.LOST_ACK_AFTER_APPLY).use { gateway ->
            val client = remote(gateway)
            assertEquals(RemoteHashRecheckOutcome.Uncertain(hash),
                client.requestHashRecheck(hash, name, directory))
            assertEquals(RemoteHashRecheckObservation.NotChecking,
                client.observeHashRecheck(hash, name, directory))
            assertEquals("Even an idle readback cannot authorize blind retry", 1, gateway.writes)
        }
    }

    @Test fun lostAckOrContradictoryPostWriteSessionNeverRetriesRecheck() = runBlocking {
        for (scenario in listOf(Scenario.LOST_ACK_AFTER_APPLY,
                Scenario.WRONG_HASH_AFTER_APPLY)) {
            Gateway(scenario).use { gateway ->
                assertEquals(RemoteHashRecheckOutcome.Uncertain(hash),
                    remote(gateway).requestHashRecheck(hash, name, directory))
                assertEquals("May have scheduled, never blindly replay", 1, gateway.writes)
                assertEquals(1, gateway.methods.count { it == "d.check_hash" })
            }
        }
    }
}
