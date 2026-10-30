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

/** Run the real native lock cycle in a child JVM so a regression fails boundedly. */
class LocalMetadataShutdownTest {
    @Test fun metadataCallbackDoesNotDeadlockEngineClose() {
        val root = Files.createTempDirectory("chunks-metadata-close-").toFile()
        val classpath = listOf(LocalMetadataShutdownWitness::class.java,
            LocalTorrentEngine::class.java, LocalResumeSaveRequests::class.java,
            DesktopLibtorrent::class.java, TorrentHandle::class.java, Unit::class.java).map {
            File(it.protectionDomain.codeSource.location.toURI()).absolutePath
        }.toMutableList()
        val native = DesktopLibtorrent::class.java.classLoader
            .getResources("lib/x86_64/libtorrent4j.so").toList()
            .single { it.toString().contains("libtorrent4j-linux") }
        classpath += File((native.openConnection() as JarURLConnection).jarFileURL.toURI()).absolutePath
        val log = File(root, "witness.log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp", classpath.distinct().joinToString(File.pathSeparator),
            LocalMetadataShutdownWitness::class.java.name, root.absolutePath
        ).redirectErrorStream(true).redirectOutput(log).apply {
            val chaining = File(System.getProperty("java.home"), "lib/libjsig.so")
            if (chaining.isFile) environment().putIfAbsent("LD_PRELOAD", chaining.absolutePath)
        }.start()
        try {
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            val output = log.readText()
            assertTrue("Native metadata/close cycle did not finish: $output", finished)
            assertEquals(output, 0, process.exitValue())
            assertTrue(output, output.contains("METADATA_CLOSE: callback_ready=processed"))
            assertTrue(output, output.contains("METADATA_CLOSE: close_completed"))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            root.deleteRecursively()
        }
    }
}

/** Disposable loopback torrents only; no tracker, DHT, user paths or Android backend. */
object LocalMetadataShutdownWitness {
    @JvmStatic fun main(args: Array<String>) {
        DesktopLibtorrent.ensureLoaded()
        val root = File(args.single())
        val source = File(root, "source").apply { check(mkdirs()) }
        val payload = File(source, "fixture.bin").apply {
            writeBytes(ByteArray(100_000) { (it % 251).toByte() })
        }
        val torrent = TorrentBuilder().path(payload).pieceSize(16 * 1024).generate().entry().bencode()
        val seeder = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true))
        seeder.start()
        val seed = (seeder.addStopped(torrent, source) as LocalAddResult.AddedStopped).status
        check(seeder.resume(seed.infoHash).outcome == LocalCommandOutcome.VERIFIED)
        await { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }
        val destination = File(root, "destination").apply { check(mkdirs()) }
        val leecher = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true, stateDirectory = File(root, "resume-state")))
        leecher.start()
        check(leecher.addMagnet("magnet:?xt=urn:btih:" + seed.infoHash, destination)
            is LocalAddResult.FetchingMetadata)
        synchronized(leecher) {
            check(leecher.connectPeer(seed.infoHash, InetSocketAddress("127.0.0.1", seeder.listenPort())))
            var processed = false
            // On the broken implementation the real native dispatcher blocks
            // on this monitor. The corrected callback must process metadata and
            // pause without needing it, before close joins the dispatcher.
            await {
                processed = leecher.status(seed.infoHash)?.let { it.metadataKnown && it.stopped } == true
                processed || Thread.getAllStackTraces().any { (thread, stack) ->
                    thread.state == Thread.State.BLOCKED &&
                        stack.any { it.methodName == "transitionMagnetMetadata" }
                }
            }
            println("METADATA_CLOSE: callback_ready=" + if (processed) "processed" else "blocked")
            leecher.close()
            check(File(root, "resume-state/" + seed.infoHash + ".resume").length() > 0L) {
                "Closing after metadata must preserve a nonempty resume witness"
            }
            println("METADATA_CLOSE: close_completed")
        }
        seeder.close()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Native fixture did not reach the required state" }
            Thread.sleep(10)
        }
    }
}
