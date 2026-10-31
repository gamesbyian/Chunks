package io.github.gamesbyian.chunks.engine

import java.io.File
import java.net.InetSocketAddress
import java.util.Random
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

class LocalCheckedAddCollisionNativeTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()
    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun close() { engines.forEach { it.close() } }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true
    )).also { it.start(); engines += it }

    @Test fun existingRegularPayloadFileIsRecheckedAndReplacedByRealTorrentPeer() {
        val root = File(temp.newFolder("source"), "Known payload").apply { mkdirs() }
        val expected = ByteArray(128 * 1024).also { Random(568L).nextBytes(it) }
        File(root, "same-path.bin").writeBytes(expected)
        val bytes = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()

        val seeder = engine()
        val source = seeder.addStopped(bytes, root.parentFile) as LocalAddResult.AddedStopped
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(source.status.infoHash).outcome)
        val seedDeadline = System.nanoTime() + 30_000_000_000L
        while (seeder.status(source.status.infoHash)?.state != LocalTransferState.SEEDING &&
            System.nanoTime() < seedDeadline) Thread.sleep(100)
        assertEquals(LocalTransferState.SEEDING, seeder.status(source.status.infoHash)?.state)

        val destination = temp.newFolder("destination")
        val payload = File(destination, "Known payload/same-path.bin")
        assertTrue(payload.parentFile.mkdirs())
        val different = ByteArray(expected.size).also { Random(9876L).nextBytes(it) }
        payload.writeBytes(different)
        val sibling = File(destination, "unrelated-user-data.bin")
        sibling.writeBytes(different)
        val receiver = engine()
        val result = receiver.addStoppedChecked(bytes, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort())))
        assertTrue(result.toString(), result is LocalAddResult.AddedStopped)
        val hash = (result as LocalAddResult.AddedStopped).status.infoHash
        assertArrayEquals("Stopped Add does not begin downloading", different, payload.readBytes())
        assertEquals(LocalCommandOutcome.VERIFIED, receiver.resume(hash).outcome)
        val deadline = System.nanoTime() + 60_000_000_000L
        while (receiver.status(hash)?.progressPpm != 1_000_000 &&
            System.nanoTime() < deadline) Thread.sleep(100)
        assertEquals("Expected all torrent pieces verified", 1_000_000,
            receiver.status(hash)?.progressPpm)
        assertTrue(receiver.flushCompletedForLibraryRead(hash, timeoutMs = 30_000L))
        assertArrayEquals(expected, payload.readBytes())
        assertArrayEquals("An unmentioned sibling may never be written", different, sibling.readBytes())
        assertArrayEquals(expected, File(root, "same-path.bin").readBytes())
    }

    @Test fun cleanTargetStillAddsStoppedWithoutMaterializingPayload() {
        val root = File(temp.newFolder("empty-source"), "Fixture").apply { mkdirs() }
        File(root, "file.bin").writeBytes(ByteArray(64 * 1024) { (it % 239).toByte() })
        val bytes = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
        val target = temp.newFolder("empty-target")
        val receiver = engine()
        assertTrue(receiver.addStoppedChecked(bytes, target) is LocalAddResult.AddedStopped)
        assertEquals(1, receiver.list().size)
        assertTrue(receiver.list().single().stopped)
        assertFalse(File(target, "Fixture/file.bin").exists())
    }
}
