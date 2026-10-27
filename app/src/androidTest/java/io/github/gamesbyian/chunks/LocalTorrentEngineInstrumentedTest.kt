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
import java.util.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * The Android libtorrent4j native library inside the app process on an emulator:
 * two real sessions exchange a locally generated payload over loopback only.
 * Not background/foreground-service, network-change or physical-device proof.
 */
@RunWith(AndroidJUnit4::class)
class LocalTorrentEngineInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File
    private val engines = ArrayList<LocalTorrentEngine>()

    @Before fun setUp() {
        work = File(context.filesDir, "engine-test").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() {
        engines.forEach { it.close() }
        work.deleteRecursively()
    }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true)).also { it.start(); engines += it }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 90_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for local engine")
            Thread.sleep(100)
        }
    }

    /**
     * libtorrent may report SEEDING before its asynchronous disk write queue
     * becomes visible through an independent Java file read. Demand exact
     * bytes within a bounded interval and report any temporary mismatch;
     * never silently accept a persistent corrupt or missing payload.
     */
    private fun waitForVerifiedReadback(path: File, expected: ByteArray, phase: String) {
        val started = System.nanoTime()
        val deadline = started + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        var attempts = 0
        var firstMismatch: String? = null
        while (true) {
            attempts++
            val actual = try { path.takeIf { it.isFile }?.readBytes() } catch (_: Exception) { null }
            if (actual != null && actual.contentEquals(expected)) {
                if (attempts > 1) {
                    val durationMs = java.util.concurrent.TimeUnit.NANOSECONDS
                        .toMillis(System.nanoTime() - started)
                    println("CHUNKS_NATIVE_READBACK delayed phase=$phase durationMs=$durationMs " +
                        "attempts=$attempts firstMismatch=$firstMismatch")
                }
                return
            }
            if (firstMismatch == null) {
                val mismatch = actual?.indices?.firstOrNull { index ->
                    index >= expected.size || actual[index] != expected[index]
                } ?: if (actual?.size == expected.size) null else
                    minOf(actual?.size ?: 0, expected.size)
                firstMismatch = "size=${actual?.size ?: -1} offset=${mismatch ?: -1}"
            }
            if (System.nanoTime() >= deadline) {
                fail("Native $phase reached complete status but exact filesystem " +
                    "readback did not converge within 30s: $firstMismatch")
            }
            Thread.sleep(150)
        }
    }

    @Test fun androidNativeEngineDownloadsAndSeedsRealBytesOverLoopback() {
        val seedRoot = File(work, "seed/Chunks device fixture").apply { mkdirs() }
        val payload = ByteArray(900_000).also { Random(11).nextBytes(it) }
        File(seedRoot, "payload.bin").writeBytes(payload)
        File(seedRoot, "readme.txt").writeText("Generated on device for Chunks engine tests")
        val torrent = TorrentBuilder().path(seedRoot).pieceSize(32 * 1024).generate().entry().bencode()

        val seeder = engine()
        val seed = (seeder.addStopped(torrent, requireNotNull(seedRoot.parentFile)) as LocalAddResult.AddedStopped).status
        assertTrue(seed.stopped)
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.infoHash).outcome)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        val destination = File(work, "chosen").apply { mkdirs() }
        val leecher = engine()
        val leech = (leecher.addStopped(torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))) as LocalAddResult.AddedStopped).status
        assertEquals(seed.infoHash, leech.infoHash)
        assertEquals(LocalCommandOutcome.VERIFIED, leecher.resume(leech.infoHash).outcome)
        waitFor {
            val state = leecher.status(leech.infoHash)?.state
            state == LocalTransferState.SEEDING || state == LocalTransferState.FINISHED
        }
        val completedPayload = File(destination, "Chunks device fixture/payload.bin")
        // The earlier intermittent zero-byte mismatches appeared after libtorrent
        // reported SEEDING/FINISHED. Capture whether disk bytes were briefly
        // behind completion *before* explicitly asking libtorrent to flush.
        // This pre-flush observation is diagnostic, not a corruption verdict.
        val beforeFlush = try { completedPayload.takeIf { it.isFile }?.readBytes() }
            catch (_: Exception) { null }
        if (beforeFlush == null || !beforeFlush.contentEquals(payload)) {
            val mismatch = beforeFlush?.indices?.firstOrNull { index ->
                index >= payload.size || beforeFlush[index] != payload[index]
            } ?: minOf(beforeFlush?.size ?: 0, payload.size)
            println("CHUNKS_NATIVE_PRE_FLUSH pending size=${beforeFlush?.size ?: -1} firstMismatch=$mismatch")
        } else {
            println("CHUNKS_NATIVE_PRE_FLUSH already-exact")
        }
        val flushStarted = System.nanoTime()
        assertTrue("Native cache-flushed alert must confirm the leeching session's completed bytes",
            leecher.flushCompletedForLibraryRead(leech.infoHash, 20_000L))
        println("CHUNKS_NATIVE_FLUSH_CONFIRMED waitMs=" +
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - flushStarted))
        // A mismatch AFTER the native flush is more consequential than an
        // asynchronous pre-flush read. Exact bytes remain a hard requirement.
        waitForVerifiedReadback(completedPayload, payload, "after-native-cache-flush")
        waitFor { (seeder.status(seed.infoHash)?.payloadUploadedBytes ?: 0) >= payload.size }

        // Session-only removal keeps the downloaded bytes.
        assertTrue(leecher.removeKeepingData(leech.infoHash))
        waitForVerifiedReadback(File(destination, "Chunks device fixture/payload.bin"),
            payload, "after-session-only-removal")
    }

    @Test fun nativeCompletedTorrentFlushesDiskCacheBeforeIndependentLibraryRead() {
        val seedRoot = File(work, "flush/Flush fixture").apply { mkdirs() }
        val payload = ByteArray(95_000).also { Random(19).nextBytes(it) }
        File(seedRoot, "payload.bin").writeBytes(payload)
        val torrent = TorrentBuilder().path(seedRoot).pieceSize(16 * 1024).generate().entry().bencode()
        val instance = engine()
        val status = (instance.addStopped(torrent, requireNotNull(seedRoot.parentFile))
            as LocalAddResult.AddedStopped).status
        assertTrue(!instance.flushCompletedForLibraryRead("A".repeat(40), 100L))
        assertEquals(LocalCommandOutcome.VERIFIED, instance.resume(status.infoHash).outcome)
        waitFor { instance.status(status.infoHash)?.state == LocalTransferState.SEEDING }
        assertTrue("Cache-flushed native alert must arrive before external reads",
            instance.flushCompletedForLibraryRead(status.infoHash, 15_000L))
        assertArrayEquals(payload, File(seedRoot, "payload.bin").readBytes())
        assertTrue(instance.removeKeepingData(status.infoHash))
        assertTrue(!instance.flushCompletedForLibraryRead(status.infoHash, 100L))
        assertArrayEquals(payload, File(seedRoot, "payload.bin").readBytes())
    }

    @Test fun sessionOnlyRemovalCannotLeaveResumeFileForRestart() {
        val root = File(work, "remove-resume/Remove fixture").apply { mkdirs() }
        val original = File(root, "source.bin").apply {
            writeBytes(ByteArray(65_000).also { Random(61).nextBytes(it) })
        }
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
        val state = File(work, "remove-state").apply { mkdirs() }
        val config = LocalEngineConfig(listenInterfaces = "127.0.0.1:0", stateDirectory = state)
        val first = LocalTorrentEngine(config).also { it.start(); engines += it }
        val added = (first.addStopped(torrent, requireNotNull(root.parentFile))
            as LocalAddResult.AddedStopped).status

        // Initiate an asynchronous native save then immediately Remove.
        // A callback arriving after Remove must not recreate .resume.
        first.saveResumeData(timeoutMs = 0)
        assertTrue(first.removeKeepingData(added.infoHash))
        Thread.sleep(250)
        assertTrue(first.status(added.infoHash) == null)
        assertTrue(!File(state, added.infoHash.lowercase() + ".resume").exists())
        first.close()

        val second = LocalTorrentEngine(config).also { it.start(); engines += it }
        assertTrue(second.status(added.infoHash) == null)
        assertTrue(second.restoreProblems.isEmpty())
        assertTrue(original.isFile)
    }

    @Test fun androidEngineRestoresSavedTransfersStoppedAfterRestart() {
        val seedRoot = File(work, "persist/Restart fixture").apply { mkdirs() }
        File(seedRoot, "a.bin").writeBytes(ByteArray(70_000).also { Random(5).nextBytes(it) })
        val torrent = TorrentBuilder().path(seedRoot).pieceSize(16 * 1024).generate().entry().bencode()
        val state = File(work, "state").apply { mkdirs() }
        val config = LocalEngineConfig(listenInterfaces = "127.0.0.1:0", stateDirectory = state)

        val first = LocalTorrentEngine(config).also { it.start(); engines += it }
        val added = (first.addStopped(torrent, requireNotNull(seedRoot.parentFile)) as LocalAddResult.AddedStopped).status
        assertEquals(LocalCommandOutcome.VERIFIED, first.resume(added.infoHash).outcome)
        waitFor { first.status(added.infoHash)?.state == LocalTransferState.SEEDING }
        first.close()

        val second = LocalTorrentEngine(config).also { it.start(); engines += it }
        assertTrue(second.restoreProblems.isEmpty())
        val restored = requireNotNull(second.status(added.infoHash))
        assertTrue(restored.stopped)
        waitFor { second.status(added.infoHash)?.progressPpm == 1_000_000 }
    }
}
