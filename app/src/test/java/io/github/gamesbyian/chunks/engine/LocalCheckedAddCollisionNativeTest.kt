package io.github.gamesbyian.chunks.engine

import java.io.File
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

    @Test fun foreignFileCollisionIsRefusedWithoutNativeRegistrationOrDataChanges() {
        val root = File(temp.newFolder("source"), "Known payload").apply { mkdirs() }
        val expected = ByteArray(128 * 1024).also { Random(568L).nextBytes(it) }
        File(root, "same-path.bin").writeBytes(expected)
        val bytes = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()

        val destination = temp.newFolder("destination")
        val foreign = File(destination, "Known payload/same-path.bin")
        assertTrue(foreign.parentFile.mkdirs())
        val unrelated = ByteArray(expected.size).also { Random(9876L).nextBytes(it) }
        foreign.writeBytes(unrelated)

        val receiver = engine()
        val refused = receiver.addStoppedChecked(bytes, destination)
        assertTrue(refused.toString(), refused is LocalAddResult.Refused)
        assertTrue(receiver.list().isEmpty())
        assertArrayEquals(unrelated, foreign.readBytes())

        // Separate explicit trusted-data seeding path still works.
        val seeder = engine()
        assertTrue(seeder.addStopped(bytes, root.parentFile) is LocalAddResult.AddedStopped)
        assertTrue(seeder.list().single().stopped)
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
