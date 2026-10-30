package io.github.gamesbyian.chunks.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalResumeFileReaderTest {
    @Test fun readsValidBytesWithoutChangingSource() {
        val file = File.createTempFile("chunks-resume-reader", ".resume")
        try {
            val contents = ByteArray(24_000) { (it % 251).toByte() }
            file.writeBytes(contents)
            assertArrayEquals(contents, LocalResumeFileReader.read(file, 32_000))
            assertArrayEquals(contents, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun missingEmptyOversizedAndDirectoryAreRejectedUnchanged() {
        val file = File.createTempFile("chunks-resume-reader", ".resume")
        val folder = File(file.parentFile, "chunks-reader-" + file.name).apply { mkdirs() }
        try {
            file.writeBytes(byteArrayOf())
            assertNull(LocalResumeFileReader.read(file, 32))
            file.writeBytes(ByteArray(128) { 42 })
            assertNull(LocalResumeFileReader.read(file, 64))
            assertEquals(128L, file.length())
            assertNull(LocalResumeFileReader.read(file, 0))
            assertNull(LocalResumeFileReader.read(folder, 256))
            assertNull(LocalResumeFileReader.read(File(folder, "missing.resume"), 256))
        } finally { file.delete(); folder.delete() }
    }

    @Test fun readFailureIsIsolatedAndLeavesFilesIntact() {
        val bad = File.createTempFile("chunks-resume-bad", ".resume")
        val good = File.createTempFile("chunks-resume-good", ".resume")
        try {
            bad.writeBytes(byteArrayOf(5, 6, 7))
            good.writeBytes(byteArrayOf(1, 2, 3))
            assertNull(LocalResumeFileReader.read(bad, 16) { throw IOException("provider disappeared") })
            assertArrayEquals(byteArrayOf(1, 2, 3), LocalResumeFileReader.read(good, 16))
            assertArrayEquals(byteArrayOf(5, 6, 7), bad.readBytes())
        } finally { bad.delete(); good.delete() }
    }

    @Test fun fileGrowthAfterStatCannotBypassLimit() {
        val file = File.createTempFile("chunks-resume-growth", ".resume")
        try {
            file.writeBytes(byteArrayOf(1))
            assertNull(LocalResumeFileReader.read(file, 4) { ByteArrayInputStream(ByteArray(5)) })
            // A stream which grows but stays under the absolute cap is still
            // not the file size recorded before opening it.
            assertNull(LocalResumeFileReader.read(file, 16) { ByteArrayInputStream(ByteArray(5)) })
            assertArrayEquals(byteArrayOf(1), file.readBytes())
        } finally { file.delete() }
    }

    @Test fun truncatedReadIsNotAcceptedAsTheOriginalResumeFile() {
        val file = File.createTempFile("chunks-resume-short", ".resume")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertNull(LocalResumeFileReader.read(file, 16) {
                ByteArrayInputStream(byteArrayOf(1, 2))
            })
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
        } finally { file.delete() }
    }

    @Test fun readerClosesStreamAfterFailure() {
        val file = File.createTempFile("chunks-resume-close", ".resume")
        try {
            file.writeBytes(byteArrayOf(1))
            var closed = false
            val stream = object : InputStream() {
                override fun read(): Int = throw IOException("read fault")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("read fault")
                override fun close() { closed = true }
            }
            assertNull(LocalResumeFileReader.read(file, 8) { stream })
            assertTrue(closed)
        } finally { file.delete() }
    }

    @Test fun symbolicLinkResumeDoesNotReadOrChangeForeignBytes() {
        val external = File.createTempFile("chunks-resume-foreign", ".resume")
        val link = File(external.parentFile, "chunks-resume-alias-" + external.name)
        try {
            val bytes = byteArrayOf(4, 8, 15, 16, 23, 42)
            external.writeBytes(bytes)
            java.nio.file.Files.createSymbolicLink(link.toPath(), external.toPath())
            assertNull(LocalResumeFileReader.read(link, 64))
            assertArrayEquals(bytes, external.readBytes())
        } finally {
            link.delete()
            external.delete()
        }
    }
}
