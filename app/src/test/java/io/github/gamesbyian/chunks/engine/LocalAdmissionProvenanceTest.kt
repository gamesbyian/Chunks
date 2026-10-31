package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAdmissionProvenanceTest {
    @get:Rule val temp = TemporaryFolder()
    private val hash = "a".repeat(40)

    @Test fun failedDirectorySyncCannotBeBypassedByRetryingAnExistingReceipt() {
        val state = temp.newFolder("failed-directory-sync")
        val failure: (java.nio.file.Path) -> Unit = { throw java.io.IOException("sync failed") }
        assertFalse(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED, failure))
        // The hard link can exist despite a failed fsync. Its presence alone is
        // not evidence that its directory entry will survive a crash.
        assertEquals(LocalAdmissionProvenance.Mode.CHECKED,
            LocalAdmissionProvenance.read(state, hash))
        assertFalse(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED, failure))
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
        assertFalse(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
    }

    @Test fun checkedReviewIsDurablyRecognizedButNeverUpgradedToExistingData() {
        val state = temp.newFolder("checked-state")
        assertNull(LocalAdmissionProvenance.read(state, hash))
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
        assertEquals(LocalAdmissionProvenance.Mode.CHECKED,
            LocalAdmissionProvenance.read(state, hash))
        assertFalse("A later raw Add must not erase checked provenance",
            LocalAdmissionProvenance.persist(state, hash,
                LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
        assertEquals(LocalAdmissionProvenance.Mode.CHECKED,
            LocalAdmissionProvenance.read(state, hash))
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
    }

    @Test fun separatelyReviewedExistingSeedRoundTripsAndCannotBeConvertedToChecked() {
        val state = temp.newFolder("reviewed-state")
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
        assertEquals(LocalAdmissionProvenance.Mode.REVIEWED_EXISTING,
            LocalAdmissionProvenance.read(state, hash))
        assertFalse(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
        LocalAdmissionProvenance.retire(state, hash)
        assertNull(LocalAdmissionProvenance.read(state, hash))
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
    }

    @Test fun corruptMissingSymlinkAndBadHashCanNeverGrantReviewedExistingAdmission() {
        val state = temp.newFolder("invalid")
        val marker = File(state, "$hash.admission")
        marker.writeText("reviewed-existing")
        assertNull(LocalAdmissionProvenance.read(state, hash))
        assertFalse(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
        assertTrue(marker.delete())
        val target = temp.newFile("outside-receipt").apply {
            writeText("chunks-native-admission-v1\n$hash\nreviewed-existing\n")
        }
        Files.createSymbolicLink(marker.toPath(), target.toPath())
        assertNull(LocalAdmissionProvenance.read(state, hash))
        assertFalse(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
        assertEquals("unchanged unrelated target receipt",
            "chunks-native-admission-v1\n$hash\nreviewed-existing\n",
            target.readText())
        assertNull(LocalAdmissionProvenance.read(state, "../$hash"))
        assertFalse(LocalAdmissionProvenance.persist(state, "../$hash",
            LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
    }

    @Test fun leftoverHardlinkedTemporaryNeverTruncatesUnrelatedBytes() {
        val state = temp.newFolder("hardlinked-old-temporary")
        val external = temp.newFile("not-owned-by-admission-writer")
        val original = ByteArray(8192) { (it * 31).toByte() }
        external.writeBytes(original)
        val oldTemporary = File(state, "$hash.admission.tmp")
        Files.createLink(oldTemporary.toPath(), external.toPath())
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
        assertArrayEquals("An old tmp path may be a hardlink to unrelated bytes",
            original, external.readBytes())
        assertArrayEquals(original, oldTemporary.readBytes())
        assertEquals(LocalAdmissionProvenance.Mode.CHECKED,
            LocalAdmissionProvenance.read(state, hash))
    }

    @Test fun interruptedSymlinkTemporaryDoesNotBlockSafeUniqueReceiptPublication() {
        val state = temp.newFolder("symlink-old-temporary")
        val external = temp.newFile("not-owned-by-admission-writer-symlink")
        external.writeText("foreign never overwritten")
        Files.createSymbolicLink(File(state, "$hash.admission.tmp").toPath(),
            external.toPath())
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
        assertEquals("foreign never overwritten", external.readText())
        assertEquals(LocalAdmissionProvenance.Mode.REVIEWED_EXISTING,
            LocalAdmissionProvenance.read(state, hash))
    }

    @Test fun simultaneousConflictingModesCannotReplaceAnAlreadyPublishedReceipt() {
        repeat(8) { trial ->
            val state = temp.newFolder("racing-$trial")
            val gate = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val tasks = LocalAdmissionProvenance.Mode.entries.map { mode ->
                    pool.submit<Boolean> {
                        gate.await()
                        LocalAdmissionProvenance.persist(state, hash, mode)
                    }
                }
                gate.countDown()
                val outcomes = tasks.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals("Exactly one conflicting immutable mode may be published", 1,
                    outcomes.count { it })
                assertEquals(LocalAdmissionProvenance.Mode.entries[outcomes.indexOf(true)],
                    LocalAdmissionProvenance.read(state, hash))
            } finally {
                gate.countDown()
                pool.shutdownNow()
            }
        }
    }

    @Test fun competingCheckedAndReviewedExistingAdmissionCannotReplaceTheOther() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            repeat(24) { index ->
                val state = temp.newFolder("race-$index")
                val begin = java.util.concurrent.CountDownLatch(1)
                val checked = pool.submit<Boolean> {
                    begin.await()
                    LocalAdmissionProvenance.persist(
                        state, hash, LocalAdmissionProvenance.Mode.CHECKED)
                }
                val existing = pool.submit<Boolean> {
                    begin.await()
                    LocalAdmissionProvenance.persist(
                        state, hash, LocalAdmissionProvenance.Mode.REVIEWED_EXISTING)
                }
                begin.countDown()
                val checkedWon = checked.get(5L, java.util.concurrent.TimeUnit.SECONDS)
                val existingWon = existing.get(5L, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue("Exactly one mode is allowed to create the target",
                    checkedWon != existingWon)
                val admitted = LocalAdmissionProvenance.read(state, hash)
                assertEquals("The durable receipt must identify the winner",
                    if (checkedWon) LocalAdmissionProvenance.Mode.CHECKED
                    else LocalAdmissionProvenance.Mode.REVIEWED_EXISTING, admitted)
                assertFalse("A loser cannot later convert a checked into an existing seed",
                    LocalAdmissionProvenance.persist(state, hash,
                        if (checkedWon) LocalAdmissionProvenance.Mode.REVIEWED_EXISTING
                        else LocalAdmissionProvenance.Mode.CHECKED))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun interruptedTemporaryOrOversizedReceiptDoesNotAuthorizeUnownedData() {
        val state = temp.newFolder("interrupted")
        File(state, "$hash.admission.tmp").writeText("incomplete transaction")
        assertNull(LocalAdmissionProvenance.read(state, hash))
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
        val marker = File(state, "$hash.admission")
        marker.writeBytes(ByteArray(1000))
        assertNull(LocalAdmissionProvenance.read(state, hash))
        assertFalse(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
    }
}
