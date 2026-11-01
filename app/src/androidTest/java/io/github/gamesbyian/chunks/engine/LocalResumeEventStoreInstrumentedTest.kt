package io.github.gamesbyian.chunks.engine

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.io.File
import org.libtorrent4j.TorrentBuilder
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
    @Test fun realNativeRemoveRetainsForeignLegacyEntryAndReAddAfterRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testRoot = root()
        val state = Files.createDirectory(testRoot.resolve("state")).toFile()
        val sourceRoot = Files.createDirectory(testRoot.resolve("source")).toFile()
        val name = File(sourceRoot, "Native remove fixture").apply { mkdirs() }
        File(name, "payload.bin").writeBytes(ByteArray(16_384) { (it % 251).toByte() })
        val torrent = TorrentBuilder().path(name).pieceSize(16 * 1024)
            .generate().entry().bencode()
        val config = LocalEngineConfig(
            listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true,
            stateDirectory = state
        )
        val original = LocalTorrentEngine(config)
        original.start()
        val added = original.addStopped(torrent, sourceRoot)
        assertTrue("Clean native Add must not regress", added is LocalAddResult.AddedStopped)
        val id = (added as LocalAddResult.AddedStopped).status.infoHash
        val foreign = File(state, "unrelated-foreign-bytes")
        val canary = "foreign entry survives Remove".toByteArray()
        foreign.writeBytes(canary)
        val legacyResume = File(state, "$id.resume")
        val linked = runCatching {
            Files.createLink(legacyResume.toPath(), foreign.toPath())
        }.isSuccess
        if (!linked) legacyResume.writeBytes(canary)
        assertTrue("Native Remove should be confirmed", original.removeKeepingData(id))
        assertArrayEquals(canary, foreign.readBytes())
        assertArrayEquals(canary, legacyResume.readBytes())
        assertTrue(legacyResume.exists())
        original.close()

        val restarted = LocalTorrentEngine(config)
        try {
            restarted.start()
            assertTrue("Removed transfer must not resurrect", restarted.list().isEmpty())
            assertTrue("No false restore failure on committed Remove: ${restarted.restoreProblems}",
                restarted.restoreProblems.isEmpty())
            val again = restarted.addStopped(torrent, sourceRoot)
            assertTrue("Same hash can be re-added after old native I/O has drained: $again",
                again is LocalAddResult.AddedStopped)
            assertArrayEquals(canary, foreign.readBytes())
            assertArrayEquals(canary, legacyResume.readBytes())
        } finally { restarted.close() }
    }

    /**
     * Query the actual SQLite connection the Android production store opened,
     * not a second connection (where synchronous is connection-local).
     * These tests run in both API29 and API35 installed instrumentation.
     */
    private fun assertProductionExtraSync(store: LocalResumeStore) {
        assertTrue("Android must open its transactional resume implementation",
            store is LocalResumeDatabase)
        val field = LocalResumeDatabase::class.java.getDeclaredField("database")
        field.isAccessible = true
        val connection = field.get(store) as android.database.sqlite.SQLiteDatabase
        connection.rawQuery("PRAGMA synchronous", null).use { cursor ->
            assertTrue("Production SQLite PRAGMA query returned no row", cursor.moveToFirst())
            assertEquals("DELETE journal retirement needs EXTRA directory sync",
                3, cursor.getInt(0))
        }
    }

    @Test fun androidProductionExtraSyncKeepsRemoveIntentAndCommitAcrossFreshOpens() {
        val dir = root()
        val legacy = "pre-upgrade immutable snapshot".toByteArray()
        val saved = "new transactional snapshot".toByteArray()
        val readded = "explicitly re-added new generation".toByteArray()
        // Leave the pre-upgrade generation in place: a stale migration must
        // never resurrect it after a committed Remove intent.
        open(dir).save(hash, legacy)
        val original = requireNotNull(dir.toFile().listFiles()).associate { it.name to it.readBytes() }
        val rootFile = dir.toFile()
        fun fresh() = LocalResumeStore.open(rootFile,
            LocalResumeDirectoryInventory.prepare(rootFile))
        fresh().use { store ->
            assertProductionExtraSync(store)
            assertArrayEquals(legacy, store.current(hash))
            store.save(hash, saved)
        }
        fresh().use { store ->
            assertProductionExtraSync(store)
            assertArrayEquals(saved, store.current(hash))
            store.removeIntent(hash)
        }
        fresh().use { store ->
            assertProductionExtraSync(store)
            assertFalse("Committed REMOVE_INTENT forbids stale re-Add", store.canRegister(hash))
            assertTrue("Old saved state must not resurrect", store.restorable().isEmpty())
            store.removeCommit(hash)
        }
        fresh().use { store ->
            assertProductionExtraSync(store)
            assertTrue("Committed Remove permits explicit fresh Add", store.canRegister(hash))
            assertTrue("Old legacy data must remain retired", store.restorable().isEmpty())
            store.save(hash, readded)
        }
        fresh().use { store ->
            assertProductionExtraSync(store)
            assertArrayEquals(readded, store.current(hash))
        }
        for ((name, bytes) in original) {
            assertArrayEquals("Migration may not mutate old immutable source $name",
                bytes, dir.resolve(name).toFile().readBytes())
        }
    }

    @Test fun androidTransactionalSnapshotsHaveBoundedGrowthAndDurableRetirementAcrossReopen() {
        val dir = root().toFile()
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        var warmBytes = 0L
        repeat(8) { cycle ->
            LocalResumeStore.open(dir, LocalResumeDirectoryInventory.prepare(dir)).use { store ->
                assertTrue("Android must use the production transactional store", store is LocalResumeDatabase)
                assertTrue(store.canRegister(hash))
                repeat(64) { save ->
                    payload[0] = cycle.toByte(); payload[1] = save.toByte()
                    store.save(hash, payload)
                }
                val database = File(File(dir, LocalResumeDatabase.DIRECTORY), LocalResumeDatabase.DATABASE)
                assertTrue("Active periodic snapshots must stay bounded",
                    database.length() < 4L * payload.size)
            }
            LocalResumeStore.open(dir, LocalResumeDirectoryInventory.prepare(dir)).use { store ->
                assertArrayEquals(payload, store.current(hash))
                store.removeIntent(hash)
            }
            LocalResumeStore.open(dir, LocalResumeDirectoryInventory.prepare(dir)).use { store ->
                assertFalse(store.canRegister(hash))
                assertTrue(store.restorable().isEmpty())
                store.removeCommit(hash)
            }
            val database = File(File(dir, LocalResumeDatabase.DIRECTORY), LocalResumeDatabase.DATABASE)
            if (cycle == 1) warmBytes = database.length()
            if (cycle > 1) assertTrue("Periodic snapshots must reuse database pages",
                database.length() <= warmBytes + 16 * 1024)
        }
        assertFalse("Production Android saves must not append immutable events",
            dir.listFiles()!!.any { it.name.contains(".resume-event.") })
    }

    @Test fun androidMigrationPreservesTornHistoryRefusalAndOriginalEntries() {
        val dir = root()
        open(dir).save(hash, "earlier snapshot".toByteArray())
        Files.write(dir.resolve("$hash.resume-event.000000000002"), "torn newer save".toByteArray())
        val originals = dir.toFile().listFiles()!!.associate { it.name to it.readBytes() }
        LocalResumeStore.open(dir.toFile(), LocalResumeDirectoryInventory.prepare(dir.toFile())).use { store ->
            assertTrue(store.restorable().isEmpty())
            assertFalse(store.canRegister(hash))
            assertEquals(1, store.problems().size)
        }
        for ((name, bytes) in originals) assertArrayEquals(bytes, dir.resolve(name).toFile().readBytes())
    }

    @Test fun androidCorruptDatabaseIsPreservedWithoutFallingBackToOldLegacyResume() {
        val dir = root().toFile()
        val legacy = File(dir, "$hash.resume").apply { writeText("obsolete snapshot") }
        LocalResumeStore.open(dir, LocalResumeDirectoryInventory.prepare(dir)).use { store ->
            store.removeIntent(hash); store.removeCommit(hash)
        }
        val database = File(File(dir, LocalResumeDatabase.DIRECTORY), LocalResumeDatabase.DATABASE)
        val foreign = "foreign content at the database path".toByteArray()
        database.writeBytes(foreign)
        try {
            LocalResumeStore.open(dir, LocalResumeDirectoryInventory.prepare(dir)).close()
            fail("Corrupt committed state must refuse startup")
        } catch (_: Exception) { }
        assertArrayEquals(foreign, database.readBytes())
        assertEquals("obsolete snapshot", legacy.readText())
        // Loss of a committed database must also refuse rather than recreate
        // it from the preserved, now obsolete pre-migration snapshot.
        Files.delete(database.toPath())
        try {
            LocalResumeStore.open(dir, LocalResumeDirectoryInventory.prepare(dir)).close()
            fail("Missing committed state must refuse legacy fallback")
        } catch (_: Exception) { }
        assertFalse(database.exists())

        // Actual Android SQLite, after a committed authority marker, must not
        // initialize or alter a zero-length replacement or sidecars merely by
        // attempting to open and refusing this corrupted authoritative state.
        database.writeBytes(ByteArray(0))
        val namespace = requireNotNull(database.parentFile)
        val before = requireNotNull(namespace.listFiles()).associate {
            it.name to it.readBytes()
        }
        try {
            LocalResumeStore.open(dir, LocalResumeDirectoryInventory.prepare(dir)).close()
            fail("Zeroed committed database must refuse without legacy fallback")
        } catch (_: Exception) { }
        assertEquals("Authoritative state was initialized after corrupt refusal",
            0L, database.length())
        assertEquals("Refused SQLite open must not leave unexpected sidecars",
            before.keys, requireNotNull(namespace.listFiles()).map { it.name }.toSet())
        for ((name, bytes) in before) {
            assertArrayEquals("Refusing authoritative zeroed DB changed $name",
                bytes, File(namespace, name).readBytes())
        }
        assertEquals("obsolete snapshot", legacy.readText())
    }

    @Test fun androidLargeResumeExceedingDefaultCursorWindowRestoresExactly() {
        val dir = root().toFile()
        val payload = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        LocalResumeStore.open(dir, LocalResumeDirectoryInventory.prepare(dir)).use { it.save(hash, payload) }
        LocalResumeStore.open(dir, LocalResumeDirectoryInventory.prepare(dir)).use {
            assertArrayEquals(payload, it.current(hash))
        }
    }

    @Test fun androidTwoProductionSQLiteConnectionsRefuseStaleSaveAfterRemoveAndReAdd() {
        val dir = root().toFile()
        val original = "old native resume snapshot".toByteArray()
        val replacement = "explicit new Add generation".toByteArray()
        fun fresh() = LocalResumeStore.open(dir, LocalResumeDirectoryInventory.prepare(dir))
        // Both connections use real Android SQLite, unlike the host-only
        // stale-writer regression. Keep A open while B retires and re-adds.
        fresh().use { stale ->
            assertProductionExtraSync(stale)
            stale.save(hash, original)
            fresh().use { newer ->
                assertProductionExtraSync(newer)
                assertArrayEquals(original, newer.current(hash))
                newer.removeIntent(hash)
                newer.removeCommit(hash)
                assertTrue(newer.canRegister(hash))
                newer.save(hash, replacement)
            }
            // Even an *identical* stale native callback must not be accepted:
            // its in-memory equality cache predates the Remove generation.
            val error = runCatching { stale.save(hash, original) }.exceptionOrNull()
            assertTrue("Stale Android SQLite writer must reject even unchanged bytes: $error",
                error is IOException)
            val cached = runCatching { stale.current(hash) }.exceptionOrNull()
            assertTrue("A failed stale write must force read-only reconciliation: $cached",
                cached is IOException)
        }
        fresh().use { recovered ->
            assertProductionExtraSync(recovered)
            assertArrayEquals("Retired job was resurrected or new Add overwritten",
                replacement, recovered.current(hash))
            assertEquals(listOf(hash), recovered.restorable().map { it.first })
            assertTrue(recovered.problems().isEmpty())
        }
    }

}
