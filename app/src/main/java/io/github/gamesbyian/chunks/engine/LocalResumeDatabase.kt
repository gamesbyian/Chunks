package io.github.gamesbyian.chunks.engine

import android.database.DatabaseErrorHandler
import android.database.CursorWindow
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Android's ordinary transactional private metadata store. One row per hash,
 * EXTRA-synchronous DELETE-journal commits, no permanent per-save files.
 * Immutable pre-upgrade inputs are read once and never cleaned up by pathname.
 */
internal class LocalResumeDatabase private constructor(
    private val database: SQLiteDatabase,
    private val states: MutableMap<String, LocalResumeSnapshot>
) : LocalResumeStore {
    private var uncertain = false

    companion object {
        internal const val DIRECTORY = "resume-database-v1"
        internal const val DATABASE = "state.sqlite"
        private const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        private const val APPLICATION_ID = 0x43484b53
        private val HASH = Regex("(?:[a-f0-9]{40}|[a-f0-9]{64})")
        private val PHASES = setOf("EMPTY", "SAVED", "REMOVE_INTENT", "REMOVED", "CORRUPT")
        private val MARKER = "CHUNKS-RESUME-SQLITE-1\n".toByteArray(Charsets.US_ASCII)
        private val AUTHORITY = "CHUNKS-RESUME-SQLITE-AUTHORITY-1\n".toByteArray(Charsets.US_ASCII)

        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

        private fun syncDirectory(directory: File) {
            val fd = Os.open(directory.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw IOException("Unsafe resume directory")
                Os.fsync(fd)
            } finally { Os.close(fd) }
        }

        private fun namespace(root: File): File {
            require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
            val directory = File(root, DIRECTORY)
            if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(directory.toPath())
                syncDirectory(root)
            }
            if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
                throw IOException("Resume database directory is unavailable")
            val marker = File(directory, "format")
            if (!Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (directory.listFiles()?.isEmpty() != true)
                    throw IOException("Unrecognized resume database directory; files preserved")
                FileChannel.open(marker.toPath(), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    val buffer = ByteBuffer.wrap(MARKER)
                    while (buffer.hasRemaining()) {
                        if (channel.write(buffer) <= 0) throw IOException("Incomplete resume database marker")
                    }
                    channel.force(true)
                }
                syncDirectory(directory)
            }
            if (LocalResumeFileReader.read(marker, MARKER.size.toLong())?.contentEquals(MARKER) != true)
                throw IOException("Unrecognized resume database marker; files preserved")
            val allowed = setOf("format", "authority", DATABASE, "$DATABASE-journal", "$DATABASE-wal", "$DATABASE-shm")
            val files = directory.listFiles() ?: throw IOException("Resume database cannot be listed")
            for (file in files) {
                val stat = try { Os.lstat(file.absolutePath) } catch (failure: ErrnoException) {
                    // SQLite may retire its own journal between list and stat.
                    // A missing main database/marker is still an error.
                    if (failure.errno == OsConstants.ENOENT &&
                        file.name in setOf("$DATABASE-journal", "$DATABASE-wal", "$DATABASE-shm")) continue
                    throw failure
                }
                if (file.name !in allowed || !OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L)
                    throw IOException("Unsafe resume database entry; files preserved")
            }
            return directory
        }

        fun open(root: File, inventory: List<File>): LocalResumeDatabase {
            val directory = namespace(root)
            val file = File(directory, DATABASE)
            val authority = File(directory, "authority")
            val authoritative = Files.exists(authority.toPath(), LinkOption.NOFOLLOW_LINKS)
            if (authoritative &&
                LocalResumeFileReader.read(authority, AUTHORITY.size.toLong())?.contentEquals(AUTHORITY) != true)
                throw IOException("Resume authority receipt is incomplete; files preserved")
            if (authoritative && !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
                throw IOException("Authoritative resume database is missing; legacy restore refused")
            // The marker identifies a dedicated app-private SQLite namespace.
            // Never apply SQLite's default corrupt-database deletion handler.
            val database = SQLiteDatabase.openDatabase(file.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or
                    SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                DatabaseErrorHandler { /* Preserve corrupt database and sidecars for recovery. */ })
            try {
                val appId = database.rawQuery("PRAGMA application_id", null).use { cursor ->
                    check(cursor.moveToFirst()); cursor.getInt(0)
                }
                val tables = database.rawQuery(
                    "SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'", null
                ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                val knownSchema = when (database.version) {
                    0 -> !authoritative && appId == 0 && tables.isEmpty()
                    1 -> appId == APPLICATION_ID && tables == setOf("resumes")
                    else -> false
                }
                if (!knownSchema)
                    throw IOException("Unrecognized resume database schema; files preserved")
                // Empty/version-zero SQLite is possible after an interrupted initial import.
                database.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                    if (!cursor.moveToFirst() || !cursor.getString(0).equals("delete", true))
                        throw IOException("Resume database journal mode is unavailable")
                }
                // DELETE journal commits retire their journal by unlink. SQLite
                // documents that FULL may lose the last committed transaction
                // on sudden power loss; EXTRA also syncs that directory entry.
                // In particular, a lost REMOVE_INTENT could resurrect a job.
                database.execSQL("PRAGMA synchronous=EXTRA")
                database.rawQuery("PRAGMA synchronous", null).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getInt(0) != 3)
                        throw IOException("Durable resume synchronization is unavailable")
                }
                if (database.version == 0) {
                    val migrated = LocalResumeEventStore.open(root, inventory).snapshots()
                    database.beginTransaction()
                    try {
                        database.execSQL("CREATE TABLE resumes (" +
                            "hash TEXT PRIMARY KEY NOT NULL, phase TEXT NOT NULL, payload BLOB, " +
                            "digest TEXT, source TEXT NOT NULL, problem TEXT, revision INTEGER NOT NULL)")
                        for (record in migrated) write(database, record)
                        database.execSQL("PRAGMA application_id=$APPLICATION_ID")
                        database.version = 1
                        database.setTransactionSuccessful()
                    } finally { database.endTransaction() }
                    syncDirectory(directory)
                }
                val states = LinkedHashMap<String, LocalResumeSnapshot>()
                database.rawQuery("SELECT hash, phase, payload, digest, source, problem, revision, length(payload) FROM resumes ORDER BY hash", null)
                    .use { cursor ->
                        // Android's default ~2 MiB window cannot hold a large
                        // supported torrent's resume BLOB. Bound this window
                        // to the existing 16 MiB snapshot limit plus row metadata.
                        (cursor as SQLiteCursor).window = CursorWindow("Chunks resume", MAX_PAYLOAD_BYTES + 64 * 1024L)
                        while (cursor.moveToNext()) {
                            val key = cursor.getString(0)
                            val phase = cursor.getString(1)
                            if (!cursor.isNull(7) && cursor.getLong(7) !in 1L..MAX_PAYLOAD_BYTES.toLong())
                                throw IOException("Resume database payload exceeds supported size")
                            val payload = if (cursor.isNull(2)) null else cursor.getBlob(2)
                            val sha = if (cursor.isNull(3)) null else cursor.getString(3)
                            val source = cursor.getString(4)
                            val problem = if (cursor.isNull(5)) null else cursor.getString(5)
                            val revision = cursor.getLong(6)
                            if (!HASH.matches(key) || phase !in PHASES ||
                                (phase == "SAVED") != (payload != null) ||
                                payload != null && (payload.size !in 1..MAX_PAYLOAD_BYTES || digest(payload) != sha) ||
                                payload == null && sha != null ||
                                (phase == "CORRUPT") != (problem != null) || revision < 0 ||
                                source !in setOf("$key.resume", "$key.resume-event", "$key.resume-database"))
                                throw IOException("Invalid resume database record; restoration refused")
                            states[key] = LocalResumeSnapshot(key, phase, payload, source, problem, revision)
                        }
                    }
                if (!authoritative) {
                    // Publish only after the imported transaction is committed
                    // and verified, BEFORE any native job can use this store.
                    // Later database loss/truncation must never resurrect the
                    // preserved pre-migration snapshots.
                    FileChannel.open(authority.toPath(), StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                        val buffer = ByteBuffer.wrap(AUTHORITY)
                        while (buffer.hasRemaining()) {
                            if (channel.write(buffer) <= 0) throw IOException("Incomplete resume authority receipt")
                        }
                        channel.force(true)
                    }
                    syncDirectory(directory)
                }
                return LocalResumeDatabase(database, states)
            } catch (failure: Exception) {
                database.close()
                throw failure
            }
        }

        private fun write(database: SQLiteDatabase, record: LocalResumeSnapshot) {
            // Bind values without insertWithOnConflict's error logging of
            // ContentValues (which can contain private transfer identity).
            database.execSQL("INSERT OR REPLACE INTO resumes " +
                "(hash,phase,payload,digest,source,problem,revision) VALUES (?,?,?,?,?,?,?)",
                arrayOf(record.key, record.phase, record.payload, record.payload?.let(::digest),
                    record.sourceName, record.problem, record.revision))
        }
    }

    private fun healthy() {
        if (uncertain || !database.isOpen) throw IOException("Resume persistence is uncertain; reopen to reconcile")
    }

    @Synchronized override fun canRegister(key: String): Boolean {
        healthy(); require(HASH.matches(key))
        return states[key]?.phase in setOf(null, "EMPTY", "REMOVED")
    }

    @Synchronized override fun restorable(): List<Pair<String, ByteArray>> {
        healthy()
        return states.values.mapNotNull { record -> record.payload?.let { record.key to it.copyOf() } }
    }

    @Synchronized override fun problems(): List<String> { healthy(); return states.values.mapNotNull { it.problem } }
    @Synchronized override fun current(key: String): ByteArray? { healthy(); return states[key]?.payload?.copyOf() }
    @Synchronized override fun savedSourceName(key: String): String {
        healthy(); return states[key]?.sourceName ?: "$key.resume-database"
    }

    private fun commit(key: String, phase: String, bytes: ByteArray?) {
        healthy(); require(HASH.matches(key))
        val previous = states[key]?.phase
        val allowed = when (phase) {
            "SAVED", "REMOVE_INTENT" -> previous in setOf(null, "EMPTY", "SAVED", "REMOVED") &&
                (phase != "REMOVE_INTENT" || previous != "REMOVED")
            "REMOVED" -> previous == "REMOVE_INTENT"
            else -> false
        }
        if (!allowed) throw IOException("Unsafe resume database transition")
        if (phase == "SAVED" && (bytes == null || bytes.size !in 1..MAX_PAYLOAD_BYTES))
            throw IOException("Invalid resume snapshot size")
        val unchanged = phase == "SAVED" && states[key]?.payload?.contentEquals(bytes) == true
        val previousRevision = states[key]?.revision
        if (previousRevision == Long.MAX_VALUE) throw IOException("Resume revision exhausted")
        val record = LocalResumeSnapshot(key, phase, bytes?.copyOf(), "$key.resume-database",
            revision = (previousRevision ?: 0L) + 1)
        try {
            database.beginTransaction()
            try {
                val observedRevision = database.rawQuery("SELECT revision FROM resumes WHERE hash=?", arrayOf(key))
                    .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                if (observedRevision != previousRevision)
                    throw IOException("Resume state changed in another writer; reopen to reconcile")
                if (!unchanged) write(database, record)
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
            if (!unchanged) states[key] = record
        } catch (failure: Exception) {
            uncertain = true
            throw IOException("Resume transaction outcome requires reopening", failure)
        }
    }

    @Synchronized override fun save(key: String, bytes: ByteArray) = commit(key, "SAVED", bytes)
    @Synchronized override fun removeIntent(key: String) = commit(key, "REMOVE_INTENT", null)
    @Synchronized override fun removeCommit(key: String) = commit(key, "REMOVED", null)
    @Synchronized override fun close() = database.close()
}
