package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Independently checked SHA-256 Digest challenge on the localhost transport,
 * forwarded to actual disposable rTorrent. This is not a protected host,
 * production Apache configuration or Android installed-UI acceptance test.
 */
class RTorrentDisposableDigestAuthenticatedTest {
    private val route = "/rutorrent3/RPC2"
    private val realm = "Chunks disposable Digest"
    private val user = "digest-fixture"
    private val password = ByteArray(20).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val allowed = setOf(
        "system.client_version", "d.multicall2", "d.hash", "d.name",
        "d.directory", "d.is_multi_file", "d.state", "d.is_active",
        "load.raw_verbose", "d.erase")
    private val source = ("d4:infod6:lengthi5e4:name15:digest-real.bin12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789ee").toByteArray(Charsets.UTF_8)
    private val hash by lazy { TorrentMetainfoReader.preview(source).infoHashV1 }

    private fun digest(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun validAuthorization(raw: String?): Boolean {
        if (raw == null || !raw.startsWith("Digest ") || raw.length > 4096) return false
        val attrs = linkedMapOf<String, String>()
        val kv = Regex("""([A-Za-z][A-Za-z0-9-]*)=(?:"([^"]*)"|([^,\s]+))""")
        for (m in kv.findAll(raw.removePrefix("Digest "))) {
            if (attrs.put(m.groupValues[1].lowercase(java.util.Locale.ROOT),
                    m.groupValues[2].ifEmpty { m.groupValues[3] }) != null) return false
        }
        val cnonce = attrs["cnonce"] ?: return false
        if (attrs["username"] != user || attrs["realm"] != realm || attrs["nonce"] != nonce ||
            attrs["uri"] != route || attrs["qop"] != "auth" ||
            attrs["algorithm"] != "SHA-256" || attrs["nc"] != "00000001" ||
            !Regex("[a-f0-9]{32}").matches(cnonce)) return false
        val ha1 = digest("$user:$realm:$password")
        val ha2 = digest("POST:$route")
        return MessageDigest.isEqual(
            digest("$ha1:$nonce:00000001:$cnonce:auth:$ha2").toByteArray(Charsets.US_ASCII),
            attrs["response"].orEmpty().toByteArray(Charsets.US_ASCII))
    }

    @Test fun challengedSha256DigestAuthenticatesRealOneShotAddAndSessionRemoval() = runBlocking {
        val address = System.getenv("CHUNKS_DISPOSABLE_RT_URL").orEmpty()
        assumeTrue("Explicit disposable real rTorrent fixture required", address.isNotBlank())
        val upstream = URI(address)
        require(upstream.scheme == "http" && upstream.host == "127.0.0.1" &&
            upstream.port == 18080 && upstream.rawPath == "/RPC2" &&
            upstream.rawUserInfo == null && upstream.rawQuery == null &&
            upstream.rawFragment == null) { "Refusing non-disposable upstream" }
        val root = Path.of(System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR").orEmpty())
        require(root.isAbsolute && root.toString().contains("chunks-data") &&
            Files.isDirectory(root)) { "Refusing non-disposable destination" }
        val accepted = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext(route) { exchange ->
            try {
                val xml = exchange.requestBody.use { it.readNBytes(4_194_305) }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(xml.toString(Charsets.UTF_8))?.groupValues?.get(1).orEmpty()
                if (!validAuthorization(exchange.requestHeaders.getFirst("Authorization"))) {
                    // Basic is deliberately co-advertised: Digest MUST win.
                    exchange.responseHeaders.add("WWW-Authenticate",
                        """Basic realm="legacy", Digest realm="$realm", nonce="$nonce", qop="auth", algorithm=SHA-256""")
                    exchange.sendResponseHeaders(401, -1)
                } else if (exchange.requestMethod != "POST" || method !in allowed ||
                    xml.size > 4_194_304) {
                    exchange.sendResponseHeaders(403, -1)
                } else {
                    val conn = upstream.toURL().openConnection() as HttpURLConnection
                    try {
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 8_000
                        conn.readTimeout = 15_000
                        conn.instanceFollowRedirects = false
                        conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                        conn.doOutput = true
                        conn.setFixedLengthStreamingMode(xml.size)
                        // Forward ONLY source XML; never Authorization/cookies.
                        conn.outputStream.use { it.write(xml) }
                        val code = conn.responseCode
                        if (code !in 200..299 || conn.contentLengthLong > 4_194_304) {
                            exchange.sendResponseHeaders(502, -1)
                        } else {
                            val reply = conn.inputStream.use { it.readNBytes(4_194_305) }
                            if (reply.size > 4_194_304) exchange.sendResponseHeaders(502, -1)
                            else {
                                accepted += method
                                exchange.responseHeaders.set("Content-Type", "text/xml")
                                exchange.sendResponseHeaders(200, reply.size.toLong())
                                exchange.responseBody.use { it.write(reply) }
                            }
                        }
                    } finally { conn.disconnect() }
                }
            } catch (_: Exception) {
                runCatching { exchange.sendResponseHeaders(502, -1) }
            } finally { exchange.close() }
        }
        server.start()
        try {
            val endpoint = "http://127.0.0.1:" + server.address.port + route
            for ((u, pass) in listOf(user to (password + "wrong"), (user + "wrong") to password)) {
                try {
                    RemoteSmartConnect.connect(endpoint, u, pass) { credentials ->
                        val remote = RTorrentRemote(credentials)
                        remote.version()
                        remote.listTransfers("main")
                        requireRemoteConnectIdentityEvidence(credentials, remote)
                    }
                    fail("Wrong Digest credential was admitted")
                } catch (e: RemoteSmartConnect.ConnectionFailed) {
                    assertEquals(RemoteSmartConnect.Category.AUTH, e.category)
                    assertEquals(1, e.tested)
                }
            }
            assertTrue("Invalid credentials reached actual rTorrent", accepted.isEmpty())
            val connection = RemoteSmartConnect.connect(endpoint, user, password) { credentials ->
                val remote = RTorrentRemote(credentials)
                val version = remote.version()
                val inventory = remote.listTransfers("main")
                requireRemoteConnectIdentityEvidence(credentials, remote)
                Triple(remote, version, inventory)
            }
            assertEquals(1, connection.attempts)
            val remote = connection.response.first
            assertEquals(RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED,
                remote.readAuthenticationEvidence())
            assertTrue(connection.response.second.isNotBlank())
            val before = connection.response.third.map { it.hash.uppercase() }.toSet()
            assertFalse(hash.uppercase() in before)
            val canary = root.resolve("digest-real.bin")
            val original = "HELLO".toByteArray(Charsets.US_ASCII)
            Files.write(canary, original)
            assertNull(remote.lookupName(hash))
            val add = remote.loadStoppedTorrentBytes(source, root.toString())
            assertTrue(add is RemoteAddOutcome.VerifiedStopped ||
                add is RemoteAddOutcome.PendingVerification)
            var row: RemoteTransfer? = null
            for (attempt in 1..15) {
                row = remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
                if (row != null) break
                delay(350)
            }
            val job = requireNotNull(row) { "Real rTorrent never accepted protected Digest Add" }
            assertEquals("digest-real.bin", job.name)
            assertEquals(root.toString(), job.directory)
            assertFalse(job.started)
            assertEquals(1, accepted.count { it == "load.raw_verbose" })
            val erase = remote.eraseFromSession(hash, job.name, job.directory)
            assertTrue(erase is SessionEraseOutcome.VerifiedRemoved ||
                erase is SessionEraseOutcome.PendingRemoval)
            var absent = false
            for (attempt in 1..15) {
                absent = remote.lookupName(hash) == null
                if (absent) break
                delay(350)
            }
            assertTrue("Real Digest Add remains in rTorrent session", absent)
            assertEquals(1, accepted.count { it == "d.erase" })
            assertEquals(before, remote.listTransfers("main").map { it.hash.uppercase() }.toSet())
            assertArrayEquals("Session-only Digest Remove changed payload", original,
                Files.readAllBytes(canary))
            assertTrue(accepted.all { it in allowed })
        } finally { server.stop(0) }
    }
}
