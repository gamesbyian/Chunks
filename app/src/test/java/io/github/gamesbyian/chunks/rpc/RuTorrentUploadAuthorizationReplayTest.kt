package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** An HTTP 401 after an already-processed upload can never trigger a second upload. */
class RuTorrentUploadAuthorizationReplayTest {
    private val torrent = ("d4:infod6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789ee").toByteArray(Charsets.ISO_8859_1)
    private val hash = TorrentMetainfoReader.preview(torrent).infoHashV1
    private val challengeBasic = """Basic realm="Fixture""""
    private val challengeDigest =
        """Digest realm="Fixture", nonce="one", qop="auth", algorithm=SHA-256"""
    private class Fake(
        private val challenge: String?,
        private val rejectAfterUpload: Boolean,
        private val headChallenge: String? = null,
        private val headStatus: Int = 405,
        private val protectListing: Boolean = false
    ) : AutoCloseable {
        private fun result(value: String) =
            "<methodResponse><params><param><value>$value</value></param></params></methodResponse>"
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val rpcAuthorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val authorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        @Volatile var uploads = 0
            private set
        @Volatile var heads = 0
            private set
        val root get() = "http://127.0.0.1:${server.address.port}/rutorrent/"
        init {
            server.createContext("/rutorrent/plugins/httprpc/action.php") { e ->
                val xml = e.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(xml)?.groupValues?.get(1).orEmpty()
                methods += method
                rpcAuthorizations += e.requestHeaders.getFirst("Authorization")
                if (challenge != null && (method == "system.client_version" ||
                        (protectListing && method == "d.multicall2" &&
                            e.requestHeaders.getFirst("Authorization") == null))) {
                    e.responseHeaders.add("WWW-Authenticate", challenge)
                    e.sendResponseHeaders(401, -1)
                    e.close()
                } else {
                    val answer = when (method) {
                        "d.multicall2" -> result("<array><data></data></array>")
                        "d.name" -> """<methodResponse><fault><value><struct>
                            <member><name>faultCode</name><value><int>-501</int></value></member>
                            <member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>
                            </struct></value></fault></methodResponse>"""
                        "system.client_version" -> result("<string>0.9.8</string>")
                        else -> error("Unexpected RPC: $method")
                    }
                    val bytes = answer.toByteArray(Charsets.UTF_8)
                    e.sendResponseHeaders(200, bytes.size.toLong())
                    e.responseBody.use { it.write(bytes) }
                }
            }
            server.createContext("/rutorrent/php/addtorrent.php") { e ->
                if (e.requestMethod == "HEAD") {
                    heads++
                    check(e.requestHeaders.getFirst("Authorization") == null)
                    if (headChallenge != null) e.responseHeaders.add("WWW-Authenticate", headChallenge)
                    if (headStatus == 302) e.responseHeaders.add("Location", "http://127.0.0.1:1/elsewhere")
                    e.sendResponseHeaders(if (headChallenge == null) headStatus else 401, -1)
                    e.close()
                    return@createContext
                }
                e.requestBody.use { it.readBytes() }
                uploads++
                authorizations += e.requestHeaders.getFirst("Authorization")
                if (rejectAfterUpload) {
                    if (challenge != null) e.responseHeaders.add("WWW-Authenticate", challenge)
                    e.sendResponseHeaders(401, -1)
                    e.close()
                } else {
                    val body = "{}".toByteArray()
                    e.sendResponseHeaders(200, body.size.toLong())
                    e.responseBody.use { it.write(body) }
                }
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    @Test fun basicAndDigestChallengeAreResolvedReadOnlyBeforeOneUpload() = runBlocking {
        for (challenge in listOf(challengeBasic, challengeDigest)) {
            Fake(challenge, rejectAfterUpload = true, headChallenge = challenge).use { fake ->
                val remote = RTorrentRemote(RemoteCredentials(fake.root, "alice", "secret"))
                val result = remote.loadStoppedViaRuTorrentUpload(torrent, "/srv/media")
                assertEquals(RemoteAddOutcome.Uncertain(hash), result)
                assertEquals(1, fake.uploads)
                assertEquals(1, fake.heads)
                assertEquals(listOf("d.multicall2", "d.name"),
                    fake.methods)
                val header = fake.authorizations.single().orEmpty()
                assertTrue("Expected preauthorized form", header.startsWith(
                    if (challenge.startsWith("Digest")) "Digest " else "Basic "))
                assertFalse(header.contains("secret"))
                if (challenge.startsWith("Digest"))
                    assertTrue(header.contains("uri=\"/rutorrent/php/addtorrent.php\""))
            }
        }
    }

    @Test fun uploadOnlyChallengeAfterAnonymousRpcPreflightNeverReplaysForm() = runBlocking {
        Fake(null, rejectAfterUpload = true).use { fake ->
            val remote = RTorrentRemote(RemoteCredentials(fake.root, "alice", "secret"))
            assertEquals(RemoteAddOutcome.Uncertain(hash),
                remote.loadStoppedViaRuTorrentUpload(torrent, "/srv/media"))
            assertEquals(1, fake.uploads)
            assertNull(fake.authorizations.single())
            assertEquals(listOf("d.multicall2", "d.name"), fake.methods)
        }
    }

    @Test fun uploadTargetHeadChallengeAuthorizesOneFormWithoutReplay() = runBlocking {
        for (challenge in listOf(challengeBasic, challengeDigest)) {
            Fake(null, rejectAfterUpload = true, headChallenge = challenge).use { fake ->
                val remote = RTorrentRemote(RemoteCredentials(fake.root, "alice", "secret"))
                assertEquals(RemoteAddOutcome.Uncertain(hash),
                    remote.loadStoppedViaRuTorrentUpload(torrent, "/srv/media"))
                assertEquals(1, fake.uploads)
                val header = fake.authorizations.single().orEmpty()
                assertTrue(header.startsWith(if (challenge == challengeBasic) "Basic " else "Digest "))
                if (challenge == challengeDigest)
                    assertTrue(header.contains("uri=\"/rutorrent/php/addtorrent.php\""))
            }
        }
    }

    @Test fun rpcChallengeDoesNotAuthorizeASeparateUploadPath() = runBlocking {
        for (headStatus in listOf(200, 403, 404, 405, 501)) {
            Fake(challengeDigest, rejectAfterUpload = true, headStatus = headStatus, protectListing = true).use { fake ->
                val remote = RTorrentRemote(RemoteCredentials(fake.root, "alice", "secret"))
                assertEquals(RemoteAddOutcome.Uncertain(hash),
                    remote.loadStoppedViaRuTorrentUpload(torrent, "/srv/media"))
                assertEquals(1, fake.heads)
                assertEquals(1, fake.uploads)
                assertNull(fake.authorizations.single())
                assertEquals(2, fake.methods.count { it == "d.multicall2" })
                assertNull(fake.rpcAuthorizations[0])
                assertTrue(fake.rpcAuthorizations[1].orEmpty().startsWith("Digest "))
                assertTrue(fake.methods.none { it == "system.client_version" })
            }
        }
    }

    @Test fun uploadHeadRedirectPreventsAnyFormSubmission() = runBlocking {
        Fake(challengeBasic, rejectAfterUpload = true, headStatus = 302).use { fake ->
            val remote = RTorrentRemote(RemoteCredentials(fake.root, "alice", "secret"))
            assertEquals(RemoteAddOutcome.Uncertain(hash),
                remote.loadStoppedViaRuTorrentUpload(torrent, "/srv/media"))
            assertEquals(1, fake.heads)
            assertEquals(0, fake.uploads)
        }
    }

    @Test fun noCredentialsCannotReplayUploadAfterServerChallenge() = runBlocking {
        Fake(challengeBasic, rejectAfterUpload = true).use { fake ->
            val remote = RTorrentRemote(RemoteCredentials(fake.root, "", ""))
            assertEquals(RemoteAddOutcome.Uncertain(hash),
                remote.loadStoppedViaRuTorrentUpload(torrent, "/srv/media"))
            assertEquals(1, fake.uploads)
            assertTrue("No preflight when no credentials are supplied",
                fake.methods.none { it == "system.client_version" })
            assertNull(fake.authorizations.single())
        }
    }
}
