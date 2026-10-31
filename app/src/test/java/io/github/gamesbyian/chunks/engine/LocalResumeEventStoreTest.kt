package io.github.gamesbyian.chunks.engine

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Actual on-disk generation tests; no native torrent or user Downloads access. */
class LocalResumeEventStoreTest {
    private val hash = "a".repeat(40)
    private fun root(): Path = Files.createTempDirectory("chunks-resume-event-")
    private fun open(root: Path): LocalResumeEventStore =
        LocalResumeEventStore.open(root.toFile(),
            requireNotNull(root.toFile().listFiles()).toList())

    @Test fun completedSnapshotRestoresOnlyNewestVerifiedGeneration() {
        val dir = root()
        val store = open(dir)
        val first = "resume first".toByteArray()
        val last = "resume newer".toByteArray()
        assertTrue(store.canRegister(hash))
        store.save(hash, first)
        store.save(hash, last)
        val restored = open(dir)
        assertFalse(restored.canRegister(hash))
        assertEquals(emptyList<String>(), restored.problems())
        assertArrayEquals(last, restored.restorable().single().second)
        assertArrayEquals(last, restored.current(hash))
        assertEquals(2L, Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().contains(".resume-event.") }.count()
        })
    }

    @Test fun removalIntentIsDurableAndPreventsZombieOnInterruptedJniRemoval() {
        val dir = root()
        val store = open(dir)
        store.save(hash, "resume".toByteArray())
        store.removeIntent(hash)
        val afterKill = open(dir)
        assertTrue(afterKill.restorable().isEmpty())
        assertFalse(afterKill.canRegister(hash))
        assertEquals(emptyList<String>(), afterKill.problems())
    }

    @Test fun committedRetirementEnablesIndependentSameHashReAddGeneration() {
        val dir = root()
        val store = open(dir)
        store.save(hash, "original".toByteArray())
        store.removeIntent(hash)
        store.removeCommit(hash)
        assertTrue(open(dir).canRegister(hash))
        store.save(hash, "new transfer".toByteArray())
        val restarted = open(dir)
        assertArrayEquals("new transfer".toByteArray(), restarted.restorable().single().second)
        assertFalse(restarted.canRegister(hash))
    }

    @Test fun foreignLegacyTempAndResumeEntriesAreNeverChangedOrUnlinked() {
        val dir = root()
        val outside = dir.resolve("foreign-owned-source")
        val canary = "unaltered foreign bytes".toByteArray()
        Files.write(outside, canary)
        val oldTemp = dir.resolve("$hash.resume.tmp")
        Files.createSymbolicLink(oldTemp, outside)
        val oldFinal = dir.resolve("$hash.resume")
        val linked = runCatching { Files.createLink(oldFinal, outside) }.isSuccess
        if (!linked) Files.write(oldFinal, "legacy not a bencoded resume".toByteArray())
        val store = open(dir)
        // A legacy .resume, even one which cannot be authenticated as native,
        // blocks a fresh registration until explicit inspection/removal.
        assertFalse(store.canRegister(hash))
        assertArrayEquals(canary, Files.readAllBytes(outside))
        assertTrue(Files.isSymbolicLink(oldTemp))
        assertTrue(Files.exists(oldFinal))
        assertArrayEquals(canary, Files.readAllBytes(outside))
    }

    @Test fun occupiedNextGenerationRefusesWithoutTouchingForeignBytes() {
        val dir = root()
        val store = open(dir)
        val victim = dir.resolve("some-other-file")
        val original = "do not truncate".toByteArray()
        Files.write(victim, original)
        val occupied = dir.resolve("$hash.resume-event.000000000001")
        val hardlinked = runCatching { Files.createLink(occupied, victim) }.isSuccess
        if (!hardlinked) Files.createSymbolicLink(occupied, victim)
        try {
            store.save(hash, "would be persisted".toByteArray())
            fail("CREATE_NEW must reject an occupied event filename")
        } catch (_: IOException) { }
        assertArrayEquals(original, Files.readAllBytes(victim))
        assertTrue(Files.exists(occupied))
    }

    @Test fun tornNewestGenerationNeverRollsBackToOlderSnapshot() {
        val dir = root()
        val store = open(dir)
        store.save(hash, "previous trusted bytes".toByteArray())
        Files.write(dir.resolve("$hash.resume-event.000000000002"),
            "incomplete due to process death".toByteArray())
        val restored = open(dir)
        assertTrue(restored.restorable().isEmpty())
        assertFalse(restored.canRegister(hash))
        assertTrue(restored.problems().single().contains("unsafe/incomplete"))
    }

    @Test fun tamperedOrMissingChainRecordRefusesRollback() {
        val dir = root()
        val store = open(dir)
        store.save(hash, "first".toByteArray())
        store.save(hash, "second".toByteArray())
        val first = dir.resolve("$hash.resume-event.000000000001")
        // Simulate external replacement/compromised state, only in disposable fixtures.
        Files.write(first, "forged".toByteArray())
        val restored = open(dir)
        assertTrue(restored.restorable().isEmpty())
        assertFalse(restored.canRegister(hash))
        assertTrue(restored.problems().isNotEmpty())
    }

    @Test fun legacySnapshotCanBeMigratedWithoutMutatingLegacyEntry() {
        val dir = root()
        val legacy = dir.resolve("$hash.resume")
        val older = "legacy resume byte array".toByteArray()
        Files.write(legacy, older)
        val store = open(dir)
        assertArrayEquals(older, store.restorable().single().second)
        store.save(hash, "new immutably saved resume".toByteArray())
        assertArrayEquals(older, Files.readAllBytes(legacy))
        assertArrayEquals("new immutably saved resume".toByteArray(),
            open(dir).restorable().single().second)
    }
}
