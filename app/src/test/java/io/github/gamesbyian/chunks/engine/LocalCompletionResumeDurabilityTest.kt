package io.github.gamesbyian.chunks.engine

import java.io.File
import java.net.InetSocketAddress
import java.net.JarURLConnection
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentHandle

/**
 * #5 regression (API29 cold restore at 0% with a complete public payload,
 * runs 41709749599 / 41769126807). Phase 1 downloads a real loopback torrent
 * to 100% and then dies WITHOUT Stop, save or close, like an Android process
 * kill before the post-Stop checkpoint commits. Phase 2 is a fresh process
 * that restores the same resume directory. A complete payload must restore as
 * complete; the zero-piece Add snapshot alone would restore it as 0%.
 */
class LocalCompletionResumeDurabilityTest {
    @Test fun completedDownloadSurvivesProcessDeathBeforeStopCheckpoint() {
        val root = Files.createTempDirectory("chunks-finished-resume-").toFile()
        try {
            val first = runPhase(root, "download-then-die")
            assertTrue(first, first.contains("FINISHED_RESUME: phase1 complete"))
            val second = runPhase(root, "restore")
            assertTrue(second, second.contains("FINISHED_RESUME: restored"))
            val line = second.lines().single { it.startsWith("FINISHED_RESUME: restored") }
            assertTrue("Restored complete payload lost its pieces: $line\n$first",
                line.contains("progressPpm=1000000"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runPhase(root: File, phase: String): String {
        val classpath = listOf(LocalCompletionResumeWitness::class.java,
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
            LocalCompletionResumeWitness::class.java.name, root.absolutePath, phase
        ).redirectErrorStream(true).redirectOutput(log).apply {
            val chaining = File(System.getProperty("java.home"), "lib/libjsig.so")
            if (chaining.isFile) environment().putIfAbsent("LD_PRELOAD", chaining.absolutePath)
        }.start()
        try {
            val finished = process.waitFor(40, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            val output = log.readText()
            assertTrue("Phase $phase did not finish: $output", finished)
            assertEquals(output, 0, process.exitValue())
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

/** Disposable loopback fixture only; no tracker, DHT, user paths or Android backend. */
object LocalCompletionResumeWitness {
    @JvmStatic fun main(args: Array<String>) {
        DesktopLibtorrent.ensureLoaded()
        val root = File(args[0])
        val state = File(root, "resume-state")
        val destination = File(root, "destination")
        val hashFile = File(root, "hash.txt")
        when (args[1]) {
            "download-then-die" -> {
                val source = File(root, "source").apply { check(mkdirs()) }
                val payload = File(source, "fixture.bin").apply {
                    writeBytes(ByteArray(256 * 1024) { (it * 31 % 251).toByte() })
                }
                val torrent = TorrentBuilder().path(payload).pieceSize(16 * 1024)
                    .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
                val seeder = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
                    allowMultipleConnectionsPerIp = true))
                seeder.start()
                val hash = (seeder.addStopped(torrent, source) as LocalAddResult.AddedStopped).status.infoHash
                check(seeder.resume(hash).outcome == LocalCommandOutcome.VERIFIED)
                await(10) { seeder.status(hash)?.state == LocalTransferState.SEEDING }
                check(destination.mkdirs())
                val phone = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
                    allowMultipleConnectionsPerIp = true, stateDirectory = state))
                phone.start()
                check(phone.addStopped(torrent, destination) is LocalAddResult.AddedStopped)
                val addGenerations = generations(state, hash)
                check(addGenerations >= 1) { "Add did not persist its initial record" }
                check(phone.resume(hash).outcome == LocalCommandOutcome.VERIFIED)
                check(phone.connectPeer(hash, InetSocketAddress("127.0.0.1", seeder.listenPort())))
                await(20) { phone.status(hash)?.progressPpm == 1_000_000 }
                hashFile.writeText(hash)
                // No Stop, saveResumeData() or close(): only what the engine
                // persisted by itself on completion may survive. Bounded wait;
                // a missing completion save leaves the count unchanged.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (generations(state, hash) <= addGenerations && System.nanoTime() < deadline)
                    Thread.sleep(20)
                println("FINISHED_RESUME: phase1 complete add_generations=$addGenerations " +
                    "now=${generations(state, hash)}")
                System.out.flush()
                Runtime.getRuntime().halt(0)
            }
            "restore" -> {
                val hash = hashFile.readText().trim()
                val phone = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
                    allowMultipleConnectionsPerIp = true, stateDirectory = state))
                phone.start()
                check(phone.restoreProblems.isEmpty()) { "restore problems ${phone.restoreProblems}" }
                // Restored jobs stay stopped; resume pieces are applied once
                // libtorrent finishes checking the resume record.
                await(10) { phone.status(hash)?.let { it.state != LocalTransferState.CHECKING } == true }
                val status = requireNotNull(phone.status(hash))
                println("FINISHED_RESUME: restored stopped=${status.stopped} " +
                    "progressPpm=${status.progressPpm} doneBytes=${status.doneBytes} state=${status.state}")
                System.out.flush()
                phone.close()
            }
            else -> error("unknown phase")
        }
    }

    private fun generations(directory: File, infoHash: String): Int {
        val expected = Regex(Regex.escape(infoHash.lowercase()) + """\.resume-event\.[0-9]{12}""")
        return directory.listFiles()?.count { it.isFile && expected.matches(it.name) } ?: 0
    }

    private fun await(seconds: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Native fixture did not reach the required state" }
            Thread.sleep(10)
        }
    }
}
