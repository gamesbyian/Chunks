package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.InetAddress
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Synthetic loopback HTTP RPC gateway. No server credentials, real torrents,
 * or destructive network commands are used in public CI.
 */
class RTorrentRemoteFixtureTest {
    private data class Reply(val code: Int, val xml: String)

    private class Fixture(handler: (String, String?) -> Reply) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endpoint: String get() = "http://127.0.0.1:${server.address.port}/RPC2"

        init {
            server.createContext("/RPC2") { exchange ->
                val rpcBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                methods.add(rpcBody)
                val reply = handler(rpcBody, exchange.requestHeaders.getFirst("Authorization"))
                val bytes = reply.xml.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/xml")
                if (reply.code == 401) exchange.responseHeaders.set("WWW-Authenticate", """Basic realm="Chunks fixture" """)
                exchange.sendResponseHeaders(reply.code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private fun result(value: String): String =
        "<methodResponse><params><param><value>$value</value></param></params></methodResponse>"

    private fun fault(code: Int, message: String): String =
        """<methodResponse><fault><value><struct>
           <member><name>faultCode</name><value><int>$code</int></value></member>
           <member><name>faultString</name><value><string>$message</string></value></member>
        </struct></value></fault></methodResponse>"""

    @Test fun readOnlyListingThroughAuthenticatedGateway() = runBlocking {
        val expectedAuth = "Basic " + Base64.getEncoder()
            .encodeToString("demo:fixture-secret".toByteArray(Charsets.UTF_8))
        var sawAuth = false
        Fixture { request, authorization ->
            if (authorization == null) Reply(401, "Credentials required")
            else {
                sawAuth = authorization == expectedAuth
                when {
                request.contains("<methodName>system.client_version</methodName>") ->
                    Reply(200, result("<string>0.9.8</string>"))
                request.contains("<methodName>d.multicall2</methodName>") ->
                    Reply(200, result("""<array><data><value><array><data>
                      <value><string>AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA</string></value>
                      <value><string>Star Trek</string></value>
                      <value><i8>100</i8></value>
                      <value><i8>70</i8></value>
                      <value><int>1</int></value>
                      <value><string>/test/tv</string></value>
                      <value><i8>2048</i8></value>
                      <value><i8>1024</i8></value>
                      <value><i8>1500</i8></value>
                    </data></array></value></data></array>"""))
                else -> Reply(200, fault(404, "Unexpected RPC method"))
                }
            }
        }.use { fixture ->
            val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "demo", "fixture-secret"))
            assertEquals("0.9.8", remote.version())
            val listed = remote.listTransfers()
            assertEquals(1, listed.size)
            assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", listed.single().hash)
            assertEquals("/test/tv", listed.single().directory)
            assertEquals(0.7f, listed.single().fractionComplete, 0.0001f)
            assertEquals(2048L, listed.single().downBytesPerSecond)
            assertEquals(1500L, listed.single().ratioThousandths)
            assertTrue(sawAuth)
            assertTrue(fixture.methods.all { it.contains("system.client_version") || it.contains("d.multicall2") })
        }
    }

    @Test fun methodProbeDifferentiatesUnavailableEnumeration() = runBlocking {
        Fixture { request, _ ->
            when {
                request.contains("system.client_version") -> Reply(200, result("<string>0.16</string>"))
                request.contains("system.listMethods") -> Reply(200, fault(-32601, "Method not found"))
                else -> Reply(200, fault(-32601, "Unknown"))
            }
        }.use { fixture ->
            val probe = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", "")).probe()
            assertEquals("0.16", probe.version)
            assertNull(probe.advertisedMethods)
            assertNull(probe.multicallAdvertised)
        }
    }

    @Test fun methodProbeReturnsReportedCapabilities() = runBlocking {
        Fixture { request, _ ->
            when {
                request.contains("system.client_version") -> Reply(200, result("<string>0.16</string>"))
                request.contains("system.listMethods") ->
                    Reply(200, result("""<array><data>
                      <value><string>system.client_version</string></value>
                      <value><string>d.multicall2</string></value>
                    </data></array>"""))
                else -> Reply(200, fault(404, "Unexpected"))
            }
        }.use { fixture ->
            val probe = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", "")).probe()
            assertEquals(true, probe.multicallAdvertised)
            assertEquals(2, probe.advertisedMethods?.size)
        }
    }

    @Test fun redirectIsRejectedRatherThanSendingCredentialsElsewhere() = runBlocking {
        Fixture { _, _ -> Reply(302, "Moved") }.use { fixture ->
            try {
                RTorrentRemote(RemoteCredentials(fixture.endpoint, "user", "password")).version()
                fail("Redirect must not be followed")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("302"))
            }
        }
    }

