package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import io.github.gamesbyian.chunks.DeclaredAddFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Reviewed "Files to download" skips for a remote .torrent Add, through the
 * real RTorrentRemote XML-RPC adapter against a stateful localhost fixture.
 * Contract/negative evidence; real-peer bytes are RTorrentLibtorrentInteropTest.
 */
class RemoteAddFileSkipsTest {
    private val hash = "cd".repeat(20)
    private val root = "Show S01"
    private val declared = listOf(
        DeclaredAddFile(0, "$root/e01.mkv", 4_000),
        DeclaredAddFile(1, "$root/e02.mkv", 5_000),
        DeclaredAddFile(2, "$root/extras/notes.txt", 10))

    private class Fixture(
        val files: MutableList<RemoteFile>,
        val faultOnPrioritySet: Boolean = false
    ) {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }

    private fun <T> withServer(fixture: Fixture, block: suspend (RTorrentRemote) -> T): T {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(body)?.groupValues?.get(1).orEmpty()
            val strings = Regex("<string>([^<]*)</string>").findAll(body).map { it.groupValues[1] }.toList()
            fixture.calls += method + strings.firstOrNull().orEmpty().let { if (method == "f.priority.set") " $it" else "" }
            val payload = when (method) {
                "d.hash" -> "<params><param><value><string>$hash</string></value></param></params>"
                "f.multicall" -> "<params><param><value><array><data>" + fixture.files.joinToString("") { f ->
                    "<value><array><data><value><string>${f.path}</string></value>" +
                        "<value><i8>${f.sizeBytes}</i8></value><value><i8>${f.priority}</i8></value></data></array></value>"
                } + "</data></array></value></param></params>"
                "f.priority.set" -> if (fixture.faultOnPrioritySet)
                    "<fault><value><struct><member><name>faultCode</name><value><i4>-501</i4></value></member>" +
                        "<member><name>faultString</name><value><string>refused</string></value></member></struct></value></fault>"
                else {
                    val index = strings.first().substringAfter(":f").toInt()
                    val priority = Regex("<(?:i4|i8|int)>(\\d+)</(?:i4|i8|int)>").find(body)!!.groupValues[1].toInt()
                    fixture.files[index] = fixture.files[index].copy(priority = priority)
                    "<params><param><value><i8>0</i8></value></param></params>"
                }
                "d.update_priorities" -> "<params><param><value><i8>0</i8></value></param></params>"
                else -> throw AssertionError("Unexpected RPC $method")
            }
            val bytes = "<?xml version=\"1.0\"?><methodResponse>$payload</methodResponse>".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            return runBlocking { block(remote) }
        } finally {
            server.stop(0)
        }
    }

    private fun serverFiles() = mutableListOf(
        RemoteFile("e01.mkv", 4_000, 1), RemoteFile("e02.mkv", 5_000, 1), RemoteFile("extras/notes.txt", 10, 1))

    @Test fun rtorrentPathsAreRelativeToTheTorrentRoot() {
        assertEquals("extras/notes.txt", RemoteAddFileSkips.withinRoot("$root/extras/notes.txt"))
        val matched = RemoteAddFileSkips.match(listOf(declared[2]), serverFiles())
        assertEquals(listOf(2), matched?.map { it.first })
    }

    @Test fun aLayoutThatDiffersFromTheReviewMatchesNothing() {
        assertNull("size differs", RemoteAddFileSkips.match(listOf(declared[1]),
            serverFiles().also { it[1] = it[1].copy(sizeBytes = 4_999) }))
        assertNull("path differs", RemoteAddFileSkips.match(listOf(declared[1]),
            serverFiles().also { it[1] = it[1].copy(path = "e03.mkv") }))
        assertNull("index absent", RemoteAddFileSkips.match(listOf(declared[2]), serverFiles().take(2)))
    }

    @Test fun confirmedSkipsAllowTheRequestedStartAndTouchOnlyReviewedFiles() {
        val fixture = Fixture(serverFiles())
        val setup = withServer(fixture) { remote ->
            RemoteAddFileSkips.apply(remote, hash, listOf(declared[1], declared[2]), startRequested = true)
        }
        assertTrue(setup.startAllowed)
        assertEquals("Skipping 2 files as chosen; they will not download.", setup.note)
        assertEquals(listOf(1, 0, 0), fixture.files.map { it.priority })
        assertEquals(listOf("f.priority.set $hash:f1", "f.priority.set $hash:f2"),
            fixture.calls.filter { it.startsWith("f.priority.set") })
        assertFalse("No Start is sent by the skip step", fixture.calls.any { it.startsWith("d.start") })
    }

    @Test fun aMismatchedServerLayoutWritesNothingAndVetoesStart() {
        val fixture = Fixture(serverFiles().also { it[1] = it[1].copy(sizeBytes = 1) })
        val setup = withServer(fixture) { remote ->
            RemoteAddFileSkips.apply(remote, hash, listOf(declared[1]), startRequested = true)
        }
        assertFalse(setup.startAllowed)
        assertTrue(setup.note!!.contains("was not started"))
        assertTrue(fixture.calls.none { it.startsWith("f.priority.set") || it == "d.update_priorities" })
    }

    @Test fun aRefusedPriorityWriteIsNeverRepeatedAndVetoesStart() {
        val fixture = Fixture(serverFiles(), faultOnPrioritySet = true)
        val setup = withServer(fixture) { remote ->
            RemoteAddFileSkips.apply(remote, hash, listOf(declared[1], declared[2]), startRequested = false)
        }
        assertFalse(setup.startAllowed)
        assertEquals(RemoteAddFileSkips.unconfirmedNote(false), setup.note)
        assertEquals("One write attempt; the next file is not touched", 1,
            fixture.calls.count { it.startsWith("f.priority.set") })
    }

    @Test fun noSkipsMeansNoRpcAndNoNote() {
        val fixture = Fixture(serverFiles())
        val setup = withServer(fixture) { remote ->
            RemoteAddFileSkips.apply(remote, hash, emptyList(), startRequested = true)
        }
        assertEquals(RemotePreStartSetup(null, true), setup)
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun reviewedLayoutComesFromPureKotlinMetainfoAndOmitsPadFiles() {
        fun b(text: String) = "${text.toByteArray().size}:$text"
        fun file(path: List<String>, length: Long, attr: String? = null) =
            "d" + (if (attr != null) b("attr") + b(attr) else "") + b("length") + "i${length}e" +
                b("path") + "l" + path.joinToString("") { b(it) } + "e" + "e"
        val total = 4_000L + 12_384L + 5_000L
        val pieces = ((total + 16_383) / 16_384).toInt()
        val info = "d" + b("files") + "l" + file(listOf("e01.mkv"), 4_000) +
            file(listOf(".pad", "12384"), 12_384, "p") + file(listOf("e02.mkv"), 5_000) + "e" +
            b("name") + b(root) + b("piece length") + "i16384e" + "6:pieces" + "${pieces * 20}:"
        val bytes = ("d" + b("info")).toByteArray() + info.toByteArray() + ByteArray(pieces * 20) + "ee".toByteArray()
        val preview = TorrentMetainfoReader.preview(bytes)
        assertEquals(3, preview.files.size)
        assertTrue(preview.files[1].padding)
        val declaredLayout = io.github.gamesbyian.chunks.AddFileChoices.declared(preview)!!
        assertEquals("Pad file is hidden but native indexes are kept", listOf(0, 2), declaredLayout.map { it.index })
        assertEquals("$root/e02.mkv", declaredLayout[1].path)
    }
}
