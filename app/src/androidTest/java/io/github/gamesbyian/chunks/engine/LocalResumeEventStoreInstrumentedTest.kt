package io.github.gamesbyian.chunks.engine

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Android API29+ filesystem witnesses for exclusive resume events.
 * All fixtures are newly created in app-private cache. No user Downloads,
 * original downloads, or post-test pathname deletion are involved.
 */
@RunWith(AndroidJUnit4::class)
class LocalResumeEventStoreInstrumentedTest {
    private val hash = "b".repeat(40)
    private fun root(): Path = Files.createTempDirectory(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(),
        "chunks-resume-g0-")
    private fun open(dir: Path): LocalResumeEventStore = LocalResumeEventStore.open(
        dir.toFile(), requireNotNull(dir.toFile().listFiles()).toList())

    @Test fun nativeAndroidExclusiveGenerationRejectsPlantedForeignDirectoryEntry() {
        val dir = root()
        val store = open(dir)
        val origin = dir.resolve("foreign-collision-canary")
        val canary = "unchanged after refusal".toByteArray()
        Files.write(origin, canary)
        val occupied = dir.resolve("$hash.resume-event.000000000001")
        // Android 10/15 can deny hardlink creation even inside app-private state.
        // Test a real conflicting hardlink when available; otherwise plant a
        // regular entry, which CREATE_NEW must refuse with the same guarantee.
        val hardlinked = runCatching { Files.createLink(occupied, origin) }.isSuccess
        if (!hardlinked) Files.write(occupied, "already occupied".toByteArray())
        try {
            store.save(hash, "new resume".toByteArray())
            fail("Existing final name must refuse without replacement")
        } catch (_: IOException) { }
        assertArrayEquals(canary, Files.readAllBytes(origin))
        assertTrue(Files.exists(occupied, LinkOption.NOFOLLOW_LINKS))
        if (hardlinked) assertArrayEquals(canary, Files.readAllBytes(occupied))
        else assertEquals("already occupied", String(Files.readAllBytes(occupied)))
    }

    @Test fun realAndroidFifoAtNextEventIsNonblockingAndNeverRemoved() {
        val dir = root()
        val store = open(dir)
        val fifo = dir.resolve("$hash.resume-event.000000000001")
        Os.mkfifo(fifo.toString(), 384)
        assertTrue(OsConstants.S_ISFIFO(Os.lstat(fifo.toString()).st_mode))
        try {
            store.save(hash, "attempt".toByteArray())
            fail("CREATE_NEW must refuse FIFO collision")
        } catch (_: IOException) { }
        assertTrue(OsConstants.S_ISFIFO(Os.lstat(fifo.toString()).st_mode))
        val restarted = open(dir)
        assertTrue(restarted.restorable().isEmpty())
        assertFalse(restarted.canRegister(hash))
        assertTrue(restarted.problems().isNotEmpty())
    }

    @Test fun realAndroidFsyncRemovalIntentPreventsRestoreAfterRestart() {
        val dir = root()
        val store = open(dir)
        store.save(hash, "resume snapshot before user Remove".toByteArray())
        store.removeIntent(hash)
        val recovered = open(dir)
        assertTrue(recovered.restorable().isEmpty())
        assertFalse(recovered.canRegister(hash))
        assertTrue(recovered.problems().isEmpty())
        // Once a durable completion is appended, a future app session may
        // choose to re-register the same identity with a new generation.
        store.removeCommit(hash)
        assertTrue(open(dir).canRegister(hash))
        assertEquals(3, dir.toFile().listFiles()?.size)
    }

    @Test fun realAndroidBrokenLatestEventNeverRestoresEarlierValidData() {
        val dir = root()
        val store = open(dir)
        store.save(hash, "previous snapshot".toByteArray())
        Files.write(dir.resolve("$hash.resume-event.000000000002"),
            "interrupted future write".toByteArray())
        val recovered = open(dir)
        assertTrue(recovered.restorable().isEmpty())
        assertFalse(recovered.canRegister(hash))
        assertEquals(1, recovered.problems().size)
    }
}
