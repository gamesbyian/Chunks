package io.github.gamesbyian.chunks.engine

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Random
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/**
 * BEP 19 web seeds: the engine fetches pieces over HTTP(S) Range requests and
 * verifies them against the torrent's piece hashes. No BitTorrent peers needed.
 */
class LocalEngineWebSeedTest {
    @get:Rule val temp = TemporaryFolder()
    private var engine: LocalTorrentEngine? = null

    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engine?.close() }

    private fun start() = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0"))
        .also { it.start(); engine = it }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for web seed download")
            Thread.sleep(200)
        }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * A native complete/seeding state can precede libtorrent's asynchronous
     * disk-cache flush. A separately computed SHA-256 is only valid evidence
     * once this exact torrent emits CACHE_FLUSHED.
     */
    private fun requireStableCompletedBytes(engine: LocalTorrentEngine, hash: String) {
        waitFor(30_000) {
            engine.status(hash)?.let {
                it.progressPpm == 1_000_000 &&
                    it.state in setOf(LocalTransferState.SEEDING, LocalTransferState.FINISHED)
            } == true
        }
        assertTrue("Independently readable bytes require exact-hash native cache flush",
            engine.flushCompletedForLibraryRead(hash, timeoutMs = 30_000))
    }

    /** Minimal HTTP server with single-range support, as BEP 19 clients require. */
    private fun serve(bytes: ByteArray): HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
        createContext("/media/sample.ogg") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            val start = match?.groupValues?.get(1)?.toInt() ?: 0
            val end = match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: (bytes.size - 1)
            val body = bytes.copyOfRange(start, minOf(end, bytes.size - 1) + 1)
            if (match != null) exchange.responseHeaders.set("Content-Range", "bytes $start-${start + body.size - 1}/${bytes.size}")
            exchange.sendResponseHeaders(if (match != null) 206 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    @Test fun downloadsAndVerifiesPiecesFromAnHttpWebSeedAlone() {
        val payload = ByteArray(300_000).also { Random(9).nextBytes(it) }
        val source = File(temp.newFolder("source"), "sample.ogg").apply { writeBytes(payload) }
        val server = serve(payload)
        try {
            val torrent = TorrentBuilder().path(source).pieceSize(32 * 1024)
                .addUrlSeed("http://127.0.0.1:${server.address.port}/media/sample.ogg")
                .generate().entry().bencode()
            val destination = temp.newFolder("destination")
            val engine = start()
            val added = (engine.addStopped(torrent, destination) as LocalAddResult.AddedStopped).status
            assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(added.infoHash).outcome)
            waitFor(60_000) { engine.status(added.infoHash)?.progressPpm == 1_000_000 }
            requireStableCompletedBytes(engine, added.infoHash)
            assertEquals(sha256(payload), sha256(File(destination, "sample.ogg").readBytes()))
        } finally {
            server.stop(0)
        }
    }

    /**
     * Opt-in real Internet evidence: a deterministic torrent built by
     * tools/public_fixture_collector.py from a licensed Wikimedia Commons file,
     * whose only source is the official HTTPS upload URL as a web seed.
     */
    @Test fun downloadsLicensedCommonsMediaFromItsOfficialWebSeed() {
        val torrentPath = System.getenv("CHUNKS_PUBLIC_WEBSEED_TORRENT").orEmpty()
        val expectedSha256 = System.getenv("CHUNKS_PUBLIC_WEBSEED_SHA256").orEmpty()
        val expectedName = System.getenv("CHUNKS_PUBLIC_WEBSEED_NAME").orEmpty()
        assumeTrue("Only in the manual public web-seed workflow", torrentPath.isNotBlank())
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")) && expectedName.isNotBlank())
        val destination = temp.newFolder("public destination")
        val engine = start()
        val added = (engine.addStopped(File(torrentPath).readBytes(), destination) as LocalAddResult.AddedStopped).status
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(added.infoHash).outcome)
        waitFor(180_000) { engine.status(added.infoHash)?.progressPpm == 1_000_000 }
        requireStableCompletedBytes(engine, added.infoHash)
        val finished = requireNotNull(engine.status(added.infoHash))
        assertFalse("Start was acknowledged but libtorrent is still paused", finished.stopped)
        // allTimeDownload may exclude HTTP web-seed payload; completed wanted
        // bytes and independent SHA-256 are the authoritative file evidence.
        assertTrue("External web seed must have wanted bytes", finished.wantedBytes > 0L)
        assertEquals("Full payload must be piece-verified on disk",
            finished.wantedBytes, finished.doneBytes)
        assertEquals(expectedSha256, sha256(File(destination, expectedName).readBytes()))
        println("CHUNKS_LOCAL_ENGINE_WEBSEED: verified ${added.infoHash} sha256=$expectedSha256")
    }
}
