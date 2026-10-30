package io.github.gamesbyian.chunks.engine

import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/**
 * G1 diagnostic for an important shared Downloads identity boundary.
 *
 * This is NOT an acceptance assertion that pre-existing, unrelated files
 * are safe. It establishes libtorrent's observed behavior on a synthetic,
 * disposable path collision with real BitTorrent peer transfer and hashes.
 * Any observed replacement is recorded as RISK, not counted as a successful
 * production collision-protection feature.
 */
class LocalNativeCollisionProbeTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = mutableListOf<LocalTorrentEngine>()

    @Before fun loadNative() = DesktopLibtorrent.ensureLoaded()

    @After fun closeEngines() {
        engines.forEach { runCatching { it.close() } }
    }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true,
        enableDht = false, enableLsd = false, enablePortMapping = false
    )).also { it.start(); engines += it }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private fun waitFor(label: String, timeoutMs: Long = 90_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("Timed out waiting for $label")
            Thread.sleep(100)
        }
    }

    @Test fun realPeerCollisionRecordsOverwriteBehaviorWithoutEndangeringOutsideData() {
        val sourceRoot = File(temp.newFolder("source"), "Synthetic collision fixture").apply {
            assertTrue(mkdirs())
        }
        val expected = ByteArray(768 * 1024).also { Random(568L).nextBytes(it) }
        File(sourceRoot, "episode.bin").writeBytes(expected)
        val torrent = TorrentBuilder().path(sourceRoot).pieceSize(32 * 1024)
            .generate().entry().bencode()

        val seed = engine()
        val seedResult = seed.addStopped(torrent, sourceRoot.parentFile)
        assertTrue(seedResult.toString(), seedResult is LocalAddResult.AddedStopped)
        val hash = (seedResult as LocalAddResult.AddedStopped).status.infoHash
        assertEquals(LocalCommandOutcome.VERIFIED, seed.resume(hash).outcome)
        waitFor("real source seeding") {
            seed.status(hash)?.state == LocalTransferState.SEEDING
        }

        val destination = temp.newFolder("unrelated-existing-files")
        val collisionFile = File(destination, "Synthetic collision fixture/episode.bin")
        assertTrue(collisionFile.parentFile.mkdirs())
        val unrelated = ByteArray(expected.size).also { Random(9876L).nextBytes(it) }
        collisionFile.writeBytes(unrelated)
        val initialSha = sha(collisionFile.readBytes())
        val expectedSha = sha(expected)
        assertTrue("The pre-existing fixture must really differ", initialSha != expectedSha)

        val downloader = engine()
        val added = downloader.addStopped(torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seed.listenPort())))
        assertTrue(added.toString(), added is LocalAddResult.AddedStopped)
        val downloadHash = (added as LocalAddResult.AddedStopped).status.infoHash
        assertEquals(hash, downloadHash)
        // A user explicitly only authorized Add stopped at this point.
        assertArrayEquals("Stopped Add must not mutate a foreign collision file",
            unrelated, collisionFile.readBytes())

        assertEquals(LocalCommandOutcome.VERIFIED, downloader.resume(downloadHash).outcome)
        waitFor("real-peer transfer completion") {
            downloader.status(downloadHash)?.progressPpm == 1_000_000
        }
        assertTrue("Cache must flush before independent readback",
            downloader.flushCompletedForLibraryRead(downloadHash, 30_000L))
        val finalBytes = collisionFile.readBytes()
        val finalSha = sha(finalBytes)
        // Diagnostic, not a protection assertion. A replaced foreign file
        // here proves that production Add needs a separate reviewed collision
        // preflight/consent path before Start.
        println("CHUNKS_NATIVE_COLLISION initial_sha256=$initialSha expected_sha256=$expectedSha final_sha256=$finalSha")
        println("CHUNKS_NATIVE_COLLISION foreign_file_replaced=${!finalBytes.contentEquals(unrelated)}")
        println("CHUNKS_NATIVE_COLLISION expected_torrent_data_present=${finalBytes.contentEquals(expected)}")
        assertTrue("The controlled transfer must end with exact known bytes",
            finalBytes.contentEquals(unrelated) || finalBytes.contentEquals(expected))
        assertNotNull(downloader.status(downloadHash))
    }
}
