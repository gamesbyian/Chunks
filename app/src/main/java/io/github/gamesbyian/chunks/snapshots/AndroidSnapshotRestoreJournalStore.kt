package io.github.gamesbyian.chunks.snapshots

import android.content.Context
import java.io.IOException

/**
 * Android-private, synchronous persistence for the import journal.
 * SharedPreferences.commit() is intentional: apply() would let an Add reach
 * the engine before IN_FLIGHT is durable across process termination.
 *
 * android:allowBackup=false keeps this hash-only journal device-local.
 * No torrent metadata, server address, credentials, path or passkey is stored.
 */
internal class AndroidSnapshotRestoreJournalStore(context: Context) :
    SnapshotRestoreJournalV1.Store {
    private val preferences = context.applicationContext.getSharedPreferences(
        "chunks_restore_journal_v1", Context.MODE_PRIVATE)

    override fun read(key: String): String? = try {
        preferences.getString(key, null)
    } catch (_: ClassCastException) {
        // This is an OCCUPIED but unreadable journal, not a missing one.
        // SnapshotRestoreJournalV1.open() must not initialize a new REVIEW
        // batch and later authorize duplicate Adds over the unknown old state.
        // Preserve the original value for explicit forensic/user recovery.
        throw IOException("Saved snapshot import journal is unreadable; no Add is authorized")
    }

    override fun compareAndCommit(
        key: String, expected: String?, value: String
    ): Boolean = synchronized(processLock) {
        // A wrong-type occupant must never be replaced by an ordinary CAS.
        // Fail closed without deleting or changing the original preference.
        val stored = try { preferences.getString(key, null) }
            catch (_: ClassCastException) { return@synchronized false }
        if (stored != expected) return@synchronized false
        preferences.edit().putString(key, value).commit()
    }

    companion object {
        /** Shared by every adapter instance in this app process. */
        private val processLock = Any()
    }
}
