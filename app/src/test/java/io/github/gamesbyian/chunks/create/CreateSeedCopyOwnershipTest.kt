package io.github.gamesbyian.chunks.create

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Disposable real-engine checks for exclusive copy ownership and non-recursive cleanup. */
class CreateSeedCopyOwnershipTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()

    @Before fun loadNative() = DesktopLibtorrent.ensureLoaded()
    @After fun close() { engines.forEach { it.close() } }

    private fun engine(): LocalTorrentEngine = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0",
        allowMultipleConnectionsPerIp = true
    )).also { it.start(); engines += it }

    private fun request(name: String, entryName: String, size: Int = 130_001):
        TorrentMetainfoBuilder.Request {
        val bytes = ByteArray(size) { (it % 251).toByte() }
        return TorrentMetainfoBuilder.Request(name, listOf(
            TorrentMetainfoBuilder.Entry(listOf(entryName), bytes.size.toLong()) {
                ByteArrayInputStream(bytes)
            }
        ))
    }

    @Test fun foreignPartFileBesideSingleFileIsNeverTruncatedOrDeleted() {
        val root = temp.newFolder("single-copy")
        val unrelated = File(root, "one.bin.chunks-part")
        val bytes = byteArrayOf(7, 4, 2, 0, 6)
        unrelated.writeBytes(bytes)
        val req = request("one.bin", "one.bin", 24_009)
        val expected = TorrentMetainfoBuilder.build(req)

        val result = CreateSeedCopy.copyVerifyAndSeed(req, expected, root, engine())
        assertTrue("A fresh destination should seed independently: $result",
            result is CreateSeedCopy.Outcome.Seeding)
        assertArrayEquals("Unrelated staging-style filename must be untouched",
            bytes, unrelated.readBytes())
        assertEquals(24_009L, File(root, "one.bin").length())
    }

    @Test fun cancellationDoesNotRecursivelyDeleteForeignFilePlantedDuringCopy() {
        val root = temp.newFolder("foreign-created-during-copy")
        val req = request("Shared set", "one.bin")
        val expected = TorrentMetainfoBuilder.build(req)
        val foreign = File(root, "Shared set/foreign.bin")
        val original = byteArrayOf(1, 3, 3, 7)
        var cancelled = false
        val result = CreateSeedCopy.copyVerifyAndSeed(
            req, expected, root, engine(),
            onProgress = {
                if (!cancelled) {
                    foreign.writeBytes(original)
                    cancelled = true
                }
            },
            isCancelled = { cancelled }
        )
        assertTrue(result is CreateSeedCopy.Outcome.Refused)
        assertTrue((result as CreateSeedCopy.Outcome.Refused).reason.startsWith("Cancelled"))
        assertArrayEquals("Foreign bytes must survive cancellation", original,
            foreign.readBytes())
        assertFalse("Only the owned partial file is deleted",
            File(root, "Shared set/one.bin").exists())
        assertTrue("A directory with foreign entries must not be recursively deleted",
            File(root, "Shared set").isDirectory)
    }

    @Test fun cancelledCopyDoesNotDeleteForeignReplacementOfOpenOutputFile() {
        val root = temp.newFolder("output-identity-replacement")
        val req = request("Shared set", "one.bin", 130_001)
        val expected = TorrentMetainfoBuilder.build(req)
        val output = File(root, "Shared set/one.bin")
        val foreign = byteArrayOf(6, 2, 6, 4, 3, 3)
        var replaced = false
        val result = CreateSeedCopy.copyVerifyAndSeed(
            req, expected, root, engine(),
            onProgress = {
                if (!replaced) {
                    // The channel for the original inode is still open. Another
                    // actor removes that path and installs unrelated regular bytes.
                    assertTrue(Files.deleteIfExists(output.toPath()))
                    output.writeBytes(foreign)
                    replaced = true
                }
            },
            isCancelled = { replaced }
        )
        assertTrue("Fixture must execute replacement", replaced)
        assertTrue("Cancellation must refuse: $result",
            result is CreateSeedCopy.Outcome.Refused)
        assertArrayEquals("Path replacement belongs to another actor; never delete it",
            foreign, output.readBytes())
        assertTrue("Leave the folder for explicit manual review", output.parentFile.isDirectory)
        assertTrue((result as CreateSeedCopy.Outcome.Refused).reason.contains("manual review"))
    }

    @Test fun danglingSymlinkDestinationIsOccupiedEvenWhenFileExistsReturnsFalse() {
        val root = temp.newFolder("dangling-target-root")
        val absent = File(temp.root, "does-not-exist")
        val alias = File(root, "Shared set")
        Files.createSymbolicLink(alias.toPath(), absent.toPath())
        assertFalse(alias.exists())
        val req = request("Shared set", "one.bin")
        val expected = TorrentMetainfoBuilder.build(req)
        val result = CreateSeedCopy.copyVerifyAndSeed(req, expected, root, engine())
        assertTrue(result is CreateSeedCopy.Outcome.Refused)
        assertTrue((result as CreateSeedCopy.Outcome.Refused).reason.contains("already"))
        assertTrue(Files.isSymbolicLink(alias.toPath()))
        assertFalse(absent.exists())
    }

    @Test fun swappedDestinationDirectoryCannotRedirectCopyOrCleanupToForeignFiles() {
        val root = temp.newFolder("reparent-during-copy")
        val external = temp.newFolder("reparent-unrelated")
        val foreign = File(external, "one.bin")
        val unrelated = byteArrayOf(9, 9, 1, 1, 9)
        foreign.writeBytes(unrelated)
        val first = ByteArray(80_000) { 7 }
        val second = ByteArray(4_096) { 8 }
        val req = TorrentMetainfoBuilder.Request("Shared set", listOf(
            TorrentMetainfoBuilder.Entry(listOf("one.bin"), first.size.toLong()) {
                ByteArrayInputStream(first)
            },
            TorrentMetainfoBuilder.Entry(listOf("two.bin"), second.size.toLong()) {
                ByteArrayInputStream(second)
            }
        ))
        val expected = TorrentMetainfoBuilder.build(req)
        val target = File(root, "Shared set")
        val ownMoved = File(root, "held-owned-folder")
        var swapped = false
        val result = CreateSeedCopy.copyVerifyAndSeed(
            req, expected, root, engine(),
            onProgress = {
                if (!swapped) {
                    Files.move(target.toPath(), ownMoved.toPath())
                    Files.createSymbolicLink(target.toPath(), external.toPath())
                    swapped = true
                }
            }
        )
        assertTrue(swapped)
        assertTrue(result is CreateSeedCopy.Outcome.Refused)
        assertTrue("A replaced parent must not be deleted", Files.isSymbolicLink(target.toPath()))
        assertArrayEquals("The original unrelated bytes must remain unchanged",
            unrelated, foreign.readBytes())
        assertFalse(File(external, "two.bin").exists())
        assertTrue(ownMoved.isDirectory)
    }

    @Test fun sourceGrowthAfterCreationNeverWritesBeyondReviewedLength() {
        val root = temp.newFolder("source-grown-after-hashing")
        val keep = File(root, "keep.txt")
        keep.writeText("other person's file")
        val checked = ByteArray(1024) { 1 }
        var providerBytes = checked
        val req = TorrentMetainfoBuilder.Request("Shared set", listOf(
            TorrentMetainfoBuilder.Entry(listOf("episode.bin"), checked.size.toLong()) {
                ByteArrayInputStream(providerBytes)
            }
        ))
        val expected = TorrentMetainfoBuilder.build(req)
        providerBytes = ByteArray(65_536) { 2 }
        val result = CreateSeedCopy.copyVerifyAndSeed(req, expected, root, engine())
        assertTrue(result is CreateSeedCopy.Outcome.Refused)
        assertFalse(File(root, "Shared set").exists())
        assertEquals("other person's file", keep.readText())
    }

    @Test fun sourceTruncationAfterCreationRemovesOnlyOwnedPartialBytes() {
        val root = temp.newFolder("source-short-after-hashing")
        val checked = ByteArray(8_192) { 9 }
        var providerBytes = checked
        val req = TorrentMetainfoBuilder.Request("Shared set", listOf(
            TorrentMetainfoBuilder.Entry(listOf("episode.bin"), checked.size.toLong()) {
                ByteArrayInputStream(providerBytes)
            }
        ))
        val expected = TorrentMetainfoBuilder.build(req)
        providerBytes = ByteArray(1024) { 9 }
        val result = CreateSeedCopy.copyVerifyAndSeed(req, expected, root, engine())
        assertTrue(result is CreateSeedCopy.Outcome.Refused)
        assertFalse(File(root, "Shared set").exists())
    }

    @Test fun invalidPathComponentRefusesBeforeCreatingAnyFile() {
        val root = temp.newFolder("invalid-relative-name")
        val valid = request("Shared set", "one.bin")
        val expected = TorrentMetainfoBuilder.build(valid)
        val invalid = TorrentMetainfoBuilder.Request("Shared set", listOf(
            TorrentMetainfoBuilder.Entry(listOf("..", "foreign.bin"), 1L) {
                ByteArrayInputStream(byteArrayOf(9))
            }
        ))
        val result = CreateSeedCopy.copyVerifyAndSeed(invalid, expected, root, engine())
        assertTrue(result is CreateSeedCopy.Outcome.Refused)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }
}
