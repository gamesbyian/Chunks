package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The production RTorrentRemote transport against a loopback XML-RPC fixture.
 * These are negative/contract tests, not real rTorrent, installed-app or peer evidence.
 */
class RemoteUniversalAddAdapterTest {
    private val hash = "ab".repeat(20)
    private val magnet = "magnet:?xt=urn:btih:$hash&tr=https%3A%2F%2Ftracker.example%2Fsecret"
    private val place = "/downloads"

    private fun value(text: String) = "<value><string>$text</string></value>"
    private fun number(n: Long) = "<value><i8>$n</i8></value>"

    private fun respond(exchange: HttpExchange, payload: String, status: Int = 200) {
        if (status != 200) {
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
            return
        }
        val body = "<methodResponse><params><param>$payload</param></params></methodResponse>"
            .toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun absent(exchange: HttpExchange) {
        val xml = """<methodResponse><fault><value><struct>
            <member><name>faultCode</name><value><int>-501</int></value></member>
            <member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>
            </struct></value></fault></methodResponse>""".toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, xml.size.toLong())
        exchange.responseBody.use { it.write(xml) }
    }

    private fun row(started: Long, directory: String): String {
        val fields = listOf(
            value(hash), value("Sample"), number(100), number(0),
            number(started), value(directory), number(0), number(0), number(0)
        )
        return "<value><array><data><value><array><data>" +
            fields.joinToString("") + "</data></array></value></data></array></value>"
    }

