package io.github.gamesbyian.chunks.engine

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Native SQLite with host filesystem calls, separate from Android runtime/power-loss acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29, 35], shadows = [ResumeHostOsShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalResumeDatabaseTest {
    @get:Rule val temp = TemporaryFolder()
    private val hash = "a".repeat(40)
    private fun root() = temp.newFolder()
    private fun open(root: File) = LocalResumeDatabase.open(root,
        LocalResumeDirectoryInventory.prepare(root))
    private fun file(root: File) = File(File(root, LocalResumeDatabase.DIRECTORY), LocalResumeDatabase.DATABASE)
    private fun sql(root: File) = SQLiteDatabase.openDatabase(file(root).absolutePath, null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    private fun refused(block: () -> Unit) {
        try { block(); fail("Expected persistence refusal") } catch (_: Exception) { }
    }

    @Test fun pendingIntentsRemainExplicitUntilARealFreshSessionCanReconcile() {
        val root = root()
        val retired = "a".repeat(40)
        val retained = "b".repeat(40)
        val corrupt = "c".repeat(40)
        open(root).use { store ->
            store.save(retired, "to remove".toByteArray())
            store.save(retained, "still active".toByteArray())
            store.removeIntent(retired)
        }
        open(root).use { reopened ->
            assertEquals(listOf(retired), reopened.pendingRemovals())
            assertFalse(reopened.canRegister(retired))
            assertArrayEquals("still active".toByteArray(), reopened.current(retained))
            // The persistence layer alone never decides that native I/O has ended.
            assertFalse(reopened.canRegister(retired))
            reopened.removeCommit(retired)
            assertTrue(reopened.canRegister(retired))
            assertEquals(emptyList<String>(), reopened.pendingRemovals())
            assertArrayEquals("still active".toByteArray(), reopened.current(retained))
        }
        open(root).use { reopened ->
            assertTrue(reopened.canRegister(retired))
            assertEquals(listOf(retained), reopened.restorable().map { it.first })
        }
    }

    @Test fun repeatedSnapshotsAndRetireReAddReuseBoundedStorageAcrossReopen() {
        val root = root()
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        var afterWarmup = 0L
        var peakSavedBytes = 0L
        repeat(8) { cycle ->
            open(root).use { store ->
                assertTrue(store.canRegister(hash))
                repeat(64) { save ->
                    payload[0] = cycle.toByte(); payload[1] = save.toByte()
                    store.save(hash, payload)
                }
                peakSavedBytes = maxOf(peakSavedBytes, file(root).length())
                assertTrue("An active job's periodic saves must also stay bounded",
                    file(root).length() < 4L * payload.size)
            }
            open(root).use { restored ->
                assertArrayEquals(payload, restored.current(hash))
                assertArrayEquals(payload, restored.restorable().single().second)
                restored.removeIntent(hash)
            }
            open(root).use { interruptedRemove ->
                assertFalse(interruptedRemove.canRegister(hash))
                assertTrue(interruptedRemove.restorable().isEmpty())
                refused { interruptedRemove.save(hash, payload) }
                interruptedRemove.removeCommit(hash)
            }
            val bytes = file(root).length()
            if (cycle == 1) afterWarmup = bytes
            if (cycle > 1) assertTrue("History must reuse SQLite pages: $afterWarmup -> $bytes",
                bytes <= afterWarmup + 16 * 1024)
        }
        assertTrue(file(root).length() < 4L * payload.size)
        assertEquals(setOf("format", "authority", LocalResumeDatabase.DATABASE),
            file(root).parentFile.listFiles()!!.map { it.name }.toSet())
        println("CHUNKS_RESUME_DATABASE saves=512 cycles=8 bytes_after_remove=${file(root).length()} " +
            "peak_saved_bytes=$peakSavedBytes payload_bytes=${payload.size}")
    }

    @Test fun supportedResumeLargerThanDefaultAndroidCursorWindowRestoresExactly() {
        val root = root()
        val payload = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        open(root).use { it.save(hash, payload) }
        open(root).use { assertArrayEquals(payload, it.current(hash)) }
    }

    @Test fun migrationPreservesLatestStateTombstonesAndTornHistoryRefusalWithoutDeletingInputs() {
        val root = root()
        val old = LocalResumeEventStore.open(root, emptyList())
        old.save(hash, "older".toByteArray())
        old.save(hash, "latest".toByteArray())
        val removed = "b".repeat(40)
        old.save(removed, "retired".toByteArray())
        old.removeIntent(removed); old.removeCommit(removed)
        val pending = "c".repeat(40)
        old.removeIntent(pending)
        val corrupt = "d".repeat(40)
        old.save(corrupt, "previous".toByteArray())
        File(root, "$corrupt.resume-event.000000000002").writeText("torn")
        val originals = root.listFiles()!!.associate { it.name to it.readBytes() }
        open(root).use { migrated ->
            assertArrayEquals("latest".toByteArray(), migrated.current(hash))
            assertTrue(migrated.canRegister(removed))
            assertFalse(migrated.canRegister(pending))
            assertFalse(migrated.canRegister(corrupt))
            assertEquals(1, migrated.problems().size)
            assertEquals(hash, migrated.restorable().single().first)
            migrated.save(removed, "new explicit Add".toByteArray())
        }
        for ((name, bytes) in originals) assertArrayEquals(name, bytes, File(root, name).readBytes())
        open(root).use { reopened ->
            assertArrayEquals("new explicit Add".toByteArray(), reopened.current(removed))
            assertFalse(reopened.canRegister(pending))
            assertEquals(1, reopened.problems().size)
        }
    }

    @Test fun failedTransactionRequiresReconciliationAndKeepsLastCommittedSnapshot() {
        val root = root()
        val store = open(root)
        store.save(hash, "durable".toByteArray())
        sql(root).use { database ->
            database.execSQL("CREATE TRIGGER fail_save AFTER INSERT ON resumes BEGIN SELECT RAISE(ABORT, 'injected interruption'); END")
        }
        refused { store.save(hash, "not committed".toByteArray()) }
        refused { store.current(hash) }
        store.close()
        sql(root).use { it.execSQL("DROP TRIGGER fail_save") }
        open(root).use { restored ->
            assertArrayEquals("durable".toByteArray(), restored.current(hash))
        }
    }

    @Test fun staleWriterCannotOverwriteRetirementOrIndependentReAdd() {
        val root = root()
        open(root).use { first ->
            first.save(hash, "original".toByteArray())
            open(root).use { second ->
                second.removeIntent(hash); second.removeCommit(hash)
                second.save(hash, "independent re-Add".toByteArray())
                // Even a byte-identical callback must not falsely acknowledge
                // a cached snapshot from before another writer's retirement.
                refused { first.save(hash, "original".toByteArray()) }
            }
        }
        open(root).use { restored ->
            assertArrayEquals("independent re-Add".toByteArray(), restored.current(hash))
        }
    }

    @Test fun corruptCommittedDatabaseNeverFallsBackToStaleLegacyStateOrDeletesDatabase() {
        val root = root()
        val old = File(root, "$hash.resume").apply { writeText("obsolete snapshot") }
        open(root).use { it.removeIntent(hash); it.removeCommit(hash) }
        sql(root).use { it.execSQL("UPDATE resumes SET phase='SAVED', payload=X'01', digest='forged'") }
        val before = file(root).readBytes()
        refused { open(root).close() }
        assertArrayEquals(before, file(root).readBytes())
        assertEquals("obsolete snapshot", old.readText())
    }

    @Test fun missingOrZeroedAuthoritativeDatabaseCannotReimportRetiredLegacyJobs() {
        val root = root()
        val old = File(root, "$hash.resume").apply { writeText("obsolete snapshot") }
        open(root).use { it.removeIntent(hash); it.removeCommit(hash) }
        Files.delete(file(root).toPath())
        refused { open(root).close() }
        assertFalse("Missing committed state must not create a replacement database", file(root).exists())
        file(root).writeBytes(ByteArray(0))
        val namespace = requireNotNull(file(root).parentFile)
        // This is *authoritative* state: a zero-length replacement may be
        // corrupt/power-interrupted, but SQLite open must not silently
        // initialize or mutate it before refusing to resurrect old jobs.
        val before = requireNotNull(namespace.listFiles()).associate {
            it.name to it.readBytes()
        }
        assertTrue("Zeroed authoritative database fixture is not empty",
            file(root).length() == 0L)
        refused { open(root).close() }
        assertEquals("Refused zeroed database must remain byte-for-byte empty",
            0L, file(root).length())
        assertEquals("Refusing an authoritative zeroed database cannot create sidecars",
            before.keys, requireNotNull(namespace.listFiles()).map { it.name }.toSet())
        for ((name, bytes) in before) {
            assertArrayEquals("Refused open changed authoritative entry $name",
                bytes, File(namespace, name).readBytes())
        }
        assertEquals("obsolete snapshot", old.readText())
    }

    @Test fun unknownDatabaseContentsArePreservedAndNeverResetByCorruptionHandler() {
        val root = root()
        open(root).close()
        val foreign = "not a SQLite database".toByteArray()
        file(root).writeBytes(foreign)
        refused { open(root).close() }
        assertArrayEquals(foreign, file(root).readBytes())
    }

    @Test fun foreignSymlinkOrHardlinkDatabaseIsRejectedWithoutChangingItsEntryOrBytes() {
        val root = root()
        open(root).close()
        val victim = File(temp.root, "foreign").apply { writeText("preserve unrelated bytes") }
        Files.delete(file(root).toPath())
        Files.createSymbolicLink(file(root).toPath(), victim.toPath())
        refused { open(root).close() }
        assertTrue(Files.isSymbolicLink(file(root).toPath()))
        assertEquals("preserve unrelated bytes", victim.readText())
        Files.delete(file(root).toPath())
        Files.createLink(file(root).toPath(), victim.toPath())
        refused { open(root).close() }
        assertTrue(Files.isSameFile(file(root).toPath(), victim.toPath()))
        assertEquals("preserve unrelated bytes", victim.readText())
    }

    @Test fun uncommittedInitialMigrationCanRetryFromIntactHistory() {
        val root = root()
        LocalResumeEventStore.open(root, emptyList()).save(hash, "legacy intact".toByteArray())
        open(root).close()
        // Simulate the pre-commit state of a first import. SQLite rolls back
        // schema, row and version as one transaction; original inputs remain.
        Files.delete(File(file(root).parentFile, "authority").toPath())
        sql(root).use { database ->
            database.execSQL("DROP TABLE resumes")
            database.execSQL("PRAGMA application_id=0")
            database.version = 0
            database.beginTransaction()
            try { database.execSQL("CREATE TABLE resumes (incomplete TEXT)") }
            finally { database.endTransaction() }
        }
        open(root).use { restored ->
            assertArrayEquals("legacy intact".toByteArray(), restored.current(hash))
        }
    }

    @Test fun committedImportBeforeAuthorityReceiptResumesWithoutReimportingLegacyFiles() {
        val root = root()
        val legacy = File(root, "$hash.resume").apply { writeText("old snapshot") }
        open(root).use { it.save(hash, "committed database".toByteArray()) }
        // Interrupted receipt publication after a valid SQL commit: use the
        // committed schema/state and finish publishing authority before use.
        Files.delete(File(file(root).parentFile, "authority").toPath())
        open(root).use { assertArrayEquals("committed database".toByteArray(), it.current(hash)) }
        assertTrue(File(file(root).parentFile, "authority").isFile)
        assertEquals("old snapshot", legacy.readText())
    }
}
