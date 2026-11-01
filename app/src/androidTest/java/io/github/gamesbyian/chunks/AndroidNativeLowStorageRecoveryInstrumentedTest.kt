package io.github.gamesbyian.chunks

import android.os.Build
import android.os.StatFs
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Opt-in DISPOSABLE-EMULATOR native low-space trial (#5/#569).
 *
 * Real allocated filesystem blocks, real Android libtorrent sessions and
 * actual hash/readback. No fake disk errors, sparse-only filler, production
 * user directories, global cleanup or whole-device acceptance claim.
 *
 * Storage pressure deliberately affects the emulator's app-private /data
 * volume. The harness must enforce emulator identity before installation;
 * this class separately refuses execution without its opt-in instrument arg.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNativeLowStorageRecoveryInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val mib = 1_048_576L
    private val targetFree = 16L * mib
    private val maxFill = 1_440L * mib

    private fun available(path: File) = StatFs(path.absolutePath).availableBytes
    private fun sha(path: File): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(path.readBytes())

    private fun waitFor(label: String, seconds: Int = 100, predicate: () -> Boolean) {
        val end = System.nanoTime() + seconds * 1_000_000_000L
        while (!predicate()) {
            if (System.nanoTime() >= end) fail("Native low-space timeout: $label")
            Thread.sleep(200)
        }
    }

    /**
     * Real posix_fallocate allocates ext4 blocks, unlike seek/truncate sparse
     * files. One test-owned descriptor only; never fill more than maxFill,
     * never touch external Downloads, and always release in finally.
     */
    private fun pressure(root: File, ballast: File): Pair<Long, Long> {
        val initial = available(root)
        assertTrue(
            "Bounded fixture needs smaller disposable AVD partition, initial free=$initial",
            initial > targetFree && initial <= maxFill + targetFree
        )
        FileOutputStream(ballast).use { output ->
            var reserved = 0L
            while (available(root) > targetFree && reserved < maxFill) {
                val gap = available(root) - targetFree
                val block = minOf(32L * mib, gap, maxFill - reserved)
                if (block < 4096L) break
                Os.posix_fallocate(output.fd, reserved, block)
                reserved += block
            }
            output.fd.sync()
            val allocatedBlocks = Os.stat(ballast.absolutePath).st_blocks * 512L
            assertTrue("No independent real-block evidence: reserved=$reserved allocated=$allocatedBlocks",
                reserved > 0L && ballast.length() >= reserved &&
                    allocatedBlocks >= reserved - 4096L)
        }
        val remaining = available(root)
        Log.i("ChunksLowSpace", "CHUNKS_LOW_SPACE_PRESSURE baseline=$initial " +
            "free=$remaining payload_required=${48L * mib} max_fill=$maxFill")
        assertTrue(
            "No actual low-space condition: $remaining free >= 48MiB payload; not acceptance",
            remaining in 0 until 48L * mib
        )
        return initial to remaining
    }

    @Test fun realDiskPressureKeepsNativeProgressHonestThenRecoversPeerPayload() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Only the opt-in low-space emulator harness may run",
            args.getString("chunksLowSpace") == "1" && Build.VERSION.SDK_INT >= 35)
        assertTrue("Disk-filling fixture must never run on a physical device",
            Build.HARDWARE in setOf("ranchu", "goldfish") ||
                Build.FINGERPRINT.startsWith("generic"))

        val root = File(context.filesDir, "native-low-space-${System.nanoTime()}").apply {
            assertTrue("Fresh private fixture required", mkdir())
        }
        val source = File(root, "source").apply { assertTrue(mkdir()) }
        val target = File(root, "target").apply { assertTrue(mkdir()) }
        val ballast = File(root, "fallocate-ballast.bin")
        val payload = ByteArray(48 * 1_048_576).also { Random(994571L).nextBytes(it) }
        val seedFile = File(source, "low-space-public-fixture.bin").apply {
            writeBytes(payload)
        }
        val torrent = TorrentBuilder().path(seedFile).pieceSize(64 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val cfg = LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true)
        val seeder = LocalTorrentEngine(cfg)
        val leecher = LocalTorrentEngine(cfg)
        try {
            assertEquals("Fixture must use actual same-volume native backing",
                Os.stat(source.absolutePath).st_dev, Os.stat(target.absolutePath).st_dev)
            seeder.start()
            leecher.start()
            val seed = seeder.addStopped(torrent, source) as LocalAddResult.AddedStopped
            assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.status.infoHash).outcome)
            waitFor("native seeder has checked fixture pieces") {
                seeder.status(seed.status.infoHash)?.state == LocalTransferState.SEEDING
            }
            val added = leecher.addStopped(torrent, target,
                listOf(InetSocketAddress("127.0.0.1", seeder.listenPort())))
                as LocalAddResult.AddedStopped
            assertTrue(added.status.stopped)
            assertFalse("Native .torrent cannot begin in upload-only mode", added.status.uploadOnly)
            assertFalse("No files downloaded before explicit Start",
                File(target, seedFile.name).exists())

            val (_, pressuredFree) = pressure(root, ballast)
            val attempted = leecher.resume(added.status.infoHash)
            // A native Start ACK merely means libtorrent unpaused. Disk-full
            // errors may arrive asynchronously; native progress must not lie.
            val startAccepted = attempted.outcome == LocalCommandOutcome.VERIFIED
            assertTrue("Unexpected native Start outcome under real storage pressure: $attempted",
                startAccepted || attempted.outcome == LocalCommandOutcome.REFUSED)
            if (startAccepted) {
                waitFor("actual native low-space error/stopped state", 50) {
                    val now = leecher.status(added.status.infoHash)
                    now?.stopped == true || now?.uploadOnly == true ||
                        !now?.error.isNullOrBlank()
                }
            }
            val pressureState = requireNotNull(leecher.status(added.status.infoHash))
            assertTrue("Cannot claim 100% complete with only $pressuredFree physical free bytes",
                pressureState.progressPpm < 1_000_000)
            assertTrue("Must expose disk error, native upload-only or stopped/refused transfer",
                pressureState.stopped || pressureState.uploadOnly ||
                    !pressureState.error.isNullOrBlank() || !startAccepted)
            Log.i("ChunksLowSpace", "CHUNKS_LOW_SPACE_BLOCKED free=$pressuredFree " +
                "state=${pressureState.state} stopped=${pressureState.stopped} " +
                "upload_only=${pressureState.uploadOnly} error_present=${!pressureState.error.isNullOrBlank()} " +
                "progress_ppm=${pressureState.progressPpm}")

            // Free only the test-owned ballast. No OS-wide cache cleanup and
            // no automatic torrent payload removal. Explicit Stop/Start is a
            // user-facing recovery operation, not a blind mutation retry.
            assertTrue("Could not release owned pressure ballast", ballast.delete())
            waitFor("capacity recovered") { available(root) > 64L * mib }
            if (!requireNotNull(leecher.status(added.status.infoHash)).stopped)
                assertEquals(LocalCommandOutcome.VERIFIED,
                    leecher.pause(added.status.infoHash).outcome)
            assertTrue(requireNotNull(leecher.status(added.status.infoHash)).stopped)
            assertEquals(LocalCommandOutcome.VERIFIED,
                leecher.resume(added.status.infoHash).outcome)
            waitFor("fresh peer-pieces after low-space recovery", 120) {
                leecher.status(added.status.infoHash)?.progressPpm == 1_000_000
            }
            assertTrue("Completed native payload not cache-flushed",
                leecher.flushCompletedForLibraryRead(added.status.infoHash, 30_000L))
            val downloaded = File(target, seedFile.name)
            assertTrue(downloaded.isFile)
            assertEquals(payload.size.toLong(), downloaded.length())
            assertArrayEquals("Independent SHA-256 must match real peer bytes",
                MessageDigest.getInstance("SHA-256").digest(payload), sha(downloaded))
            assertArrayEquals("Seeding source must not be altered by storage pressure",
                MessageDigest.getInstance("SHA-256").digest(payload), sha(seedFile))
            assertEquals(LocalCommandOutcome.VERIFIED,
                leecher.pause(added.status.infoHash).outcome)
            assertTrue(leecher.removeKeepingData(added.status.infoHash))
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(payload),
                sha(downloaded))
            Log.i("ChunksLowSpace", "CHUNKS_LOW_SPACE_RECOVERED " +
                "bytes=${payload.size} sha_equal=true stopped=true removed_keep_files=true")
        } finally {
            // Release pressure first even if native recheck, assertions or
            // exceptions fail. The disposable emulator is torn down by CI.
            if (ballast.exists()) ballast.delete()
            try { leecher.close() } finally { seeder.close() }
            root.deleteRecursively()
        }
    }
}
