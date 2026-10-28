package io.github.gamesbyian.chunks.snapshots

import android.content.Context

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

    override fun read(key: String): String? = preferences.getString(key, null)

    override fun commit(key: String, value: String): Boolean =
        preferences.edit().putString(key, value).commit()
}
