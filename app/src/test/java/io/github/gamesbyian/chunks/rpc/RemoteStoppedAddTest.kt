package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteStoppedAddTest {
    private val hash = "ab".repeat(20)
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=Open%20test"
    private val dir = "/home/dev/Chunks Test"

    @Test fun validatesMagnetAndArchiveSourceAndExplicitDirectory() {
        val m = StoppedRemoteAddPlan.fromMagnet(magnet, dir)
        assertEquals(hash, m.expectedInfoHash)
        assertEquals(dir, m.destination)
        val a = StoppedRemoteAddPlan.fromArchiveTorrent(
            "https://archive.org/download/example/example_archive.torrent", hash, dir)
        assertEquals(StoppedRemoteAddPlan.Kind.ARCHIVE_TORRENT_URL, a.kind)
        for (path in listOf("relative", "/", "/tmp/../media", "/tmp/,d.erase=", "/tmp//hidden")) {
            try {
                StoppedRemoteAddPlan.fromMagnet(magnet, path)
                fail("Must reject path: $path")
            } catch (_: IllegalArgumentException) { }
        }
        for (bad in listOf("https://evil.test/example.torrent",
                           "http://archive.org/download/x/x.torrent",
                           "https://archive.org/download/x/a.torrent?token=secret")) {
            try {
                StoppedRemoteAddPlan.fromArchiveTorrent(bad, hash, dir)
                fail("Must reject archive URL")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun addPlanCannotBypassValidationViaGeneratedCopy() {
        // Kotlin data classes generate a public copy() even with a private constructor.
        // Such a copy could substitute a traversal path or unrelated infohash.
        assertFalse(StoppedRemoteAddPlan::class.java.methods.any { it.name == "copy" })
        assertTrue(StoppedRemoteAddPlan::class.java.constructors.none { !it.isSynthetic })
        val valid = StoppedRemoteAddPlan.fromMagnet(magnet, dir)
        assertEquals(dir, valid.destination)
        assertEquals(hash, valid.expectedInfoHash)
    }

    private fun value(text: String) = "<value><string>$text</string></value>"
    private fun num(i: Long) = "<value><i8>$i</i8></value>"
    private fun rowXml(): String {
        val row = listOf(value(hash), value("Open test"), num(10), num(0),
                         num(0), value(dir), num(0), num(0), num(0))
        return "<value><array><data><value><array><data>" +
            row.joinToString("") + "</data></array></value></data></array></value>"
    }
    private fun respondAbsentHash(exchange: com.sun.net.httpserver.HttpExchange) {
        val reply = """<methodResponse><fault><value><struct>
            <member><name>faultCode</name><value><int>-501</int></value></member>
            <member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>
            </struct></value></fault></methodResponse>""".toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, reply.size.toLong())
        exchange.responseBody.use { it.write(reply) }
    }

    @Test fun ambiguousStoppedAddCanBeReconciledWithoutASecondWrite() = runBlocking {
        var loaded = false
        val methods = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(xml)?.groupValues?.get(1).orEmpty()
            methods.add(method)
            if (method == "load.verbose") {
                // The remote has already applied Add, but its proxy lost the ACK.
                loaded = true
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            } else {
                if (method == "d.name") { respondAbsentHash(exchange); return@createContext }
                val body = when (method) {
                    "d.multicall2" -> if (loaded) rowXml()
                        else "<value><array><data></data></array></value>"
                    "d.directory" -> value(dir)
                    "d.is_multi_file" -> num(0)
                    "d.state" -> num(0)
                    else -> throw AssertionError("Unexpected method: $method")
                }
                val response = "<methodResponse><params><param>$body</param></params></methodResponse>"
                    .toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            val plan = StoppedRemoteAddPlan.fromMagnet(magnet, dir)
            assertEquals(RemoteAddOutcome.Uncertain(hash), remote.loadStopped(plan))
            assertEquals(listOf("d.multicall2", "d.name", "load.verbose"), methods)
            assertEquals(RemoteAddOutcome.VerifiedStopped(hash, dir),
                remote.reconcileStoppedAdd(plan))
            assertEquals("Only ONE Add RPC despite a lost acknowledgement",
                1, methods.count { it == "load.verbose" })
            assertEquals(listOf("d.multicall2", "d.name", "load.verbose", "d.multicall2",
                "d.directory", "d.is_multi_file", "d.state"), methods)
        } finally { server.stop(0) }
    }

    @Test fun loadsStoppedAndVerifiesDestinationAndState() = runBlocking {
        var loaded = false
        val requests = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(body)?.groupValues?.get(1).orEmpty()
            requests.add(method + "|" + body)
            if (method == "d.name") { respondAbsentHash(exchange); return@createContext }
            val responseValue = when (method) {
                "d.multicall2" -> if (loaded) rowXml()
                                   else "<value><array><data></data></array></value>"
                "load.verbose" -> {
                    if (!body.contains("d.directory.set=$dir") ||
                        !body.contains("magnet:?xt=")) throw AssertionError("Unexpected load request")
                    loaded = true
                    num(0)
                }
                "d.directory" -> value(dir)
                "d.is_multi_file" -> num(0)
                "d.state" -> num(0)
                else -> throw AssertionError("Unsupported test RPC: $method")
            }
            val xml = "<methodResponse><params><param>$responseValue</param></params></methodResponse>"
                .toByteArray()
            exchange.sendResponseHeaders(200, xml.size.toLong())
            exchange.responseBody.use { it.write(xml) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            val plan = StoppedRemoteAddPlan.fromMagnet(magnet, dir)
            assertEquals(RemoteAddOutcome.VerifiedStopped(hash, dir), remote.loadStopped(plan))
            assertTrue(loaded)
            assertEquals(listOf("d.multicall2", "d.name", "load.verbose", "d.multicall2",
                "d.directory", "d.is_multi_file", "d.state"),
                requests.map { it.substringBefore('|') })
            val beforeDuplicate = requests.size
            assertEquals(RemoteAddOutcome.AlreadyPresent(hash), remote.loadStopped(plan))
            assertEquals(beforeDuplicate + 1, requests.size)
            assertEquals("d.multicall2", requests.last().substringBefore('|'))
        } finally {
            server.stop(0)
        }
    }

    @Test fun defaultAddOmitsDirectorySetterButRequiresExactObservedDestination() = runBlocking {
        var loaded = false
        val requests = mutableListOf<Pair<String, String>>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(xml)?.groupValues?.get(1).orEmpty()
            requests.add(method to xml)
            if (method == "d.name") { respondAbsentHash(exchange); return@createContext }
            val value = when (method) {
                "d.multicall2" -> if (loaded) rowXml() else "<value><array><data></data></array></value>"
                "directory.default" -> value(dir)
                "load.verbose" -> {
                    if (xml.contains("d.directory.set=")) throw AssertionError("Default must not override path")
                    loaded = true
                    num(0)
                }
                "d.directory" -> value(dir)
                "d.is_multi_file" -> num(0)
                "d.state" -> num(0)
                else -> throw AssertionError("Unexpected method")
            }
            val response = "<methodResponse><params><param>" + value + "</param></params></methodResponse>"
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:" + server.address.port + "/RPC2", "", ""))
            val plan = StoppedRemoteAddPlan.fromMagnetAtServerDefault(magnet, dir)
            assertTrue(plan.useServerDefault)
            assertEquals(RemoteAddOutcome.VerifiedStopped(hash, dir), remote.loadStopped(plan))
            assertEquals(listOf("d.multicall2", "d.name", "directory.default", "load.verbose",
                "d.multicall2", "d.directory", "d.is_multi_file", "d.state"),
                requests.map { it.first })
            assertFalse(requests.first { it.first == "load.verbose" }.second.contains("d.directory.set"))
        } finally { server.stop(0) }
    }

    @Test fun changedDefaultRefusesAddBeforeSendingMutation() = runBlocking {
        val methods = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(xml)?.groupValues?.get(1).orEmpty()
            methods.add(method)
            if (method == "d.name") { respondAbsentHash(exchange); return@createContext }
            val responseValue = when (method) {
                "d.multicall2" -> "<value><array><data></data></array></value>"
                "directory.default" -> value("/other/server/default")
                else -> throw AssertionError("Mutation sent after default changed")
            }
            val body = ("<methodResponse><params><param>" + responseValue +
                "</param></params></methodResponse>").toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:" + server.address.port + "/RPC2", "", ""))
            val plan = StoppedRemoteAddPlan.fromMagnetAtServerDefault(magnet, dir)
            try {
                remote.loadStopped(plan)
                fail("Must refuse a stale server default before load.verbose")
            } catch (_: IllegalStateException) { }
            assertEquals(listOf("d.multicall2", "d.name", "directory.default"), methods)
        } finally { server.stop(0) }
    }

    @Test fun acknowledgedButDelayedAddWaitsForVerifiedStoppedThenStartsExactlyOnce() = runBlocking {
        var loaded = false
        var afterLoadReads = 0
        val calls = mutableListOf<String>()
        val startedHashes = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(xml)?.groupValues?.get(1).orEmpty()
            calls.add(method)
            if (method == "d.name") { respondAbsentHash(exchange); return@createContext }
            val value = when (method) {
                "d.multicall2" -> {
                    if (!loaded) "<value><array><data></data></array></value>"
                    else {
                        afterLoadReads++
                        if (afterLoadReads < 3) "<value><array><data></data></array></value>"
                        else rowXml()
                    }
                }
                "load.verbose" -> { loaded = true; num(0) }
                "d.directory" -> value(dir)
                "d.is_multi_file", "d.state" -> num(0)
                else -> throw AssertionError("Unexpected RPC")
            }
            val body = "<methodResponse><params><param>$value</param></params></methodResponse>"
                .toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            val result = RemoteAddStarter.run(
                startAfterVerifiedAdd = true,
                add = { remote.loadStopped(StoppedRemoteAddPlan.fromMagnet(magnet, dir)) },
                start = { requested ->
                    startedHashes.add(requested)
                    LifecycleOutcome.Verified(true)
                }
            )
            assertEquals(RemoteAddAndStart.Started(hash), result)
            assertEquals(listOf(hash), startedHashes)
            assertEquals(1, calls.count { it == "load.verbose" })
            assertEquals(3, afterLoadReads)
        } finally { server.stop(0) }
    }

    @Test fun pendingAddNeverRetriesMutationOrInventsStartAfterReadbackDeadline() = runBlocking {
        var loads = 0
        var inventoryReads = 0
        var startCount = 0
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(request)?.groupValues?.get(1).orEmpty()
            if (method == "d.name") { respondAbsentHash(exchange); return@createContext }
            val value = when (method) {
                "d.multicall2" -> {
                    inventoryReads++
                    "<value><array><data></data></array></value>"
                }
                "load.verbose" -> { loads++; num(0) }
                else -> throw AssertionError("Unexpected RPC")
            }
            val body = "<methodResponse><params><param>$value</param></params></methodResponse>"
                .toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            val result = RemoteAddStarter.run(
                startAfterVerifiedAdd = true,
                add = { remote.loadStopped(StoppedRemoteAddPlan.fromMagnet(magnet, dir)) },
                start = {
                    startCount++
                    LifecycleOutcome.Verified(true)
                }
            )
            assertEquals(RemoteAddAndStart.AddedStopped(
                RemoteAddOutcome.PendingVerification(hash)), result)
            assertEquals(1, loads)
            assertEquals(9, inventoryReads) // preflight + eight bounded exact-hash reads
            assertEquals(0, startCount)
        } finally { server.stop(0) }
    }

}
