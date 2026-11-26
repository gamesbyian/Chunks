package io.github.gamesbyian.chunks.rpc

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Deluge wire owns its socket, so a stalled write has a real deadline (no write timeout exists on
 * HttpURLConnection) and cancel/deadline close the socket instead of waiting for a timeout. Real sockets.
 */
class DelugeWireDeadlineTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val server = ServerSocket(0, 5, loopback)
    private val url = "http://127.0.0.1:${server.localPort}/w/json"
    private fun req(body: ByteArray, cookie: String? = null) = DelugeReadOnlyRpc.Request(java.net.URI(url), body, cookie)

    @After fun close() { server.close() }

    /** Accept one connection, read the request head and body, answer with [raw]. Returns what the server saw. */
    private fun serveOnce(raw: ByteArray): CompletableFuture<String> {
        val seen = CompletableFuture<String>()
        Thread {
            try {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    val buf = ByteArray(65536)
                    val text = StringBuilder()
                    while (!text.contains("\r\n\r\n")) { val n = input.read(buf); if (n < 0) break; text.append(String(buf, 0, n, Charsets.ISO_8859_1)) }
                    val len = Regex("(?i)Content-Length: (\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
                    var have = text.length - text.indexOf("\r\n\r\n") - 4
                    while (have < len) { val n = input.read(buf); if (n < 0) break; text.append(String(buf, 0, n, Charsets.ISO_8859_1)); have += n }
                    seen.complete(text.toString())
                    s.getOutputStream().write(raw); s.getOutputStream().flush()
                }
            } catch (e: Exception) { seen.completeExceptionally(e) }
        }.apply { isDaemon = true; start() }
        return seen
    }

    @Test fun contentLengthChunkedAndCloseDelimitedRepliesAndCookiesParse(): Unit = runBlocking {
        val json = """{"id":1,"result":true,"error":null}"""
        val cl = "HTTP/1.1 200 OK\r\nSet-Cookie: _session_id=abcdef123456; Path=/\r\nContent-Length: ${json.length}\r\n\r\n$json"
        val seen = serveOnce(cl.toByteArray())
        val r1 = DelugeReadOnlyRpc.HttpWire.exchange(req("""{"method":"a"}""".toByteArray(), "_session_id=zzzzzzzz"))
        assertEquals(200, r1.status); assertEquals(json, r1.body)
        assertEquals(listOf("_session_id=abcdef123456; Path=/"), r1.header("set-cookie"))
        val head = seen.get(5, TimeUnit.SECONDS)
        assertTrue(head, head.startsWith("POST /w/json HTTP/1.1\r\n"))
        assertTrue(head, head.contains("Cookie: _session_id=zzzzzzzz\r\n"))
        assertTrue(head, head.endsWith("""{"method":"a"}"""))

        val half = json.length / 2
        val chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "%x\r\n%s\r\n%x;ext=1\r\n%s\r\n0\r\n\r\n".format(half, json.substring(0, half), json.length - half, json.substring(half))
        serveOnce(chunked.toByteArray())
        assertEquals(json, DelugeReadOnlyRpc.HttpWire.exchange(req("x".toByteArray())).body)

        serveOnce("HTTP/1.1 200 OK\r\n\r\n$json".toByteArray())
        assertEquals(json, DelugeReadOnlyRpc.HttpWire.exchange(req("x".toByteArray())).body)

        serveOnce("HTTP/1.1 302 Found\r\nLocation: http://evil.example/\r\nContent-Length: 0\r\n\r\n".toByteArray())
        val redirect = DelugeReadOnlyRpc.HttpWire.exchange(req("x".toByteArray()))
        assertEquals("a redirect is returned, never followed", 302, redirect.status); assertEquals("", redirect.body)
    }

    @Test fun truncatedOversizedAndCompressedRepliesAreTransportFailures(): Unit = runBlocking {
        for (raw in listOf(
            "HTTP/1.1 200 OK\r\nContent-Length: 50\r\n\r\nshort",
            "HTTP/1.1 200 OK\r\nContent-Length: ${DelugeReadOnlyRpc.MAX_BODY + 1}\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 2\r\n\r\nzz",
            "garbage\r\n\r\n")) {
            serveOnce(raw.toByteArray())
            try { DelugeReadOnlyRpc.HttpWire.exchange(req("x".toByteArray())); fail("expected failure for $raw") }
            catch (e: java.io.IOException) { assertEquals("Deluge RPC transport failed", e.message) }
        }
        assertEquals(0, DelugeReadOnlyRpc.HttpWire.inFlight)
    }

    @Test fun aStalledWriteWithoutAnyCancelEndsAtTheStallDeadline(): Unit = runBlocking {
        // A peer that accepts but never reads. Before the socket-level wire the write had no timeout at all.
        val accepted = CompletableFuture<Socket>()
        Thread { accepted.complete(server.accept()) }.apply { isDaemon = true; start() }
        val t0 = System.nanoTime()
        val failure = withTimeout(40_000) {
            try { DelugeReadOnlyRpc.HttpWire.exchange(req(ByteArray(64 * 1024 * 1024))); null }
            catch (e: java.io.IOException) { e }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("CHUNKS_DELUGE_WIRE_STALLED_WRITE: ended_ms=$ms")
        assertTrue("stalled write must fail with a transport failure", failure != null)
        assertTrue("bounded by the 15 s stall deadline, took $ms", ms in 14_000..24_000)
        assertEquals(0, DelugeReadOnlyRpc.HttpWire.inFlight)
        accepted.get().close()
    }

    @Test fun httpsToAServerWithAnUntrustedCertificateNeverSendsTheBody(): Unit = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("wire-tls").toFile()
        try {
            val ks = File(dir, "ks.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "a", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", ks.path, "-storepass", "changeit").redirectErrorStream(true).start()
            assertEquals(0, p.waitFor())
            val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
            val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            val tlsServer = ctx.serverSocketFactory.createServerSocket(0, 5, loopback)
            val sawPlaintext = CompletableFuture<Int>()
            Thread {
                try {
                    (tlsServer.accept() as javax.net.ssl.SSLSocket).use { s ->
                        s.soTimeout = 3_000
                        try { sawPlaintext.complete(s.getInputStream().read(ByteArray(4096))) }
                        catch (_: Exception) { sawPlaintext.complete(-1) }
                    }
                } catch (_: Exception) { sawPlaintext.complete(-1) }
            }.apply { isDaemon = true; start() }
            try {
                DelugeReadOnlyRpc.HttpWire.exchange(DelugeReadOnlyRpc.Request(
                    java.net.URI("https://127.0.0.1:${tlsServer.localPort}/w/json"), """{"secret":"pw"}""".toByteArray(), null))
                fail("an untrusted certificate must be refused")
            } catch (e: java.io.IOException) { assertEquals("Deluge RPC transport failed", e.message) }
            assertTrue("no application data may reach an unverified peer", sawPlaintext.get(5, TimeUnit.SECONDS) <= 0)
            tlsServer.close()
        } finally { dir.deleteRecursively() }
    }
}