    @Test fun plainHttpToNonLoopbackIsRefusedBeforeConnection() {
        try {
            RTorrentRemote(RemoteCredentials("http://example.org/RPC2", "", ""))
            fail("Remote HTTP without TLS must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("HTTPS"))
        }
    }
    @Test fun optionalRatesFaultFallsBackToMinimumFields() = runBlocking {
        var triedExtended = false
        Fixture { request, _ ->
            if (request.contains("d.down.rate=")) {
                triedExtended = true
                Reply(200, fault(-32601, "d.down.rate missing"))
            } else Reply(200, result("""<array><data><value><array><data>
                <value><string>AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA</string></value>
                <value><string>Old server sample</string></value>
                <value><i8>100</i8></value>
                <value><i8>70</i8></value>
                <value><int>1</int></value>
                <value><string>/data</string></value>
            </data></array></value></data></array>"""))
        }.use { fixture ->
            val items = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", "")).listTransfers()
            assertTrue(triedExtended)
            assertEquals(1, items.size)
            assertNull(items.single().downBytesPerSecond)
            assertEquals("Old server sample", items.single().name)
        }
    }

    @Test fun readOnlyFileInspectionUsesValidHash() = runBlocking {
        Fixture { request, _ ->
            assertTrue(request.contains("f.multicall"))
            Reply(200, result("""<array><data><value><array><data>
                <value><string>Season 01/Episode 01.mkv</string></value>
                <value><i8>2048</i8></value>
                <value><int>1</int></value>
            </data></array></value></data></array>"""))
        }.use { fixture ->
            val files = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
                .listFiles("A".repeat(40))
            assertEquals(1, files.size)
            assertEquals(2048L, files.first().sizeBytes)
            assertEquals("Season 01/Episode 01.mkv", files.first().path)
        }
    }

    @Test fun rejectsArbitraryViewAndInvalidFileHashBeforeRpc() = runBlocking {
        Fixture { _, _ -> Reply(200, result("<string>unneeded</string>")) }.use { fixture ->
            val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
            try {
                remote.listTransfers("main,d.stop=")
                fail("Invalid view must not reach server")
            } catch (_: IllegalArgumentException) { }
            try {
                remote.listFiles("../unsafe")
                fail("Invalid hash must not reach server")
            } catch (_: IllegalArgumentException) { }
            assertTrue(fixture.methods.isEmpty())
        }
    }

    @Test fun duplicateCaseVariantInfohashFailsClosedInsteadOfRenderingOrMutating() = runBlocking {
        val identity = "aB".repeat(20)
        fun row(hash: String, name: String): String =
            "<value><array><data>" +
                "<value><string>$hash</string></value>" +
                "<value><string>$name</string></value>" +
                "<value><i8>100</i8></value><value><i8>30</i8></value>" +
                "<value><int>1</int></value><value><string>/test</string></value>" +
                "<value><i8>0</i8></value><value><i8>0</i8></value>" +
                "<value><i8>0</i8></value></data></array></value>"
        Fixture { request, _ ->
            assertTrue(request.contains("<methodName>d.multicall2</methodName>"))
            Reply(200, result("<array><data>" +
                row(identity, "First title") +
                row(identity.uppercase(), "Conflicting title") +
                "</data></array>"))
        }.use { fixture ->
            val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
            try {
                remote.listTransfers()
                fail("A duplicate torrent identity must not enter the native queue")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("duplicate"))
            }
            assertEquals(1, fixture.methods.size)
            assertTrue(fixture.methods.single().contains("d.multicall2"))
            assertFalse(fixture.methods.single().contains("d.erase"))
        }
    }

