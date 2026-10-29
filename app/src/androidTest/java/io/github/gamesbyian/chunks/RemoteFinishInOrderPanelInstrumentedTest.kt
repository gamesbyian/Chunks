package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteFile
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Remote Finish in Order against a loopback rTorrent XML-RPC fake. */
@RunWith(AndroidJUnit4::class)
class RemoteFinishInOrderPanelInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "AB".repeat(20)
    private val scope = "http://127.0.0.1/RPC2\u0000fio-test"
    private val paths = listOf("Show/Show S01E02.mkv", "Show/Show S01E01.mkv", "Show/Show S01E03.mkv")
    private val priorities = Collections.synchronizedList(mutableListOf(1, 1, 1))
    private val writes = Collections.synchronizedList(mutableListOf<String>())
    private var server: ServerSocket? = null
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clean() = FinishInOrderStore(context, scope).let { it.remove(hash); Unit }
    @After fun stop() { server?.close(); FinishInOrderStore(context, scope).remove(hash) }

    private fun num(n: Long) = "<value><i8>$n</i8></value>"
    private fun str(s: String) = "<value><string>$s</string></value>"
    private fun answer(method: String, body: String): String = when (method) {
        "d.hash" -> str(hash)
        "d.size_chunks" -> num(3)
        "d.bitfield" -> str("0")
        "f.multicall" -> "<value><array><data>" + paths.indices.joinToString("") { i ->
            "<value><array><data>" + str(paths[i]) + num(4096) + num(priorities[i].toLong()) +
                (if ("f.range_first" in body) num(i.toLong()) + num(i + 1L) else "") +
                "</data></array></value>"
        } + "</data></array></value>"
        "f.priority.set" -> {
            val target = Regex(":f(\\d+)</string>").find(body)!!.groupValues[1].toInt()
            val value = Regex("<i[48]>(\\d+)</i[48]>|<int>(\\d+)</int>").findAll(body).last()
                .groupValues.drop(1).first { it.isNotEmpty() }.toInt()
            writes += "f$target=$value"; priorities[target] = value; num(0)
        }
        "d.update_priorities" -> num(0)
        else -> error("Unexpected command: $method")
    }

    private fun startLoopback(): String {
        val listening = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also { server = it }
        Thread {
            while (!listening.isClosed) {
                val socket = try { listening.accept() } catch (_: Exception) { break }
                socket.use {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                    reader.readLine() ?: return@use
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: return@use
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(":").trim().toInt()
                    }
                    val chars = CharArray(length)
                    var read = 0
                    while (read < length) read += reader.read(chars, read, length - read).coerceAtLeast(0)
                    val body = String(chars)
                    val method = Regex("<methodName>([^<]+)</methodName>").find(body)?.groupValues?.get(1).orEmpty()
                    val xml = ("<?xml version=\"1.0\"?><methodResponse><params><param>" + answer(method, body) +
                        "</param></params></methodResponse>").toByteArray()
                    it.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${xml.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray())
                        write(xml); flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return "http://127.0.0.1:${listening.localPort}/RPC2"
    }

    @Test fun reviewedPolicyRaisesOnlyTheFirstEpisodeOnTheServer() {
        val remote = RTorrentRemote(RemoteCredentials(startLoopback(), "", ""))
        val files = paths.mapIndexed { i, p -> RemoteFile(p, 4096, priorities[i]) }
        var started = 0
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RemoteFinishInOrderPanel(remote, RemoteTransfer(hash, "Show", 12288, 0, true, "/srv"),
                        scope, files, stale = false, onMutationStarted = { started++; true }, onSettled = {})
                }
            }
        }
        rule.onNodeWithText("Finish in Order…").performClick()
        rule.onNodeWithText("1. Show S01E01.mkv").assertExists()
        assertEquals(0, writes.size)
        rule.onNodeWithText("Start Finish in Order").performScrollTo().performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("confirmed by the server", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, started)
        assertEquals(listOf("f1=2"), writes.toList())
    }

    @Test fun finishThisNextRaisesTheChosenEpisodeOnTheServer() {
        val remote = RTorrentRemote(RemoteCredentials(startLoopback(), "", ""))
        val files = paths.mapIndexed { i, p -> RemoteFile(p, 4096, priorities[i]) }
        val asLocal = files.mapIndexed { i, f ->
            io.github.gamesbyian.chunks.engine.LocalTransferFile(i, f.path, f.sizeBytes, 0L, true, 4)
        }
        // Order E01 (index 1), E02 (0), E03 (2), already saved.
        FinishInOrderStore(context, scope).save(hash, io.github.gamesbyian.chunks.engine.FinishInOrderRecord(
            io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.policyFor(
                io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Mode.BALANCED, asLocal, listOf(1, 0, 2))))
        var started = 0
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RemoteFinishInOrderPanel(remote, RemoteTransfer(hash, "Show", 12288, 0, true, "/srv"),
                        scope, files, stale = false, onMutationStarted = { started++; true }, onSettled = {})
                }
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithContentDescription("Finish Show S01E03.mkv next").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Finish Show S01E03.mkv next").performScrollTo().performClick()
        rule.waitUntil(10_000) { writes.contains("f2=2") }
        assertEquals(1, started)
        assertEquals(listOf(2, 1, 0), FinishInOrderStore(context, scope).get(hash)!!.policy.order.map { it.index })
        assertEquals(listOf(1, 1, 2), priorities.toList())
    }
}
