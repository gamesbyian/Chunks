package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Actual Android JNI/libtorrent peer-wire and piece-validation acceptance.
 * Two independent native sessions exchange real bytes over emulator loopback.
 * No mock peer, tracker, DHT, external IP, real-world Internet or physical
 * handset is involved. This is strictly stronger than a green Compose card.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNativePeerPayloadInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun until(label: String, timeoutMs: Long = 90_000L, condition: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() >= end) fail("Timed out waiting for $label")
            Thread.sleep(100)
        }
    }

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test fun independentAndroidNativeSessionsExchangeHashVerifiedCompletePayload() {
        val base = File(context.cacheDir, "peer-proof-${System.nanoTime()}").apply { mkdirs() }
        val source = File(base, "seed").apply { mkdirs() }
        val target = File(base, "download").apply { mkdirs() }
        val contents = ByteArray(900_000).also { Random(71012L).nextBytes(it) }
        val file = File(source, "public-native-fixture.bin").apply { writeBytes(contents) }
        val torrent = TorrentBuilder().path(file).pieceSize(32 * 1024)
            .generate().entry().bencode()
        val seeder = LocalTorrentEngine(LocalEngineConfig(
            listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true))
        val leecher = LocalTorrentEngine(LocalEngineConfig(
            listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true))
        try {
            seeder.start()
            leecher.start()
            val seed = (seeder.addStopped(torrent, source)
                as LocalAddResult.AddedStopped).status
            assertTrue(seed.stopped)
            assertEquals(LocalCommandOutcome.VERIFIED,
                seeder.resume(seed.infoHash).outcome)
            until("seed became hash-verified") {
                seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING
            }
            assertTrue("Android libtorrent must bind a genuine loopback TCP port",
                seeder.listenPort() > 0)
            val added = (leecher.addStopped(torrent, target,
                listOf(InetSocketAddress("127.0.0.1", seeder.listenPort())))
                as LocalAddResult.AddedStopped).status
            assertTrue("Add is stopped until explicitly started", added.stopped)
            assertFalse("No destination bytes should already exist", File(target, file.name).exists())
            assertEquals(LocalCommandOutcome.VERIFIED,
                leecher.resume(added.infoHash).outcome)
            until("all pieces accepted by native Android engine") {
                leecher.status(added.infoHash)?.progressPpm == 1_000_000
            }
            until("complete engine status stable for independent filesystem read") {
                leecher.status(added.infoHash)?.let {
                    it.state == LocalTransferState.FINISHED ||
                        it.state == LocalTransferState.SEEDING
                } == true
            }
            assertTrue("No independent SHA proof without native CACHE_FLUSHED",
                leecher.flushCompletedForLibraryRead(added.infoHash, 30_000L))
            val received = File(target, file.name)
            assertTrue("Android peer data must exist on real local filesystem", received.isFile)
            assertEquals(contents.size.toLong(), received.length())
            assertArrayEquals("Independent Android SHA-256 of all peer payload bytes",
                digest(contents), digest(received.readBytes()))
            val observed = requireNotNull(leecher.status(added.infoHash))
            assertEquals(observed.wantedBytes, observed.doneBytes)
            assertTrue(observed.wantedBytes > 0L)
            assertEquals(1_000_000, observed.progressPpm)
        } finally {
            leecher.close()
            seeder.close()
            base.deleteRecursively()
        }
    }
}
