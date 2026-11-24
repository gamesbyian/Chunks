package io.github.gamesbyian.chunks.engine

import android.database.sqlite.SQLiteDatabase
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetSocketAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Installed Android two-process witness for the engine-owned resume
 * checkpoint (Android libtorrent4j + the SQLite resume database + real Os
 * fsync, unlike the JVM event-store tier). Owned by
 * `tools/installed_partial_resume_death.sh`; ordinary shards skip it.
 *
 * Phase `download-then-die`: a real throttled loopback seeder and a stopped
 * checked Add started explicitly, then `Process.killProcess` part-way through
 * with NO Stop, save or close — Android's low-memory kill. Phase `restore`:
 * a fresh app process restores the same disposable state directory; the job
 * must come back stopped with the pieces verified before the last checkpoint,
 * then finish to the exact source bytes. Only files under this test's own
 * app-private directory are created or removed.
 */
@RunWith(AndroidJUnit4::class)
class LocalPartialResumeProcessDeathInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("chunks_resume_death_phase")
    private val root get() = File(context.filesDir, "partial-resume-death-witness")
    private val receipt get() = File(root, "receipt.txt")

    private fun await(seconds: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Native fixture did not reach the required state" }
            Thread.sleep(20)
        }
    }

    private fun payload() = ByteArray(2 * 1024 * 1024) { (it * 37 % 251).toByte() }

    private fun liveHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    @Test fun downloadThenDie() {
        assumeTrue("Owned by installed_partial_resume_death.sh", phase == "download-then-die")
        root.deleteRecursively()
        val source = File(root, "source").apply { check(mkdirs()) }
        val file = File(source, "fixture.bin").apply { writeBytes(payload()) }
        val torrent = TorrentBuilder().path(file).pieceSize(PIECE).flags(TorrentBuilder.V1_ONLY)
            .generate().entry().bencode()
        val seeder = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true, rateLimitLocalPeers = true))
        seeder.start()
        seeder.setRateLimits(LocalRateLimits(uploadBytesPerSecond = 160 * 1024))
        val seed = (seeder.addStopped(torrent, source) as LocalAddResult.AddedStopped).status.infoHash
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed).outcome)
        await(20) { seeder.status(seed)?.state == LocalTransferState.SEEDING }

        val destination = File(root, "destination").apply { check(mkdirs()) }
        val local = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true, stateDirectory = File(root, "state"),
            resumeCheckpointIntervalMs = CHECKPOINT_MS))
        local.start()
        val hash = (local.addStoppedChecked(torrent, destination) as LocalAddResult.AddedStopped).status.infoHash
        assertEquals(LocalCommandOutcome.VERIFIED, local.resume(hash).outcome)
        assertTrue(local.connectPeer(hash, InetSocketAddress("127.0.0.1", seeder.listenPort())))
        val size = file.length()
        await(60) { (local.status(hash)?.doneBytes ?: 0L) >= size / 4 }
        val checkpointed = requireNotNull(local.status(hash)).doneBytes
        Thread.sleep(CHECKPOINT_MS * 5)
        val halted = requireNotNull(local.status(hash)).doneBytes
        check(halted < size) { "The fixture finished before the kill; nothing was interrupted" }
        receipt.writeText("$hash $checkpointed $halted $size\n")
        android.util.Log.i("ChunksResumeDeath", "CHUNKS_RESUME_DEATH: killing checkpointed=$checkpointed halted=$halted size=$size")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    @Test fun restoreKeepsCheckpointedPieces() {
        assumeTrue("Owned by installed_partial_resume_death.sh", phase == "restore")
        val (hash, checkpointed, halted, size) = receipt.readText().trim().split(" ").let {
            listOf(it[0], it[1], it[2], it[3])
        }
        val state = File(root, "state")
        val local = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true, stateDirectory = state))
        val seeder = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true))
        val pssBeforeKiB = Debug.getPss()
        val heapBefore = liveHeapBytes()
        val startedAt = SystemClock.elapsedRealtimeNanos()
        local.start()
        val engineStartNanos = SystemClock.elapsedRealtimeNanos() - startedAt
        val heapAfter = liveHeapBytes()
        val pssAfterKiB = Debug.getPss()
        seeder.start()
        try {
            assertTrue(local.restoreProblems.toString(), local.restoreProblems.isEmpty())
            await(20) { local.status(hash)?.state != LocalTransferState.CHECKING }
            val restored = requireNotNull(local.status(hash))
            assertTrue("Restored jobs never start on their own", restored.stopped)
            assertTrue("Restored ${restored.doneBytes} of $size lost pieces verified before the last checkpoint " +
                "($checkpointed)", restored.doneBytes >= checkpointed.toLong() - 4L * PIECE)
            assertTrue(restored.doneBytes <= halted.toLong())
            // Read only the aggregate from actual persisted native state, after
            // the timed engine open. Never print identities or filesystem paths.
            val database = File(File(state, LocalResumeDatabase.DIRECTORY), LocalResumeDatabase.DATABASE)
            SQLiteDatabase.openDatabase(database.path, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { readback ->
                readback.rawQuery("SELECT COUNT(*), SUM(length(payload)) FROM resumes WHERE phase = 'SAVED'", null)
                    .use { row ->
                        check(row.moveToFirst())
                        val savedRows = row.getInt(0)
                        val bytes = row.getLong(1)
                        assertEquals("The real checkpoint stores one native job", 1, savedRows)
                        assertTrue("The checkpoint must contain a native resume payload", bytes > 0)
                        android.util.Log.i("ChunksResumeDeath", "CHUNKS_RESUME_DEATH: storage saved_rows=$savedRows " +
                            "payload_bytes=$bytes engine_start_ns=$engineStartNanos java_heap_before=$heapBefore " +
                            "java_heap_after=$heapAfter pss_before_kib=$pssBeforeKiB pss_after_kib=$pssAfterKiB")
                    }
            }
            android.util.Log.i("ChunksResumeDeath", "CHUNKS_RESUME_DEATH: restored=${restored.doneBytes} " +
                "checkpointed=$checkpointed halted=$halted size=$size")

            val torrent = requireNotNull(local.exportTorrentFile(hash))
            val seed = (seeder.addStopped(torrent, File(root, "source")) as LocalAddResult.AddedStopped).status.infoHash
            assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed).outcome)
            await(20) { seeder.status(seed)?.state == LocalTransferState.SEEDING }
            assertEquals(LocalCommandOutcome.VERIFIED, local.resume(hash).outcome)
            assertTrue(local.connectPeer(hash, InetSocketAddress("127.0.0.1", seeder.listenPort())))
            await(90) { local.status(hash)?.progressPpm == 1_000_000 }
            assertTrue(local.flushCompletedForLibraryRead(hash, timeoutMs = 30_000L))
            assertArrayEquals("Resumed payload equals the independent source bytes",
                payload(), File(root, "destination/fixture.bin").readBytes())
            android.util.Log.i("ChunksResumeDeath", "CHUNKS_RESUME_DEATH: verified=true")
        } finally {
            seeder.close()
            local.close()
            root.deleteRecursively()
        }
    }

    private companion object {
        const val PIECE = 16 * 1024
        const val CHECKPOINT_MS = 400L
    }
}