    @Test fun rejectsMalformedInfohashRowsWithoutSilentlyUsingTheirValues() = runBlocking {
        fun row(hash: String): String = "<value><array><data>" +
            "<value><string>$hash</string></value>" +
            "<value><string>Untrusted server title</string></value>" +
            "<value><i8>100</i8></value><value><i8>30</i8></value>" +
            "<value><int>1</int></value><value><string>/data</string></value>" +
            "<value><i8>0</i8></value><value><i8>0</i8></value>" +
            "<value><i8>0</i8></value></data></array></value>"

        for (invalid in listOf("ABCDEF", "G".repeat(40), "a".repeat(39),
                "b".repeat(41), "a".repeat(65), "a".repeat(40) + "spoof")) {
            Fixture { request, _ ->
                assertTrue(request.contains("<methodName>d.multicall2</methodName>"))
                Reply(200, result("<array><data>" + row(invalid) + "</data></array>"))
            }.use { fixture ->
                val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
                try {
                    remote.listTransfers()
                    fail("Malformed torrent ID must block publication: $invalid")
                } catch (expected: IllegalArgumentException) {
                    assertTrue(expected.message.orEmpty().contains("infohash"))
                }
                assertEquals(1, fixture.methods.size)
                assertFalse(fixture.methods.single().contains("d.erase"))
            }
        }
    }

    @Test fun acceptsProperHexV2LengthAsDistinctTransferIdentity() = runBlocking {
        val valid = "ab".repeat(32)
        Fixture { request, _ ->
            assertTrue(request.contains("<methodName>d.multicall2</methodName>"))
            Reply(200, result("""<array><data><value><array><data>
                <value><string>$valid</string></value>
                <value><string>Sixty-four hex test</string></value>
                <value><i8>100</i8></value><value><i8>30</i8></value>
                <value><int>0</int></value><value><string>/data</string></value>
                <value><i8>0</i8></value><value><i8>0</i8></value>
                <value><i8>0</i8></value>
            </data></array></value></data></array>"""))
        }.use { fixture ->
            assertEquals(valid, RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
                .listTransfers().single().hash)
            assertEquals(1, fixture.methods.size)
        }
    }


    private fun hashFor(index: Int) = "%040X".format(index)

    /** Serves an oversized d.multicall2 and a paged download_list/system.multicall gateway. */
    private fun largeQueue(count: Int, faultHash: String? = null) = Fixture { request, _ ->
        when {
            request.contains("<methodName>d.multicall2</methodName>") -> Reply(200, result("<array><data>" +
                (0 until count).joinToString("") { i ->
                    "<value><array><data><value><string>${hashFor(i)}</string></value>" +
                        "<value><string>${"Long fixture torrent name ".repeat(12)}$i</string></value>" +
                        "<value><i8>1</i8></value><value><i8>1</i8></value><value><i8>0</i8></value>" +
                        "<value><string>/data/fixture/$i</string></value><value><i8>0</i8></value>" +
                        "<value><i8>0</i8></value><value><i8>0</i8></value></data></array></value>"
                } + "</data></array>"))
            request.contains("<methodName>download_list</methodName>") -> Reply(200, result("<array><data>" +
                (0 until count).joinToString("") { "<value><string>${hashFor(it)}</string></value>" } +
                "</data></array>"))
            request.contains("<methodName>system.multicall</methodName>") -> {
                val calls = Regex("<name>methodName</name><value><string>([a-z_.]+)</string></value></member>" +
                    "<member><name>params</name><value><array><data><value><string>([0-9A-F]{40})</string>")
                    .findAll(request).map { it.groupValues[1] to it.groupValues[2] }.toList()
                Reply(200, result("<array><data>" + calls.joinToString("") { (method, hash) ->
                    if (hash == faultHash) """<value><struct><member><name>faultCode</name><value><int>-501</int></value></member>
                        <member><name>faultString</name><value><string>Could not find info-hash.</string></value></member></struct></value>"""
                    else {
                        val index = hash.toInt(16)
                        val value = when (method) {
                            "d.hash" -> "<string>$hash</string>"
                            "d.name" -> "<string>Torrent $index</string>"
                            "d.directory" -> "<string>/data/fixture/$index</string>"
                            "d.size_bytes" -> "<i8>${1000 + index}</i8>"
                            "d.completed_bytes" -> "<i8>$index</i8>"
                            "d.state" -> "<i8>${index % 2}</i8>"
                            else -> "<i8>7</i8>"
                        }
                        "<value><array><data><value>$value</value></data></array></value>"
                    }
                } + "</data></array>"))
            }
            else -> Reply(200, fault(404, "Unexpected RPC method"))
        }
    }

