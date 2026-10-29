package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner
import io.github.gamesbyian.chunks.engine.FinishInOrderRecord
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner.Step
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** A stateful rTorrent fake: three 4-chunk episodes, the middle one already verified. */
class RemoteFinishInOrderPortTest {
    private val hash = "dd".repeat(20)
    private val paths = listOf("Show/Show S01E01.mkv", "Show/Show S01E02.mkv", "Show/Show S01E03.mkv")
    private val priorities = intArrayOf(1, 1, 1)
    /** Verified chunks per episode (each file spans 4 chunks). */
    private val completed = longArrayOf(2, 4, 0)
    private fun bitfield(): String {
        val bits = (0 until 12).map { chunk -> chunk % 4 < completed[chunk / 4] }
        return bits.chunked(4).joinToString("") { nib ->
            Integer.toHexString(nib.foldIndexed(0) { i, acc, b -> if (b) acc or (8 shr i) else acc })
        }
    }
    private val writes = mutableListOf<String>()
    @Volatile private var failSetOnIndex = -1

    private fun num(n: Long) = "<value><i8>$n</i8></value>"
    private fun str(s: String) = "<value><string>$s</string></value>"

    private fun answer(method: String, body: String): String = when (method) {
        "d.hash" -> str(hash)
        "d.size_chunks" -> num(12)
        "d.bitfield" -> str(bitfield())
        "f.multicall" -> "<value><array><data>" + paths.indices.joinToString("") { i ->
            "<value><array><data>" + str(paths[i]) + num(4096) + num(priorities[i].toLong()) +
                (if ("f.range_first" in body) num(i * 4L) + num(i * 4L + 4) else "") +
                "</data></array></value>"
        } + "</data></array></value>"
        "f.priority.set" -> {
            val target = Regex(":f(\\d+)</string>").find(body)!!.groupValues[1].toInt()
            val value = Regex("<i[48]>(\\d+)</i[48]>|<int>(\\d+)</int>").findAll(body).last()
                .groupValues.drop(1).first { it.isNotEmpty() }.toInt()
            if (target == failSetOnIndex) throw java.io.IOException("simulated lost reply")
            writes += "f$target=$value"; priorities[target] = value; num(0)
        }
        "d.update_priorities" -> num(0)
        else -> error("Unexpected $method")
    }

    private fun withServer(block: suspend (RTorrentRemote) -> Unit) = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(request)?.groupValues?.get(1).orEmpty()
            val payload = try { answer(method, request) } catch (_: java.io.IOException) {
                exchange.sendResponseHeaders(502, -1); exchange.close(); return@createContext
            }
            val bytes = ("<methodResponse><params><param>$payload</param></params></methodResponse>")
                .toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block(RTorrentRemote(RemoteCredentials("http://127.0.0.1:${server.address.port}/RPC2", "", ""))) }
        finally { server.stop(0) }
    }

    private suspend fun record(remote: RTorrentRemote): FinishInOrderRecord {
        val files = RemoteFinishInOrderPort(remote) { _, _ -> true }.observe(hash)!!.map { it.file }
        return FinishInOrderRecord(FinishInOrderPlanner.policyFor(FinishInOrderPlanner.Mode.BALANCED,
            files, requireNotNull(FinishInOrderPlanner.suggest(files)).order))
    }

    @Test fun twoBandScheduleRaisesOnlyTheEarliestUnverifiedEpisode() = withServer { remote ->
        var saved: FinishInOrderRecord? = null
        val runner = FinishInOrderRunner(RemoteFinishInOrderPort(remote) { _, r -> saved = r; true })
        val step = runner.step(hash, record(remote)) as Step.Applied
        assertEquals(0, step.plan.active)
        assertEquals(listOf("f0=2"), writes)                 // E01 High; E03 stays Normal, never "Low"
        assertEquals(intArrayOf(2, 1, 1).toList(), priorities.toList())
        // E01 finishes verification: High moves to E03, skipping verified E02.
        completed[0] = 4
        val next = runner.step(hash, requireNotNull(saved)) as Step.Applied
        assertEquals(2, next.plan.active)
        assertEquals(listOf("f0=2", "f2=2"), writes)
        assertTrue(saved!!.pending.isEmpty())
    }

    @Test fun lostWriteReplySuspendsAndIsNotReplayed() = withServer { remote ->
        failSetOnIndex = 0
        var saved: FinishInOrderRecord? = null
        val runner = FinishInOrderRunner(RemoteFinishInOrderPort(remote) { _, r -> saved = r; true })
        val step = runner.step(hash, record(remote))
        assertTrue(step is Step.Suspended)
        assertFalse(saved!!.pending.isEmpty())
        failSetOnIndex = -1
        assertTrue(runner.step(hash, saved!!) is Step.Suspended)
        assertTrue("no automatic replay", writes.isEmpty())
    }

    @Test fun priorityMappingNeverRequestsAnUnsupportedValue() {
        assertEquals(2, RemoteFinishInOrderPort.toRemote(FinishInOrderPlanner.HIGH))
        assertEquals(1, RemoteFinishInOrderPort.toRemote(FinishInOrderPlanner.NORMAL))
        assertEquals(1, RemoteFinishInOrderPort.toRemote(FinishInOrderPlanner.LOW))
        assertEquals(0, RemoteFinishInOrderPort.toRemote(0))
        assertEquals(FinishInOrderPlanner.HIGH, RemoteFinishInOrderPort.toPlanner(2))
    }
}
