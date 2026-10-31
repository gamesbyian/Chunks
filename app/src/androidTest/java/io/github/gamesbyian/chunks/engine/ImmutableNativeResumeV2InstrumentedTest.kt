package io.github.gamesbyian.chunks.engine

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real app-private Android FS / FD / fsync evidence for the NON-SHIPPING
 * immutable native resume candidate. No libtorrent handle or user download
 * is created, and no pathname-based teardown is performed.
 */
@RunWith(AndroidJUnit4::class)
class ImmutableNativeResumeV2InstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val hash = "b".repeat(40)
    private fun root(): Path = Files.createTempDirectory(
        context.filesDir.toPath(), "chunks-immutable-resume-")

    @Test fun actualAndroidPrivateDirectoryFsyncAndIndependentSnapshotReadback() {
        val state = root()
        val first = ByteArray(82_003) { ((it * 31 + 7) and 255).toByte() }
        val second = ByteArray(55_013) { ((it * 23 + 13) and 255).toByte() }
        val epoch = ImmutableNativeResumeV2.beginReviewed(state, hash)
        ImmutableNativeResumeV2.save(state, hash, first)
        val one = ImmutableNativeResumeV2.inspect(state, hash) as
            ImmutableNativeResumeV2.Inspection.Active
        assertEquals(epoch, one.epoch)
        assertArrayEquals(first, one.snapshot)
        val folder = state.resolve("immutable-resume-v2")
        val earlier = LinkedHashMap<Path, ByteArray>()
        Files.newDirectoryStream(folder).use { stream ->
            for (name in stream) earlier[name] = Files.readAllBytes(name)
        }
        ImmutableNativeResumeV2.save(state, hash, second)
        assertArrayEquals(second, (ImmutableNativeResumeV2.inspect(state, hash) as
            ImmutableNativeResumeV2.Inspection.Active).snapshot)
        for ((path, bytes) in earlier) {
            assertArrayEquals("Published immutable generation was replaced", bytes,
                Files.readAllBytes(path))
        }
    }

    @Test fun actualAndroidRemoveIntentSurvivesNewInspectionWithoutResurrection() {
        val state = root()
        val original = ImmutableNativeResumeV2.beginReviewed(state, hash)
        ImmutableNativeResumeV2.save(state, hash, byteArrayOf(1, 2, 3))
        ImmutableNativeResumeV2.requestRemoval(state, hash)
        assertTrue(ImmutableNativeResumeV2.inspect(state, hash) is
            ImmutableNativeResumeV2.Inspection.Uncertain)
        try {
            ImmutableNativeResumeV2.save(state, hash, byteArrayOf(7, 8))
            fail("A pending removal cannot admit a later resume write")
        } catch (_: IOException) { }
        ImmutableNativeResumeV2.completeRemoval(state, hash)
        assertEquals(original, (ImmutableNativeResumeV2.inspect(state, hash) as
            ImmutableNativeResumeV2.Inspection.Removed).epoch)
        val reReviewed = ImmutableNativeResumeV2.beginReviewed(state, hash)
        assertNotEquals(original, reReviewed)
    }

    @Test fun actualAndroidFifoAndSymlinkRecordsRefuseWithoutTouchingForeignBytes() {
        val state = root()
        ImmutableNativeResumeV2.beginReviewed(state, hash)
        val dir = state.resolve("immutable-resume-v2")
        val fifo = dir.resolve(hash + ".r2.000002." + "c".repeat(32) + ".event")
        Os.mkfifo(fifo.toString(), 384) // app-owned test directory, mode 0600
        assertTrue("Pinned native resume reader must not block on a FIFO",
            ImmutableNativeResumeV2.inspect(state, hash) is
                ImmutableNativeResumeV2.Inspection.Uncertain)
        assertTrue(Files.exists(fifo))
        val anotherHash = "c".repeat(40)
        val foreign = state.resolve("foreign-owned-test.bin")
        Files.write(foreign, "foreign byte-preservation witness".toByteArray())
        val link = dir.resolve(anotherHash + ".r2.000001." + "a".repeat(32) + ".event")
        Files.createSymbolicLink(link, foreign)
        assertTrue(ImmutableNativeResumeV2.inspect(state, anotherHash) is
            ImmutableNativeResumeV2.Inspection.Uncertain)
        assertArrayEquals("foreign byte-preservation witness".toByteArray(),
            Files.readAllBytes(foreign))
        assertTrue(Files.isSymbolicLink(link))
    }
}
