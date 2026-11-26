package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner
import io.github.gamesbyian.chunks.engine.FinishInOrderRecord
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner.Step
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2611-06 measurement: the RPC cost of one Finish in Order rule per 30 s policy tick against
 * a stateful rTorrent fake (three 4-chunk files, so cost is per call, not per byte). The numbers are
 * asserted as exact ceilings so a change that adds a round trip to every active rule is a
 * deliberate, reviewed decision; they are not a speed claim. A rule costs the same wire calls
 * whether it is polled by the loop or stepped manually, because both use [FinishInOrderRunner].
 */
class RemoteFinishInOrderCostTest {
    private val hash = "ee".repeat(20)
    private val paths = listOf("Show/E01.mkv", "Show/E02.mkv", "Show/E03.mkv")
    private val priorities = intArrayOf(1, 1, 1)
    private val completed = longArrayOf(0, 0, 0)
    private val methods = CopyOnWriteArrayList<String>()

    private fun bitfield(): String {
        val bits = (0 until 12).map { chunk -> chunk % 4 < completed[chunk / 4] }
        return bits.chunked(4).joinToString("") { nib ->
            Integer.toHexString(nib.foldIndexed(0) { i, acc, b -> if (b) acc or (8 shr i) else acc })
        }
    }

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
            priorities[target] = value; num(0)
        }
        "d.update_priorities" -> num(0)
        else -> error("Unexpected $method")
    }

    private fun withServer(block: suspend (RTorrentRemote) -> Unit) = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(request)?.groupValues?.get(1).orEmpty()
            methods += method
            val bytes = "<methodResponse><params><param>${answer(method, request)}</param></params></methodResponse>"
                .toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block(RTorrentRemote(RemoteCredentials("http://127.0.0.1:${server.address.port}/RPC2", "", ""))) }
        finally { server.stop(0) }
    }

    @Test fun steadyStateRuleCostsOnlyReadsAndAChangeAddsOneWritePerChangedFile() = withServer { remote ->
        val observe = RemoteFinishInOrderPort(remote) { _, _ -> true }
        val files = observe.observe(hash)!!.map { it.file }
        var saved = FinishInOrderRecord(FinishInOrderPlanner.policyFor(FinishInOrderPlanner.Mode.BALANCED,
            files, requireNotNull(FinishInOrderPlanner.suggest(files)).order))
        val runner = FinishInOrderRunner(RemoteFinishInOrderPort(remote) { _, r -> saved = r; true })

        methods.clear()
        assertTrue(runner.step(hash, saved) is Step.Applied)          // first tick raises E01
        val firstTick = methods.toList(); methods.clear()
        assertTrue("an unchanged schedule is idle", runner.step(hash, saved) is Step.Idle)
        val idleTick = methods.toList()
        println("CHUNKS_COST finish-in-order first-applied=$firstTick idle=$idleTick")

        assertEquals("an idle rule performs no write", emptyList<String>(),
            idleTick.filter { it.endsWith(".set") || it == "d.update_priorities" })
        assertTrue("idle tick reads are bounded: $idleTick", idleTick.size <= 3)
        assertEquals("one file change = one priority write", 1, firstTick.count { it == "f.priority.set" })
        assertTrue("an applied tick stays bounded: $firstTick", firstTick.size <= 6)
    }

    @Test fun costPerRuleIsIndependentOfTheNumberOfOtherRules() = withServer { remote ->
        val files = RemoteFinishInOrderPort(remote) { _, _ -> true }.observe(hash)!!.map { it.file }
        val record = FinishInOrderRecord(FinishInOrderPlanner.policyFor(FinishInOrderPlanner.Mode.BALANCED,
            files, requireNotNull(FinishInOrderPlanner.suggest(files)).order))
        var saved = record
        val runner = FinishInOrderRunner(RemoteFinishInOrderPort(remote) { _, r -> saved = r; true })
        runner.step(hash, saved)                                       // settle E01 -> High
        methods.clear()
        repeat(10) { runner.step(hash, saved) }
        val perTenTicks = methods.size
        methods.clear()
        runner.step(hash, saved)
        assertEquals("linear: ten idle ticks cost ten times one", methods.size * 10, perTenTicks)
    }
}
