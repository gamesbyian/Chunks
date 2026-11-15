package io.github.gamesbyian.chunks.engine

import java.io.File
import java.net.InetSocketAddress
import java.net.JarURLConnection
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentHandle

/**
 * Process death part-way through a background download (#5, #1655).
 *
 * Phase 1 runs in a child JVM: a checked stopped Add, explicit Start, a real
 * throttled loopback seeder, and then `Runtime.halt` part-way through with
 * NO Stop, `saveResumeData()` or `close()` — what an Android low-memory kill
 * does to a foreground-service download while no screen is polling. Only
 * what the engine checkpointed by itself can survive.
 *
 * Phase 2 restores that state in this JVM. Before the engine-owned periodic
 * checkpoint existed, the newest durable record was the zero-piece Add
 * snapshot, so libtorrent trusted it and every downloaded piece was
 * discarded and fetched again. The restored job must keep (almost) all
 * pieces it had verified at least one checkpoint interval before the kill,
 * come back stopped, then finish to the exact source bytes while fetching
 * only the missing remainder.
 */
class LocalPartialResumeDurabilityTest {
    @Test fun partialBackgroundDownloadKeepsCheckpointedPiecesAcrossProcessDeath() {
        DesktopLibtorrent.ensureLoaded()
        val root = Files.createTempDirectory("chunks-partial-resume-").toFile()
        try {
            val first = runPhase(root, "partial-then-die")
            val marker = Regex("""PARTIAL_RESUME: phase1 checkpointed_done=(\d+) halted_done=(\d+) size=(\d+)""")
                .find(first) ?: throw AssertionError("Phase 1 did not report its progress\n$first")
            val (checkpointed, halted, size) = marker.destructured.toList().map { it.toLong() }
            assertTrue("Phase 1 must be interrupted part-way\n$first", halted < size && checkpointed > 0)
            val hash = File(root, "hash.txt").readText().trim()
            val payload = File(root, "source/fixture.bin").readBytes()
            val torrent = File(root, "fixture.torrent").readBytes()

            val restoredEngine = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
                allowMultipleConnectionsPerIp = true, stateDirectory = File(root, "resume-state")))
            restoredEngine.start()
            val seeder = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
                allowMultipleConnectionsPerIp = true))
            seeder.start()
            try {
                assertTrue(restoredEngine.restoreProblems.toString(), restoredEngine.restoreProblems.isEmpty())
                await(10) { restoredEngine.status(hash)?.state != LocalTransferState.CHECKING }
                val restored = requireNotNull(restoredEngine.status(hash)) { "Restored job missing\n$first" }
                assertTrue("Restored jobs never start on their own", restored.stopped)
                // Pieces in flight across the last checkpoint may be lost; a
                // restore from the zero-piece Add snapshot loses all of them.
                assertTrue("Restored ${restored.doneBytes} of $size lost pieces verified before the last " +
                    "checkpoint ($checkpointed)\n$first", restored.doneBytes >= checkpointed - 4L * PIECE)
                assertTrue("Restored progress cannot exceed what was on disk at the kill",
                    restored.doneBytes <= halted)
                Thread.sleep(1_000)
                val idle = requireNotNull(restoredEngine.status(hash))
                assertTrue(idle.stopped)
                assertEquals("A stopped restored job writes nothing", restored.doneBytes, idle.doneBytes)

                val seed = (seeder.addStopped(torrent, File(root, "source")) as LocalAddResult.AddedStopped).status
                assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.infoHash).outcome)
                await(10) { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }
                assertEquals(LocalCommandOutcome.VERIFIED, restoredEngine.resume(hash).outcome)
                assertTrue(restoredEngine.connectPeer(hash, InetSocketAddress("127.0.0.1", seeder.listenPort())))
                await(60) { restoredEngine.status(hash)?.progressPpm == 1_000_000 }
                assertTrue(restoredEngine.flushCompletedForLibraryRead(hash, timeoutMs = 30_000L))
                assertArrayEquals("Resumed payload must equal the independent source bytes",
                    payload, File(root, "destination/fixture.bin").readBytes())
                Thread.sleep(1_500)
                val fetched = requireNotNull(restoredEngine.status(hash)).payloadDownloadedBytes -
                    idle.payloadDownloadedBytes
                assertTrue("Fetched $fetched again after restoring ${restored.doneBytes} of $size",
                    fetched <= size - restored.doneBytes + 8L * PIECE)
            } finally {
                seeder.close()
                restoredEngine.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runPhase(root: File, phase: String): String {
        val classpath = listOf(LocalPartialResumeWitness::class.java,
            LocalTorrentEngine::class.java, LocalResumeSaveRequests::class.java,
            DesktopLibtorrent::class.java, TorrentHandle::class.java, Unit::class.java).map {
            File(it.protectionDomain.codeSource.location.toURI()).absolutePath
        }.toMutableList()
        val native = DesktopLibtorrent::class.java.classLoader
            .getResources("lib/x86_64/libtorrent4j.so").toList()
            .single { it.toString().contains("libtorrent4j-linux") }
        classpath += File((native.openConnection() as JarURLConnection).jarFileURL.toURI()).absolutePath
        val log = File(root, "$phase.log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp", classpath.distinct().joinToString(File.pathSeparator),
            LocalPartialResumeWitness::class.java.name, root.absolutePath, phase
        ).redirectErrorStream(true).redirectOutput(log).apply {
            val chaining = File(System.getProperty("java.home"), "lib/libjsig.so")
            if (chaining.isFile) environment().putIfAbsent("LD_PRELOAD", chaining.absolutePath)
        }.start()
        try {
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            val output = log.readText()
            assertTrue("Phase $phase did not finish: $output", finished)
            assertEquals(output, 0, process.exitValue())
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun await(seconds: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Native fixture did not reach the required state" }
            Thread.sleep(20)
        }
    }

    internal companion object { const val PIECE = 16 * 1024 }
}

/** Disposable loopback fixture only; no tracker, DHT, user paths or Android backend. */
object LocalPartialResumeWitness {
    private const val CHECKPOINT_MS = 400L

    @JvmStatic fun main(args: Array<String>) {
        DesktopLibtorrent.ensureLoaded()
        val root = File(args[0])
        check(args[1] == "partial-then-die") { "unknown phase" }
        val source = File(root, "source").apply { check(mkdirs()) }
        val payload = File(source, "fixture.bin").apply {
            writeBytes(ByteArray(2 * 1024 * 1024) { (it * 37 % 251).toByte() })
        }
        val torrent = TorrentBuilder().path(payload).pieceSize(LocalPartialResumeDurabilityTest.PIECE)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        File(root, "fixture.torrent").writeBytes(torrent)
        val seeder = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true, rateLimitLocalPeers = true))
        seeder.start()
        seeder.setRateLimits(LocalRateLimits(uploadBytesPerSecond = 160 * 1024))
        val seedHash = (seeder.addStopped(torrent, source) as LocalAddResult.AddedStopped).status.infoHash
        check(seeder.resume(seedHash).outcome == LocalCommandOutcome.VERIFIED)
        await(10) { seeder.status(seedHash)?.state == LocalTransferState.SEEDING }

        val destination = File(root, "destination").apply { check(mkdirs()) }
        val local = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true, stateDirectory = File(root, "resume-state"),
            resumeCheckpointIntervalMs = CHECKPOINT_MS))
        local.start()
        val hash = (local.addStoppedChecked(torrent, destination) as LocalAddResult.AddedStopped).status.infoHash
        File(root, "hash.txt").writeText(hash)
        check(local.resume(hash).outcome == LocalCommandOutcome.VERIFIED)
        check(local.connectPeer(hash, InetSocketAddress("127.0.0.1", seeder.listenPort())))
        val size = payload.length()
        await(30) { (local.status(hash)?.doneBytes ?: 0L) >= size / 4 }
        val checkpointed = requireNotNull(local.status(hash)).doneBytes
        // Keep downloading for several checkpoint intervals, then die.
        Thread.sleep(CHECKPOINT_MS * 5)
        val halted = requireNotNull(local.status(hash)).doneBytes
        println("PARTIAL_RESUME: phase1 checkpointed_done=$checkpointed halted_done=$halted size=$size")
        System.out.flush()
        Runtime.getRuntime().halt(0)
    }

    private fun await(seconds: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Native fixture did not reach the required state" }
            Thread.sleep(10)
        }
    }
}