    private fun withFixture(
        initialPresent: Boolean = false,
        start: Boolean = true,
        lostAck: Boolean = false,
        observedDirectory: String = place,
        unauthorized: Boolean = false,
        body: suspend (RemoteUniversalAddAdapter, RemoteReviewedAdd, MutableList<String>, () -> RTorrentRemote) -> Unit
    ) = runBlocking {
        var loaded = initialPresent
        val methods = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(xml)?.groupValues?.get(1).orEmpty()
            methods += method
            if (unauthorized) {
                respond(exchange, "", 401)
                return@createContext
            }
            if (method == "d.name" && !loaded) {
                absent(exchange)
                return@createContext
            }
            if (method in setOf("load.start", "load.verbose")) {
                loaded = true
                if (lostAck) {
                    respond(exchange, "", 503)
                    return@createContext
                }
            }
            val response = when (method) {
                "d.multicall2" -> if (loaded) row(if (start) 1 else 0, observedDirectory)
                    else "<value><array><data></data></array></value>"
                "d.name" -> value("Sample")
                "d.hash" -> value(hash)
                "d.directory" -> value(observedDirectory)
                "d.is_multi_file" -> number(0)
                "d.state", "d.is_active" -> number(if (start) 1 else 0)
                "load.start", "load.verbose" -> number(0)
                else -> throw AssertionError("Unexpected rTorrent RPC: $method")
            }
            respond(exchange, response)
        }
        server.start()
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}/RPC2"
            fun connection() = RTorrentRemote(RemoteCredentials(endpoint, "", ""))
            val remote = connection()
            val reviewed = RemoteReviewedAdd.magnet(
                accountScope = remote.addAccountScope(),
                plan = StoppedRemoteAddPlan.fromMagnet(magnet, place),
                startAfterAdd = start
            )
            body(RemoteUniversalAddAdapter(remote), reviewed, methods, ::connection)
        } finally {
            server.stop(0)
        }
    }

    @Test fun nativeStartLostAcknowledgementReconcilesThroughNewConnectionWithoutRetry() =
        withFixture(lostAck = true) { adapter, reviewed, methods, reconnect ->
            val first = adapter.submit(reviewed)
            assertEquals(RemoteAddAndStart.StartNotVerified(hash, null), first.result)
            assertEquals(listOf("d.multicall2", "d.name", "load.start"), methods)
            val checked = RemoteUniversalAddAdapter(reconnect()).reconcile(reviewed)
            assertEquals(RemoteAddAndStart.Started(hash), checked)
            assertEquals(1, methods.count { it == "load.start" })
            assertFalse(methods.any { it == "d.start" || it == "load.verbose" })
        }

    @Test fun stoppedAddLostAcknowledgementReconcilesExactPlaceWithoutStart() =
        withFixture(start = false, lostAck = true) { adapter, reviewed, methods, reconnect ->
            val first = adapter.submit(reviewed)
            assertEquals(RemoteAddAndStart.AddedStopped(RemoteAddOutcome.Uncertain(hash)), first.result)
            assertEquals(
                RemoteAddAndStart.AddedStopped(RemoteAddOutcome.VerifiedStopped(hash, place)),
                RemoteUniversalAddAdapter(reconnect()).reconcile(reviewed)
            )
            assertEquals(1, methods.count { it == "load.verbose" })
            assertFalse(methods.any { it == "load.start" || it == "d.start" })
        }

    @Test fun existingHashNeverSendsAddOrStart() =
        withFixture(initialPresent = true) { adapter, reviewed, methods, _ ->
            val first = adapter.submit(reviewed)
            assertEquals(RemoteAddAndStart.AddedStopped(RemoteAddOutcome.AlreadyPresent(hash)), first.result)
            assertEquals(listOf("d.multicall2"), methods)
        }

    @Test fun matchingHashAtWrongPlaceCannotClearStartedUncertainty() =
        withFixture(lostAck = true, observedDirectory = "/different") { adapter, reviewed, methods, _ ->
            assertTrue(adapter.submit(reviewed).result is RemoteAddAndStart.StartNotVerified)
            assertTrue(adapter.reconcile(reviewed) is RemoteAddAndStart.StartNotVerified)
            assertEquals(1, methods.count { it == "load.start" })
        }

    @Test fun authenticationLossBeforeLoadNeverMutates() =
        withFixture(unauthorized = true) { adapter, reviewed, methods, _ ->
            try {
                adapter.submit(reviewed)
                fail("Authentication rejection must not become successful Add")
            } catch (_: Exception) {
                assertEquals(listOf("d.multicall2"), methods)
                assertFalse(methods.any { it.startsWith("load.") || it == "d.start" })
            }
        }

    @Test fun differentAccountOrNumberedRouteRefusedBeforeAnyRpc() =
        withFixture { adapter, reviewed, methods, _ ->
            val wrongRoute = RemoteReviewedAdd.magnet(
                "https://host.example/rutorrent3/plugins/httprpc/action.php\u0000owner",
                reviewed.plan, true)
            val wrongAccount = RemoteReviewedAdd.magnet(
                reviewed.accountScope + "other", reviewed.plan, true)
            for (intent in listOf(wrongRoute, wrongAccount)) {
                try {
                    adapter.submit(intent)
                    fail("A different exact target must never be used")
                } catch (_: IllegalStateException) { }
                try {
                    adapter.reconcile(intent)
                    fail("Readback cannot cross account scope either")
                } catch (_: IllegalStateException) { }
            }
            assertTrue("No network request made for mismatched identities", methods.isEmpty())
        }

    @Test fun torrentReviewDefensivelyFreezesMetainfoAndRestrictsPriorityMode() {
        val bytes = ("d4:infod6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
            "01234567890123456789ee").toByteArray()
        val original = bytes.copyOf()
        val scope = "https://host.example/rutorrent2/plugins/httprpc/action.php\u0000owner"
        val intent = RemoteReviewedAdd.torrent(scope, bytes, place, false, true)
        bytes.fill(0)
        assertArrayEquals(original, intent.torrentBytesForSubmission())
        val getter = intent.torrentBytesForSubmission()!!
        getter.fill(1)
        assertArrayEquals(original, intent.torrentBytesForSubmission())
        assertEquals(TorrentMetainfoReader.preview(original).infoHashV1, intent.plan.expectedInfoHash)
        assertFalse(intent.toString().contains("test.txt"))
        try {
            RemoteReviewedAdd.torrent(scope, original, place, false, true,
                prioritiesBeforeStart = true)
            fail("Single-file metainfo cannot request multi-file priorities")
        } catch (_: IllegalArgumentException) { }
    }
}
