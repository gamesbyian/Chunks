package io.github.gamesbyian.chunks.engine

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Host portability and actual file/failure state, not an Android-native writer
 * acceptance test. All paths live inside TemporaryFolder's exclusively minted
 * test sandbox. No public Downloads, peer traffic or user files.
 */
class ImmutableNativeResumeV2Test {
    @get:Rule val sandbox = TemporaryFolder()
    private val hash = "a".repeat(40)
    private val otherHash = "b".repeat(40)
    private val first = "first exact native resume bytes".toByteArray()
    private val second = "second exact native resume bytes".toByteArray()
    private fun state(): Path = sandbox.newFolder("private-native-state").toPath()
    private fun events(root: Path, target: String = hash): List<Path> =
        Files.list(root.resolve("immutable-resume-v2")).use { stream ->
            stream.filter { it.fileName.toString().startsWith("$target.r2.") }
                .toList().sortedBy { it.fileName.toString() }
        }
    private fun active(root: Path): ImmutableNativeResumeV2.Inspection.Active =
        ImmutableNativeResumeV2.inspect(root, hash) as ImmutableNativeResumeV2.Inspection.Active

    @Test fun newEpochAndMultipleSnapshotsNeverModifyAnEarlierNamedGeneration() {
        val root = state()
        assertTrue(ImmutableNativeResumeV2.inspect(root, hash) is
            ImmutableNativeResumeV2.Inspection.Absent)
        val epoch = ImmutableNativeResumeV2.beginReviewed(root, hash)
        assertEquals(epoch, active(root).epoch)
        assertNull(active(root).snapshot)
        ImmutableNativeResumeV2.save(root, hash, first)
        val originalFiles = events(root)
        val originalBytes = originalFiles.associateWith { Files.readAllBytes(it) }
        assertArrayEquals(first, active(root).snapshot)
        ImmutableNativeResumeV2.save(root, hash, second)
        assertArrayEquals(second, active(root).snapshot)
        assertEquals(3, events(root).size)
        for ((name, bytes) in originalBytes) {
            assertTrue("An immutable prior generation cannot vanish", Files.exists(name))
            assertArrayEquals("An older generation cannot be replaced", bytes, Files.readAllBytes(name))
        }
    }

    @Test fun durableRemovalIntentRefusesResurrectionAndExplicitNewReviewUsesNewEpoch() {
        val root = state()
        val originalEpoch = ImmutableNativeResumeV2.beginReviewed(root, hash)
        ImmutableNativeResumeV2.save(root, hash, first)
        val preserved = events(root).associateWith { Files.readAllBytes(it) }
        try {
            ImmutableNativeResumeV2.completeRemoval(root, hash)
            fail("Native remove completion cannot exist without durable request")
        } catch (_: IOException) { }
        ImmutableNativeResumeV2.requestRemoval(root, hash)
        assertTrue("A crash after native removal intent is NOT safe to restore",
            ImmutableNativeResumeV2.inspect(root, hash) is
                ImmutableNativeResumeV2.Inspection.Uncertain)
        try {
            ImmutableNativeResumeV2.save(root, hash, second)
            fail("A pending remove cannot admit more native resume data")
        } catch (_: IOException) { }
        ImmutableNativeResumeV2.completeRemoval(root, hash)
        assertEquals(originalEpoch, (ImmutableNativeResumeV2.inspect(root, hash) as
            ImmutableNativeResumeV2.Inspection.Removed).epoch)
        val newEpoch = ImmutableNativeResumeV2.beginReviewed(root, hash)
        assertNotEquals(originalEpoch, newEpoch)
        assertNull(active(root).snapshot)
        ImmutableNativeResumeV2.save(root, hash, second)
        assertArrayEquals(second, active(root).snapshot)
        for ((path, bytes) in preserved) assertArrayEquals(bytes, Files.readAllBytes(path))
    }

