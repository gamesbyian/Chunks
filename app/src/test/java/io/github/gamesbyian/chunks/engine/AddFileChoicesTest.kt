package io.github.gamesbyian.chunks.engine

import io.github.gamesbyian.chunks.AddFileChoices
import java.io.File
import java.net.InetSocketAddress
import java.util.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/**
 * Universal Add "Files to download": libtorrent's own declared files, matched to the
 * registered job by native index and path, and applied while stopped. Real libtorrent,
 * loopback seeder: a file skipped at Add is never downloaded; the chosen one is exact.
 */
class AddFileChoicesTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()
    private val piece = 64 * 1024

    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
        allowMultipleConnectionsPerIp = true)).also { it.start(); engines += it }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for engine state")
            Thread.sleep(100)
        }
    }

    /** Two piece-aligned files, so skipping one shares no piece with the other. */
    private fun fixture(): Triple<File, ByteArray, Map<String, ByteArray>> {
        val root = File(temp.newFolder("seed"), "Pack").apply { mkdirs() }
        val files = linkedMapOf(
            "a.bin" to ByteArray(10 * piece).also { Random(1).nextBytes(it) },
            "b.bin" to ByteArray(10 * piece).also { Random(2).nextBytes(it) })
        for ((name, bytes) in files) File(root, name).writeBytes(bytes)
        return Triple(root, TorrentBuilder().path(root).pieceSize(piece).generate().entry().bencode(), files)
    }

    @Test fun declaredFilesComeFromLibtorrentsOwnView() {
        val (_, torrent, files) = fixture()
        val declared = requireNotNull(AddFileChoices.declared(torrent))
        assertEquals(listOf("Pack/a.bin", "Pack/b.bin"), declared.map { it.path })
        assertEquals(files.values.map { it.size.toLong() }, declared.map { it.sizeBytes })
        assertNull(AddFileChoices.declared("not bencode".toByteArray()))
    }

    @Test fun reviewedSkipsRequireTheSameIndexAndPath() {
        val job = listOf(LocalTransferFile(0, "Pack/a.bin", 1, 0, true), LocalTransferFile(1, "Pack/b.bin", 1, 0, true))
        assertEquals(listOf(job[1]), AddFileChoices.reviewedSkips(job, mapOf(1 to "Pack/b.bin")))
        assertNull("a different path at that index is not what was reviewed",
            AddFileChoices.reviewedSkips(job, mapOf(1 to "Pack/other.bin")))
        assertNull(AddFileChoices.reviewedSkips(job, mapOf(5 to "Pack/b.bin")))
        assertEquals(emptyList<LocalTransferFile>(), AddFileChoices.reviewedSkips(job, emptyMap()))
        assertTrue(AddFileChoices.outcomeNote(LocalFileBatchOutcome.UNCERTAIN, 1, startRequested = true)!!.contains("not started"))
        assertTrue(!AddFileChoices.outcomeNote(LocalFileBatchOutcome.UNCERTAIN, 1, startRequested = false)!!.contains("not started"))
        assertNull(AddFileChoices.outcomeNote(null, 0, startRequested = true))
    }

    @Test fun aFileSkippedAtAddIsNeverDownloadedWhileTheChosenOneIsExact() {
        val (root, torrent, files) = fixture()
        val seeder = engine()
        val seed = (seeder.addStopped(torrent, root.parentFile) as LocalAddResult.AddedStopped).status
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.infoHash).outcome)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        val destination = temp.newFolder("dest")
        val leecher = engine()
        val job = (leecher.addStoppedChecked(torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))) as LocalAddResult.AddedStopped).status
        val declared = requireNotNull(AddFileChoices.declared(torrent))
        val skips = declared.filter { it.path.endsWith("b.bin") }.associate { it.index to it.path }
        val reviewed = requireNotNull(AddFileChoices.reviewedSkips(requireNotNull(leecher.files(job.infoHash)), skips))
        assertEquals(LocalFileBatchOutcome.VERIFIED, leecher.setFilesWantedBatch(job.infoHash, reviewed, false))

        assertEquals(LocalCommandOutcome.VERIFIED, leecher.resume(job.infoHash).outcome)
        waitFor { leecher.files(job.infoHash)?.firstOrNull { it.path.endsWith("a.bin") }?.let { it.doneBytes == it.sizeBytes } == true }
        assertTrue(leecher.flushCompletedForLibraryRead(job.infoHash, timeoutMs = 30_000L))
        assertArrayEquals(files.getValue("a.bin"), File(destination, "Pack/a.bin").readBytes())
        Thread.sleep(1_500)
        val skipped = File(destination, "Pack/b.bin")
        assertTrue("the skipped file was not written: ${skipped.length()}", !skipped.exists() || skipped.length() == 0L)
        val b = requireNotNull(leecher.files(job.infoHash)).first { it.path.endsWith("b.bin") }
        assertEquals("no skipped bytes verified", 0L, b.doneBytes)
        assertTrue("nothing beyond the chosen file was downloaded: ${leecher.status(job.infoHash)}",
            requireNotNull(leecher.status(job.infoHash)).payloadDownloadedBytes <= files.getValue("a.bin").size + piece)
    }
}
