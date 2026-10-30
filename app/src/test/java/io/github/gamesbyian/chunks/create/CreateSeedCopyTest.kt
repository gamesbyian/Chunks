package io.github.gamesbyian.chunks.create

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Copy → verify → seed with the real engine, and an independent peer downloads it byte-exact. */
class CreateSeedCopyTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()
    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
        allowMultipleConnectionsPerIp = true)).also { it.start(); engines += it }

    private val payload = mapOf("one.bin" to 70_001, "sub/two.bin" to 33_000).mapValues { (_, n) -> ByteArray(n).also(Random(9)::nextBytes) }
    private fun request(changed: Boolean = false) = TorrentMetainfoBuilder.Request("Shared set", payload.map { (path, bytes) ->
        val data = if (changed && path == "one.bin") bytes.copyOf().also { it[0] = (it[0] + 1).toByte() } else bytes
        TorrentMetainfoBuilder.Entry(path.split('/'), data.size.toLong()) { ByteArrayInputStream(data) }
    })

    @Test fun seedsAVerifiedCopyThatAnotherPeerDownloadsExactly() {
        val req = request()
        val created = TorrentMetainfoBuilder.build(req)
        val root = temp.newFolder("place")
        val seeder = engine()
        val outcome = CreateSeedCopy.copyVerifyAndSeed(req, created, root, seeder)
        assertTrue("$outcome", outcome is CreateSeedCopy.Outcome.Seeding)
        assertArrayEquals(payload.getValue("sub/two.bin"), File(root, "Shared set/sub/two.bin").readBytes())
        assertTrue(File(root, "Shared set").walk().none { it.name.endsWith(".chunks-part") })

        val leecher = engine()
        val target = temp.newFolder("peer")
        val hash = (leecher.addStopped(created.torrent, target,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))) as LocalAddResult.AddedStopped).status.infoHash
        leecher.resume(hash)
        val deadline = System.currentTimeMillis() + 60_000
        while (leecher.status(hash)?.state != LocalTransferState.SEEDING) {
            assertTrue("peer did not finish", System.currentTimeMillis() < deadline); Thread.sleep(100)
        }
        // A native SEEDING state can precede asynchronous disk-cache flush.
        // The independent peer's payload is accepted only after this exact
        // handle has acknowledged CACHE_FLUSHED, then byte-for-byte readback.
        assertTrue("Native leecher never confirmed a completed cache flush",
            leecher.flushCompletedForLibraryRead(hash, timeoutMs = 20_000L))
        for ((path, bytes) in payload) {
            val downloaded = File(target, "Shared set/$path")
            assertTrue("Native peer reported completion but file is missing: " +
                downloaded.absolutePath, downloaded.isFile)
            assertArrayEquals("Native peer bytes mismatch at " + downloaded.absolutePath,
                bytes, downloaded.readBytes())
        }
    }

    @Test fun neverOverwritesAndRefusesFilesThatChangedAfterCreation() {
        val root = temp.newFolder("place2")
        File(root, "Shared set").mkdirs()
        val created = TorrentMetainfoBuilder.build(request())
        val existing = CreateSeedCopy.copyVerifyAndSeed(request(), created, root, engine())
        assertTrue((existing as CreateSeedCopy.Outcome.Refused).reason.contains("already"))

        val fresh = temp.newFolder("place3")
        val changed = CreateSeedCopy.copyVerifyAndSeed(request(changed = true), created, fresh, engine())
        assertTrue((changed as CreateSeedCopy.Outcome.Refused).reason.contains("changed"))
    }

    @Test fun replacedEarlierFileCannotBeReadViaSymlinkDuringHashRebuild() {
        val req = request()
        val created = TorrentMetainfoBuilder.build(req)
        val root = temp.newFolder("replace-earlier-output")
        val foreign = File(temp.newFolder("foreign-source"), "private.bin")
        // Identical bytes deliberately defeat an ordinary hash-mismatch
        // refusal. The result must be rejected because the source file was
        // replaced, even though the rebuilt torrent would have the same hash.
        val untouched = payload.getValue("one.bin").copyOf()
        foreign.writeBytes(untouched)
        val copied = File(root, "Shared set/one.bin").toPath()
        val displaced = File(root, "Shared set/owned-moved-aside.bin").toPath()
        var swapped = false
        val result = CreateSeedCopy.copyVerifyAndSeed(req, created, root, engine(),
            onProgress = { completed ->
                if (!swapped && completed > payload.getValue("one.bin").size) {
                    assertTrue("First output must already exist before the second file",
                        Files.isRegularFile(copied))
                    Files.move(copied, displaced)
                    Files.createSymbolicLink(copied, foreign.toPath())
                    swapped = true
                }
            })
        assertTrue("Fixture must exercise the replacement", swapped)
        assertTrue("$result", result is CreateSeedCopy.Outcome.Refused)
        assertTrue((result as CreateSeedCopy.Outcome.Refused).reason.contains("changed"))
        assertTrue(Files.isSymbolicLink(copied))
        assertTrue("Foreign file contents must remain intact",
            untouched.contentEquals(foreign.readBytes()))
        assertTrue(Files.isRegularFile(displaced))
    }

    @Test fun cancellingKeepsThePartialCopyForManualReview() {
        val root = temp.newFolder("place4")
        File(root, "keep.txt").writeText("mine")
        val out = CreateSeedCopy.copyVerifyAndSeed(request(), TorrentMetainfoBuilder.build(request()), root, engine(),
            isCancelled = { true })
        assertTrue((out as CreateSeedCopy.Outcome.Refused).reason.startsWith("Cancelled"))
        assertTrue(File(root, "Shared set").isDirectory)
        assertTrue(out.reason.contains("manual review"))
        assertEquals("mine", File(root, "keep.txt").readText())
    }
}
