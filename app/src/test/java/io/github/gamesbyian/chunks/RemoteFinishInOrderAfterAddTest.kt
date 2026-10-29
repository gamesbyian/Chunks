package io.github.gamesbyian.chunks

import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Server Add review → Finish in Order, against a loopback XML-RPC fake (#415 remote parity). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteFinishInOrderAfterAddTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val hash = "CD".repeat(20)
    private val scope = "http://127.0.0.1/RPC2\u0000after-add"
    private var paths = listOf("Show/Show S02E02.mkv", "Show/Show S02E01.mkv", "Show/Show S02E03.mkv")
    private val priorities = Collections.synchronizedList(mutableListOf(1, 1, 1))
    private val writes = Collections.synchronizedList(mutableListOf<String>())
    private var server: ServerSocket? = null

    @After fun stop() { server?.close(); FinishInOrderStore(context, scope).remove(hash) }

    private fun num(n: Long) = "<value><i8>$n</i8></value>"
    private fun str(s: String) = "<value><string>$s</string></value>"
    private fun answer(method: String, body: String): String = when (method) {
        "d.hash" -> str(hash)
        "d.size_chunks" -> num(3)
        "d.bitfield" -> str("0")
        "f.multicall" -> "<value><array><data>" + paths.indices.joinToString("") { i ->
            "<value><array><data>" + str(paths[i]) + num(4096) + num(priorities[i].toLong()) +
                (if ("f.range_first" in body) num(i.toLong()) + num(i + 1L) else "") + "</data></array></value>"
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

    private fun loopback(): String {
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
                    val chars = CharArray(length); var read = 0
                    while (read < length) read += reader.read(chars, read, length - read).coerceAtLeast(0)
                    val body = String(chars)
                    val method = Regex("<methodName>([^<]+)</methodName>").find(body)?.groupValues?.get(1).orEmpty()
                    val xml = ("<?xml version=\"1.0\"?><methodResponse><params><param>" + answer(method, body) +
                        "</param></params></methodResponse>").toByteArray()
                    it.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${xml.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray()); write(xml); flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return "http://127.0.0.1:${listening.localPort}/RPC2"
    }

    @Test fun detectedOrderBecomesAPolicyAndRaisesOnlyTheFirstEpisode() = runBlocking {
        val remote = RTorrentRemote(RemoteCredentials(loopback(), "", ""))
        val note = enableRemoteFinishInOrderAfterAdd(context, remote, scope, hash)
        assertTrue(note, note.startsWith("Finish in Order is on for 3 files"))
        assertEquals(listOf("f1=2"), writes.toList())
        assertEquals(listOf(1, 0, 2), FinishInOrderStore(context, scope).get(hash)!!.policy.order.map { it.index })
    }

    @Test fun noDetectableOrderSetsNothingUp() = runBlocking {
        paths = listOf("Pack/readme.txt", "Pack/cover.jpg", "Pack/notes.pdf")
        val remote = RTorrentRemote(RemoteCredentials(loopback(), "", ""))
        val note = enableRemoteFinishInOrderAfterAdd(context, remote, scope, hash)
        assertTrue(note, note.contains("not set up"))
        assertTrue(writes.isEmpty())
        assertNull(FinishInOrderStore(context, scope).get(hash))
    }
}
