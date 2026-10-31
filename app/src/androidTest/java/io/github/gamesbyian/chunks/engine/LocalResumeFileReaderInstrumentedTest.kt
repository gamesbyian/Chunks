package io.github.gamesbyian.chunks.engine

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android API29/35 native resume read-only admission; test-owned cache only. */
@RunWith(AndroidJUnit4::class)
class LocalResumeFileReaderInstrumentedTest {
    private fun ownedDirectory(): File = Files.createTempDirectory(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(),
        "chunks-resume-read-").toFile()

    @Test fun nativeResumeReaderPinsOriginalRegularBytesWithoutReopeningPath() {
        val folder = ownedDirectory()
        val receipt = File(folder, "owned.resume")
        val bytes = ByteArray(82_001) { (it * 17 + 13).toByte() }
        Files.write(receipt.toPath(), bytes)
        assertArrayEquals(bytes, LocalResumeFileReader.read(receipt, 90_000))
        assertArrayEquals(bytes, Files.readAllBytes(receipt.toPath()))
        assertNull("A byte limit never authorizes a partial resume",
            LocalResumeFileReader.read(receipt, 80_000))
        assertTrue("No attempted cleanup on oversized refusal", receipt.isFile)
    }

    @Test fun fifoSymlinkAndHardlinkCannotBlockOrSpoofAndroidResumeRead() {
        val folder = ownedDirectory()
        val fifo = File(folder, "attacker-fifo.resume")
        Os.mkfifo(fifo.absolutePath, 384) // 0600
        // A blocking open after an isRegularFile preflight would hang here.
        assertNull("FIFO must be refused without waiting for a writer",
            LocalResumeFileReader.read(fifo, 16_384))
        assertTrue(Files.exists(fifo.toPath(), LinkOption.NOFOLLOW_LINKS))
        val foreign = File(folder, "foreign-target.bin")
        val bytes = ByteArray(1024) { (it * 41 + 7).toByte() }
        foreign.writeBytes(bytes)
        val alias = File(folder, "attacker-link.resume")
        Files.createSymbolicLink(alias.toPath(), foreign.toPath())
        assertNull(LocalResumeFileReader.read(alias, 16_384))
        assertTrue(Files.isSymbolicLink(alias.toPath()))
        val hardlink = File(folder, "attacker-hardlink.resume")
        try {
            Files.createLink(hardlink.toPath(), foreign.toPath())
            assertNull("Multiply-linked foreign inode must not be trusted",
                LocalResumeFileReader.read(hardlink, 16_384))
            assertTrue(Files.isSameFile(hardlink.toPath(), foreign.toPath()))
        } catch (_: FileSystemException) {
            // Android app-private volumes may prohibit same-UID hardlinks;
            // denial is safe and must not weaken regular/symlink/FIFO tests.
        }
        assertArrayEquals(bytes, foreign.readBytes())
        assertEquals(bytes.size.toLong(), foreign.length())
    }
}