    @Test fun plantedForeignFinalNameAndTornGenerationRefuseWithoutTruncatingAnyBytes() {
        val root = state()
        ImmutableNativeResumeV2.beginReviewed(root, hash)
        val folder = root.resolve("immutable-resume-v2")
        val foreign = folder.resolve("$hash.r2.000002." + "b".repeat(32) + ".event")
        val canary = "unrelated foreign record must survive".toByteArray()
        Files.write(foreign, canary)
        assertTrue(ImmutableNativeResumeV2.inspect(root, hash) is
            ImmutableNativeResumeV2.Inspection.Uncertain)
        try {
            ImmutableNativeResumeV2.save(root, hash, first)
            fail("A foreign/incomplete generation must block further writes")
        } catch (_: IOException) { }
        assertArrayEquals(canary, Files.readAllBytes(foreign))
        assertEquals(2, events(root).size)
    }

    @Test fun substitutedSymlinkAndDuplicatedSequenceRefuseRecoveryReadOnly() {
        val root = state()
        ImmutableNativeResumeV2.beginReviewed(root, hash)
        ImmutableNativeResumeV2.save(root, hash, first)
        val folder = root.resolve("immutable-resume-v2")
        val original = events(root).first()
        val alias = folder.resolve("$hash.r2.000001." + "f".repeat(32) + ".event")
        Files.copy(original, alias)
        assertTrue("Two candidate sequence-1 records must never be accepted",
            ImmutableNativeResumeV2.inspect(root, hash) is
                ImmutableNativeResumeV2.Inspection.Uncertain)
        Files.delete(alias) // only the test's own copied fixture
        val foreign = root.resolve("outside-resume").toFile().apply { writeBytes(second) }
        val symlink = folder.resolve("$hash.r2.000003." + "d".repeat(32) + ".event")
        Files.createSymbolicLink(symlink, foreign.toPath())
        assertTrue(ImmutableNativeResumeV2.inspect(root, hash) is
            ImmutableNativeResumeV2.Inspection.Uncertain)
        assertArrayEquals(second, Files.readAllBytes(foreign.toPath()))
        assertTrue(Files.isSymbolicLink(symlink))
    }

    @Test fun alteredPreviouslyCommittedRecordRefusesReplayWithoutDeletingItsName() {
        val root = state()
        ImmutableNativeResumeV2.beginReviewed(root, hash)
        ImmutableNativeResumeV2.save(root, hash, first)
        val saved = events(root).last()
        val original = Files.readAllBytes(saved)
        // This is an intentionally test-owned fixture, not an unowned file.
        // Corrupt exactly one content byte while preserving record length.
        val changed = original.copyOf()
        changed[changed.lastIndex - 2] =
            if (changed[changed.lastIndex - 2] == '0'.code.toByte())
                '1'.code.toByte() else '0'.code.toByte()
        Files.write(saved, changed)
        assertTrue(ImmutableNativeResumeV2.inspect(root, hash) is
            ImmutableNativeResumeV2.Inspection.Uncertain)
        try {
            ImmutableNativeResumeV2.requestRemoval(root, hash)
            fail("Tampered prior resume must never authorize a new lifecycle event")
        } catch (_: IOException) { }
        assertArrayEquals(changed, Files.readAllBytes(saved))
        assertEquals(2, events(root).size)
    }

    @Test fun invalidHashAndUnreviewedSaveNeverBecomeNativeWriterAuthority() {
        val root = state()
        assertTrue(ImmutableNativeResumeV2.inspect(root, "not-a-hash") is
            ImmutableNativeResumeV2.Inspection.Uncertain)
        try {
            ImmutableNativeResumeV2.save(root, hash, first)
            fail("No unreviewed snapshot may be written")
        } catch (_: IOException) { }
        assertTrue(ImmutableNativeResumeV2.inspect(root, otherHash) is
            ImmutableNativeResumeV2.Inspection.Absent)
        ImmutableNativeResumeV2.beginReviewed(root, hash)
        try {
            ImmutableNativeResumeV2.save(root, hash, ByteArray(16 * 1024 * 1024 + 1))
            fail("Oversized resume payload must refuse before creating a file")
        } catch (_: IOException) { }
        assertEquals(1, events(root).size)
    }
}
