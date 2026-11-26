package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * #2072 on REAL daemons: two deluged daemons registered in ONE deluge-web (CHUNKS_INTEROP_DELUGE2_*),
 * so the Web UI's single slot is shared. Chunks' own concurrent profiles on the two daemons must not
 * disturb each other, identical infohashes with distinct payload folders must stay on their own daemon,
 * cancellation must leave the route usable, and an outside actor's slot switch must still be refused.
 * Fixture-only jobs, removed without payload deletion; env-gated like the other interop tests.
 */
class DelugeRoutingInteropTest {
    private fun env(name: String) = System.getenv("CHUNKS_INTEROP_DELUGE2_$name").orEmpty()

    private class CountingWire : DelugeReadOnlyRpc.Wire {
        val methods = ConcurrentHashMap<String, AtomicInteger>()
        fun count(m: String) = methods[m]?.get() ?: 0
        fun total() = methods.values.sumOf { it.get() }
        override suspend fun exchange(request: DelugeReadOnlyRpc.Request): DelugeReadOnlyRpc.Response {
            val m = JSONObject(request.body.toString(Charsets.UTF_8)).optString("method")
            methods.computeIfAbsent(m) { AtomicInteger() }.incrementAndGet()
            return DelugeReadOnlyRpc.HttpWire.exchange(request)
        }
    }

    private suspend fun attempt(block: suspend () -> Unit): String? = try { block(); null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message}" }

