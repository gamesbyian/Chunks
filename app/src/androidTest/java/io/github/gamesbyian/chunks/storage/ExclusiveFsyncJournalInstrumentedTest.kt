package io.github.gamesbyian.chunks.storage

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.FileSystemException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Android private-journal recovery, never a public Download or a
 * claim to write/delete a provider URI. Every test node is created only
 * under its own newly minted app-cache directory and is not cleaned up
 * by pathname. The disposable emulator owns the temporary test volume.
 */
@RunWith(AndroidJUnit4::class)
class ExclusiveFsyncJournalInstrumentedTest {
    private fun freshRoot() = Files.createTempDirectory(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(),
        "chunks-private-read-")

    @Test fun completedLinesReadOnlyAndTornTailNeverBecomesCommitted() {
        val dir = freshRoot()
        val source = dir.resolve("private-recovery.log")
        Files.write(source, "INTENT\nROW\nincomplete".toByteArray())
        assertEquals(listOf("INTENT", "ROW"),
            ExclusiveFsyncJournal.readCompleteLines(source))
        assertEquals("INTENT\nROW\nincomplete", String(Files.readAllBytes(source), Charsets.UTF_8))
    }

    @Test fun fifoSymlinkOversizedAndHardlinkCannotBeAdoptedAsJournal() {
        val dir = freshRoot()
        val fifo = dir.resolve("untrusted-fifo.log")
        Os.mkfifo(fifo.toString(), 384) // 0600, app-private fixture
        // Without NONBLOCK on Android, a pre-open FIFO substitution can hang
        // recovery indefinitely. This must return null promptly.
        assertNull(ExclusiveFsyncJournal.readCompleteLines(fifo))
        val target = dir.resolve("preserve-foreign-source.log")
        Files.write(target, "not a private journal\n".toByteArray())
        val link = dir.resolve("untrusted-link.log")
        Files.createSymbolicLink(link, target)
        assertTrue(Files.isSymbolicLink(link))
        assertNull(ExclusiveFsyncJournal.readCompleteLines(link))
        assertEquals("not a private journal\n", String(Files.readAllBytes(target), Charsets.UTF_8))
        val hardlink = dir.resolve("untrusted-hardlink.log")
        val linked = try {
            Files.createLink(hardlink, target)
            true
        } catch (_: FileSystemException) { false }
        if (linked) {
            assertNull("More than one hardlink cannot prove a unique journal",
                ExclusiveFsyncJournal.readCompleteLines(hardlink))
            assertEquals("not a private journal\n", String(Files.readAllBytes(target), Charsets.UTF_8))
        }
        val oversized = dir.resolve("oversized.log")
        Files.write(oversized, ByteArray(1024 * 1024 + 1) { 42 })
        assertNull(ExclusiveFsyncJournal.readCompleteLines(oversized))
        assertFalse(Files.size(oversized) == 0L)
    }
}
