package io.github.gamesbyian.chunks

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.media.MediaFileHint
import java.io.File
import java.io.IOException
import java.util.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android descriptor operations, not a JVM approximation of Os.open. */
@RunWith(AndroidJUnit4::class)
class LocalCompletedMediaFilesInstrumentedTest {
    private fun fixtureRoot() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "media-pinned-" + System.nanoTime()).apply { assertTrue(mkdir()) }
    private fun row(index: Int, path: String, size: Long, done: Long = size) =
        LocalTransferFile(index, path, size, done, true)

    @Test fun readsExactCoverAndRandomRangesWithoutAuthorizingIncompleteOrForeignRows() {
        val root = fixtureRoot()
        try {
            val cover = ByteArray(1234).also { Random(17).nextBytes(it) }
            File(root, "cover.jpg").writeBytes(cover)
            File(root, "partial.flac").writeBytes(ByteArray(500))
            File(root, "short.flac").writeBytes(ByteArray(90))
            val local = LocalCompletedMediaFiles(root.absolutePath, listOf(
                row(0, "cover.jpg", 1234), row(1, "partial.flac", 1000, 500),
                row(2, "short.flac", 100)))
            val hint = MediaFileHint("cover.jpg", 1234)
            assertEquals(3, local.hints.size)
            assertArrayEquals(cover, local.read(hint))
            assertNull(local.read(hint, limit = 100))
            assertNull(local.read(hint, limit = Int.MAX_VALUE))
            assertNull(local.read(MediaFileHint("partial.flac")))
            assertNull(local.open(MediaFileHint("short.flac")))
            assertNull(local.read(MediaFileHint("other.jpg")))
            assertNull(local.open(MediaFileHint("cover.jpg", 1)))
            val source = requireNotNull(local.open(hint))
            assertEquals(1234L, source.size)
            assertArrayEquals(cover.copyOfRange(1200, 1234), source.read(1200, 100))
            assertArrayEquals(cover.copyOfRange(11, 43), source.read(11, 32))
            assertEquals(0, source.read(1234, 1).size)
            assertTrue(runCatching { source.read(0, Int.MAX_VALUE) }.exceptionOrNull() is IOException)
            assertTrue(runCatching { source.read(-1, 1) }.exceptionOrNull() is IOException)
            assertArrayEquals(cover, File(root, "cover.jpg").readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun replacedFileNeverRebindsAnExistingObservationEvenIfOriginalReturns() {
        val root = fixtureRoot()
        try {
            val original = ByteArray(128_123).also { Random(18).nextBytes(it) }
            val replacement = ByteArray(original.size).also { Random(19).nextBytes(it) }
            val file = File(root, "movie.mkv").apply { writeBytes(original) }
            val retired = File(root, "retired")
            val local = LocalCompletedMediaFiles(root.absolutePath,
                listOf(row(0, file.name, original.size.toLong())))
            val hint = requireNotNull(local.primaryInspectable)
            val source = requireNotNull(local.open(hint))
            assertArrayEquals(original.copyOfRange(0, 32), source.read(0, 32))
            assertTrue(file.renameTo(retired))
            file.writeBytes(replacement)
            assertTrue(runCatching { source.read(64_000, 64) }.exceptionOrNull() is IOException)
            assertArrayEquals(replacement, file.readBytes())
            assertArrayEquals(original, retired.readBytes())
            assertTrue(file.delete())
            assertTrue(retired.renameTo(file))
            assertTrue("Expired observation cannot be revived by restoring pathname",
                runCatching { source.read(64_000, 64) }.exceptionOrNull() is IOException)
            assertArrayEquals(original, file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun fifoSymlinkAndIntermediateLinkCannotBecomeReadableAndGrowthIsRefused() {
        val root = fixtureRoot()
        val outside = fixtureRoot()
        try {
            val bytes = ByteArray(64) { 42 }
            val foreign = File(outside, "secret.jpg").apply { writeBytes(bytes) }
            val fifo = File(root, "fifo.jpg")
            Os.mkfifo(fifo.absolutePath, 384)
            Os.symlink(foreign.absolutePath, File(root, "link.jpg").absolutePath)
            Os.symlink(outside.absolutePath, File(root, "directory").absolutePath)
            val good = File(root, "good.jpg").apply { writeBytes(bytes) }
            val local = LocalCompletedMediaFiles(root.absolutePath, listOf(
                row(0, fifo.name, 64), row(1, "link.jpg", 64),
                row(2, "directory/secret.jpg", 64), row(3, "../secret.jpg", 64),
                row(4, good.name, 64)))
            for (path in listOf(fifo.name, "link.jpg", "directory/secret.jpg", "../secret.jpg")) {
                assertNull(local.open(MediaFileHint(path)))
                assertNull(local.read(MediaFileHint(path)))
            }
            val opened = requireNotNull(local.open(MediaFileHint(good.name)))
            good.appendBytes(byteArrayOf(99))
            assertTrue(runCatching { opened.read(0, 32) }.exceptionOrNull() is IOException)
            assertNull(local.read(MediaFileHint(good.name)))
            assertArrayEquals(bytes, foreign.readBytes())
            assertArrayEquals(bytes + byteArrayOf(99), good.readBytes())
            // Remove only the test's links, never recurse through a foreign root.
            assertTrue(File(root, "directory").delete())
            assertTrue(File(root, "link.jpg").delete())
            assertTrue(fifo.delete())
        } finally {
            File(root, "directory").delete()
            File(root, "link.jpg").delete()
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
