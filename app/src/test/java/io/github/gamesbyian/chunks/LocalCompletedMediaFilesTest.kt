package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.media.MediaFileHint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Local media evidence is limited to verified, completed regular files inside the real save folder. */
class LocalCompletedMediaFilesTest {
    private val root: File = Files.createTempDirectory("local-media").toFile()
    private fun write(path: String, bytes: ByteArray) = File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
    private fun file(i: Int, path: String, size: Long, done: Long) = LocalTransferFile(i, path, size, done, true)

    @Test fun onlyCompletedExactSizeFilesAreReadable() {
        val cover = ByteArray(1234) { 7 }
        write("Album/cover.jpg", cover)
        write("Album/01.flac", ByteArray(500))
        write("Album/02.flac", ByteArray(90)) // completed per engine, wrong size on disk
        val files = listOf(file(0, "Album/cover.jpg", 1234, 1234), file(1, "Album/01.flac", 1000, 500),
            file(2, "Album/02.flac", 100, 100))
        val local = LocalCompletedMediaFiles(root.absolutePath, files)
        assertEquals(3, local.hints.size)
        assertArrayEquals(cover, local.read(MediaFileHint("Album/cover.jpg", 1234)))
        assertNull("Partly downloaded files are never read", local.read(MediaFileHint("Album/01.flac")))
        assertNull("A size mismatch is not the verified file", local.open(MediaFileHint("Album/02.flac")))
        assertNull("Unlisted paths are refused", local.read(MediaFileHint("Album/other.jpg")))
        assertNull("An over-limit image is refused", local.read(MediaFileHint("Album/cover.jpg"), limit = 100))
        assertNotNull(local.open(MediaFileHint("Album/cover.jpg")))
    }

    @Test fun symlinksEscapesAndMissingFoldersAreRefused() {
        val outside = Files.createTempDirectory("outside").toFile()
        File(outside, "secret.jpg").writeBytes(ByteArray(10))
        Files.createSymbolicLink(File(root, "link.jpg").toPath(), File(outside, "secret.jpg").toPath())
        val files = listOf(file(0, "link.jpg", 10, 10), file(1, "../secret.jpg", 10, 10))
        val local = LocalCompletedMediaFiles(root.absolutePath, files)
        assertNull(local.read(MediaFileHint("link.jpg")))
        assertNull(local.read(MediaFileHint("../secret.jpg")))
        assertNull(LocalCompletedMediaFiles(File(root, "missing").absolutePath, files).read(MediaFileHint("link.jpg")))
        assertNull(LocalCompletedMediaFiles("relative/path", files).read(MediaFileHint("link.jpg")))
    }
}