    private fun fixture(tag: String): TorrentMetainfoBuilder.Result {
        val bytes = ByteArray(32 * 1024) { (it * 13 + tag.hashCode()).toByte() }
        val name = "chunks-route-$tag"
        return TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
            listOf(TorrentMetainfoBuilder.Entry(listOf(name), bytes.size.toLong()) { bytes.inputStream() }),
            trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024))
    }

    @Test fun twoDaemonsBehindOneWebUiStayIndependentUnderConcurrentChunksSessions(): Unit = runBlocking {
        assumeTrue(env("URL").isNotBlank() && env("HOST_A").isNotBlank() && env("HOST_B").isNotBlank())
        val base = System.getenv("CHUNKS_INTEROP_DELUGE_PAYLOAD_DIR").orEmpty()
        assumeTrue(base.isNotBlank())
        val credentials = RemoteCredentials(env("URL"), "", env("PASS"))
        val hostA = env("HOST_A"); val hostB = env("HOST_B")
        val tag = java.lang.Long.toString(System.nanoTime() and Long.MAX_VALUE, 36)
        val dirA = File(base, "route-a-$tag").also { it.mkdirs() }.absolutePath
        val dirB = File(base, "route-b-$tag").also { it.mkdirs() }.absolutePath
        val fx = fixture(tag)
        val hash = fx.infoHashV1
        val wire = CountingWire()
        fun clientA() = DelugeClient(credentials, hostA, wire)
        fun clientB() = DelugeClient(credentials, hostB, wire)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            // 1. The SAME infohash on both daemons with DISTINCT payload folders, added concurrently by two profiles.
            val adds = coroutineScope {
                listOf(
                    async(Dispatchers.IO) { ClientOperations(clientA()).add(ClientAddRequest(ClientAddSource.TorrentFile(fx.torrent), dirA, startNow = false)) },
                    async(Dispatchers.IO) { ClientOperations(clientB()).add(ClientAddRequest(ClientAddSource.TorrentFile(fx.torrent), dirB, startNow = false)) },
                ).awaitAll()
            }
            adds.forEach { assertTrue("fixture added on its own daemon: $it", it is ClientOperationOutcome.Verified) }

            // 2. Concurrent refreshes, per-job reads, file reads, and Details writes on both daemons.
            wire.methods.clear()
            val started = System.nanoTime()
            val rounds = 10
            coroutineScope {
                (0 until rounds).flatMap { r ->
                    listOf(
                        async(Dispatchers.IO) { attempt { if (clientA().torrents().none { it.hash == hash }) failures += "A inventory lost $hash" }?.let { failures += "A refresh $r $it" } },
                        async(Dispatchers.IO) { attempt { if (clientB().torrents().none { it.hash == hash }) failures += "B inventory lost $hash" }?.let { failures += "B refresh $r $it" } },
                        async(Dispatchers.IO) { attempt { val t = clientA().torrent(hash); if (t?.downloadDirectory != dirA) failures += "A job dir ${t?.downloadDirectory}" }?.let { failures += "A job $r $it" } },
                        async(Dispatchers.IO) { attempt { val t = clientB().torrent(hash); if (t?.downloadDirectory != dirB) failures += "B job dir ${t?.downloadDirectory}" }?.let { failures += "B job $r $it" } },
                        async(Dispatchers.IO) { attempt { DelugeReadOnlyRpc(credentials, hostA, wire).inspect() }?.let { failures += "A inspect $r $it" } },
                        async(Dispatchers.IO) { attempt { DelugeReadOnlyRpc(credentials, hostB, wire).inspect() }?.let { failures += "B inspect $r $it" } },
                        async(Dispatchers.IO) { attempt { clientA().files(hash) }?.let { failures += "A files $r $it" } },
                        async(Dispatchers.IO) { attempt { clientB().files(hash) }?.let { failures += "B files $r $it" } },
                    )
                }.awaitAll()
            }
            val wallMs = (System.nanoTime() - started) / 1_000_000
            println("CHUNKS_DELUGE_ROUTING_INTEROP: refresh_ops=${rounds * 8} failures=${failures.size} web.connect=${wire.count("web.connect")} " +
                "get_host_status=${wire.count("web.get_host_status")} total_rpc=${wire.total()} wall_ms=$wallMs")
            assertEquals("no spurious refusals from Chunks' own interference: ${failures.take(5)}", emptyList<String>(), failures)

            // 3. Independent writes: Start A only; B must still be stopped, then restore.
            val startA = ClientOperations(clientA()).setStarted(hash, true)
            assertTrue("A start verified: $startA", startA is ClientOperationOutcome.Verified)
            assertNotEquals("A is running", ClientRunState.STOPPED, clientA().torrent(hash)!!.runState)
            assertEquals("B untouched by A's start", ClientRunState.STOPPED, clientB().torrent(hash)!!.runState)
            val stopA = ClientOperations(clientA()).setStarted(hash, false)
            assertTrue("A stop verified: $stopA", stopA is ClientOperationOutcome.Verified)
            assertEquals(dirA, clientA().torrent(hash)!!.downloadDirectory)
            assertEquals(dirB, clientB().torrent(hash)!!.downloadDirectory)

            // 4. Cancellation: cancel half of a mixed queue mid-flight; the route stays usable and nothing is wedged.
            val pa = clientA(); val pb = clientB()
            val jobs = (0 until 40).map { i ->
                launch(Dispatchers.IO) { runCatching { if (i % 2 == 0) pa.torrents() else pb.torrents() }.exceptionOrNull()?.let { if (it is CancellationException) throw it } }
            }
            delay(20)
            jobs.filterIndexed { i, _ -> i % 3 != 0 }.forEach { it.cancel() }
            withTimeout(60_000) { jobs.forEach { it.join() } }
            withTimeout(30_000) { assertTrue(pa.torrents().any { it.hash == hash }); assertTrue(pb.torrents().any { it.hash == hash }) }
            println("CHUNKS_DELUGE_ROUTING_INTEROP: cancellation=route_usable")

            // 5. An OUTSIDE actor takes the slot (its own web session): Chunks must still refuse, and write nothing.
            val probe = ByteArray(20).also(java.security.SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            val external = DelugeClient(credentials, hostA, wire)
            external.torrents() // last Chunks bind = A
            val outsider = DelugeReadOnlyRpc.HttpWire
            val login = outsider.exchange(DelugeReadOnlyRpc.Request(java.net.URI(credentials.endpoint),
                """{"method":"auth.login","params":["${credentials.password}"],"id":1}""".toByteArray(), null))
            val cookie = login.header("Set-Cookie").first().substringBefore(';')
            val switched = outsider.exchange(DelugeReadOnlyRpc.Request(java.net.URI(credentials.endpoint),
                """{"method":"web.connect","params":["$hostB"],"id":2}""".toByteArray(), cookie))
            assertTrue("outsider switched the slot to B", JSONObject(switched.body).opt("result") is JSONArray)
            assertTrue("A read refused after an outside switch", attempt { external.torrents() } != null)
            val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$probe&dn=route-probe&tr=http%3A%2F%2F127.0.0.1%3A1%2Fannounce")
            assertTrue("A write refused after an outside switch", attempt { external.add(ClientAddRequest(magnet, dirA, false)) } != null)
            assertFalse("probe never reached B", clientB().torrents().any { it.hash == probe })
            assertFalse("probe never reached A", clientA().torrents().any { it.hash == probe })
            println("CHUNKS_DELUGE_ROUTING_INTEROP: external_switch=refused no_write=true")
        } finally {
            runCatching { ClientOperations(clientA()).remove(hash, deleteData = false) }
            runCatching { ClientOperations(clientB()).remove(hash, deleteData = false) }
        }
        assertTrue("fixture removed from A", clientA().torrent(hash) == null)
        assertTrue("fixture removed from B", clientB().torrent(hash) == null)
        File(dirA).delete(); File(dirB).delete()
    }
}