    @Test fun queueTooLargeForOneReplyIsReadInBoundedPages() = runBlocking {
        largeQueue(7_000).use { fixture ->
            val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
            val listed = remote.listTransfers()
            assertEquals(7_000, listed.size)
            assertEquals(hashFor(0), listed.first().hash)
            assertEquals(hashFor(6_999), listed.last().hash)
            assertEquals("Torrent 4321", listed[4321].name)
            assertEquals("/data/fixture/4321", listed[4321].directory)
            assertEquals(1000L + 4321, listed[4321].sizeBytes)
            assertEquals(7L, listed[4321].downBytesPerSecond)
            assertTrue(listed[4321].started)
            val methods = fixture.methods.map { Regex("<methodName>([a-z_.0-9]+)</methodName>").find(it)!!.groupValues[1] }
            assertEquals(listOf("d.multicall2", "download_list"), methods.take(2))
            // One rich-field probe plus ceil(7000 / 250) pages; every request is read-only.
            assertEquals(1 + 28, methods.count { it == "system.multicall" })
            assertEquals(setOf("d.multicall2", "download_list", "system.multicall"), methods.toSet())
            val getters = fixture.methods.flatMap { body ->
                Regex("<name>methodName</name><value><string>([a-z_.]+)</string>").findAll(body).map { it.groupValues[1] }.toList()
            }.toSet()
            assertEquals(setOf("d.hash", "d.name", "d.size_bytes", "d.completed_bytes", "d.state",
                "d.directory", "d.down.rate", "d.up.rate", "d.ratio"), getters)
            assertTrue(fixture.methods.all { it.length < 4_194_304 })
        }
    }

    @Test fun torrentVanishingDuringPagedReadFailsTheWholeSnapshotClosed() {
        largeQueue(7_000, faultHash = hashFor(5_000)).use { fixture ->
            val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
            try {
                runBlocking { remote.listTransfers() }
                fail("A partial list must not be published")
            } catch (expected: RpcFault) {
                assertTrue(expected.message.orEmpty().contains("refresh"))
            }
        }
    }

    @Test fun codecReportsWideRepliesDistinctlyFromHostileNesting() {
        val wide = result("<array><data>" + "<value><i8>1</i8></value>".repeat(13_000) + "</data></array>")
        assertThrows(XmlRpcSizeLimitExceeded::class.java) { XmlRpcCodec.response(wide) }
        val deep = result("<array><data>".repeat(30) + "<value><i8>1</i8></value>" + "</data></array>".repeat(30))
        val failure = assertThrows(IllegalArgumentException::class.java) { XmlRpcCodec.response(deep) }
        assertFalse(failure is XmlRpcSizeLimitExceeded)
    }

    @Test fun defaultDirectoryIsReadOnlyAndValidated() = runBlocking {
        for ((reply, expected) in listOf(
            result("<string>/home/dev/downloads/</string>") to "/home/dev/downloads",
            result("<string>relative/path</string>") to null,
            fault(-506, "Method 'directory.default' not defined") to null)) {
            Fixture { request, _ ->
                assertTrue(request.contains("<methodName>directory.default</methodName>"))
                Reply(200, reply)
            }.use { fixture ->
                assertEquals(expected, RTorrentRemote(RemoteCredentials(fixture.endpoint, "", "")).defaultDirectory())
            }
        }
    }

    @Test fun lookupNameIsExactAndTreatsUnknownHashAsAbsent() = runBlocking {
        val known = "A".repeat(40)
        Fixture { request, _ ->
            assertTrue(request.contains("<methodName>d.name</methodName>"))
            if (request.contains(known)) Reply(200, result("<string>Fixture name</string>"))
            else Reply(200, fault(-501, "Could not find info-hash."))
        }.use { fixture ->
            val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
            assertEquals("Fixture name", remote.lookupName(known))
            assertNull(remote.lookupName("B".repeat(40)))
            try { remote.lookupName("not-a-hash"); fail("must refuse") } catch (_: IllegalArgumentException) { }
        }
    }
}
