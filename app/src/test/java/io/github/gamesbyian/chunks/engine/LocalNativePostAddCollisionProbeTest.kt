package io.github.gamesbyian.chunks.engine

import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/**
 * G1 independent diagnostic: files introduced AFTER Add but BEFORE Start can
 * collide with the torrent's still-empty destination. All bytes and peers are
 * synthetic, loopback-only, in a JUnit TemporaryFolder.
 *
 * This is NOT protection acceptance: the raw/native addStopped path is used
 * deliberately to characterize behavior underneath reviewed production Add.
 * A positive replacement witness documents a dangerous lower-level default;
 * it does not authorize any user-facing overwrite.
 */
class LocalNativePostAddCollisionProbeTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = mutableListOf<LocalTorrentEngine>()

    @Before fun loadNative() = DesktopLibtorrent.ensureLoaded()

    @After fun closeEngines() {
        engines.forEach { runCatching { it.close() } }
    }

    private fun engine(): LocalTorrentEngine = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0",
        allowMultipleConnectionsPerIp = true,
        enableDht = false,
        enableLsd = false,
        enablePortMapping = false
    )).also { it.start(); engines += it }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private fun await(label: String, timeoutMs: Long = 90_000L, condition: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() >= end) fail("Timed out waiting for $label")
            Thread.sleep(100)
        }
    }

    @Test fun newForeignFileAfterStoppedAddIsAnUnprotectedStartTimeCollision() {
        // Construct a real, valid v1 torrent whose payload is known exactly.
        val sourceRoot = File(temp.newFolder("source"), "Timing fixture").apply {
            assertTrue(mkdirs())
        }
        val expected = ByteArray(768 * 1024).also { Random(568L).nextBytes(it) }
        File(sourceRoot, "payload.bin").writeBytes(expected)
        val metainfo = TorrentBuilder().path(sourceRoot).pieceSize(32 * 1024)
            .generate().entry().bencode()

        val seed = engine()
        val seedAdded = seed.addStopped(metainfo, sourceRoot.parentFile)
        assertTrue(seedAdded.toString(), seedAdded is LocalAddResult.AddedStopped)
        val infoHash = (seedAdded as LocalAddResult.AddedStopped).status.infoHash
        assertTrue(seed.resume(infoHash).outcome == LocalCommandOutcome.VERIFIED)
        await("native seeder") {
            seed.status(infoHash)?.state == LocalTransferState.SEEDING
        }

        // The exact path is EMPTY when the job is registered stopped. A
        // source-safe Add preflight would observe no collision here.
        val destination = temp.newFolder("empty-at-add")
        val foreign = File(destination, "Timing fixture/payload.bin")
        assertFalse(foreign.exists())
        val receiver = engine()
        val add = receiver.addStopped(metainfo, destination,
            listOf(InetSocketAddress("127.0.0.1", seed.listenPort())))
        assertTrue(add.toString(), add is LocalAddResult.AddedStopped)
        assertTrue((add as LocalAddResult.AddedStopped).status.stopped)
        assertFalse("Add stopped must not have materialized the target", foreign.exists())

        // Another process/user can create the same filename while Chunks
        // waits for explicit Start. Equal size prevents trivially detecting
        // it by file size alone; contents have a distinct independent hash.
        assertTrue(foreign.parentFile.isDirectory || foreign.parentFile.mkdirs())
        val unrelated = ByteArray(expected.size).also { Random(9876L).nextBytes(it) }
        foreign.writeBytes(unrelated)
        val before = sha(foreign.readBytes())
        val sourceSha = sha(expected)
        assertFalse(before == sourceSha)
        assertArrayEquals("No mutation before Start", unrelated, foreign.readBytes())

        val start = receiver.resume(infoHash)
        assertTrue(start.toString(), start.outcome == LocalCommandOutcome.VERIFIED)
        await("native peer transfer completion") {
            receiver.status(infoHash)?.progressPpm == 1_000_000
        }
        assertTrue("Flush before independent backing-file readback",
            receiver.flushCompletedForLibraryRead(infoHash, 30_000L))

        val afterBytes = foreign.readBytes()
        val after = sha(afterBytes)
        val replaced = !afterBytes.contentEquals(unrelated)
        val expectedPresent = afterBytes.contentEquals(expected)
        println("CHUNKS_POST_ADD_COLLISION before_sha256=$before expected_sha256=$sourceSha after_sha256=$after")
        println("CHUNKS_POST_ADD_COLLISION foreign_file_replaced=$replaced")
        println("CHUNKS_POST_ADD_COLLISION expected_torrent_data_present=$expectedPresent")
        // Characterization only. A future native implementation that
        // preserves the foreign bytes is safer and should not fail this test.
        assertTrue("Only synthetic foreign or exact fixture bytes may remain",
            afterBytes.contentEquals(unrelated) || expectedPresent)
        assertTrue("The untouched source seeding file must remain intact",
            File(sourceRoot, "payload.bin").readBytes().contentEquals(expected))
    }
}
