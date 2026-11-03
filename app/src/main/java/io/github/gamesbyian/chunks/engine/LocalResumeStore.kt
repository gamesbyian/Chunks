package io.github.gamesbyian.chunks.engine

import android.os.Build
import java.io.File

/** One resume authority per engine session; desktop JNI fixtures retain the legacy reader/writer. */
internal interface LocalResumeStore : AutoCloseable {
    fun canRegister(key: String): Boolean
    /** Valid fsynced intents awaiting confirmation from a *new* empty native session. */
    fun pendingRemovals(): List<String>
    fun restorable(): List<Pair<String, ByteArray>>
    fun problems(): List<String>
    fun current(key: String): ByteArray?
    fun savedSourceName(key: String): String
    fun save(key: String, bytes: ByteArray)
    fun removeIntent(key: String)
    fun removeCommit(key: String)
    override fun close() {}

    companion object {
        fun open(directory: File, inventory: List<File>): LocalResumeStore {
            val android = try { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q }
                catch (_: NoClassDefFoundError) { false }
            return if (android) LocalResumeDatabase.open(directory, inventory)
                else LocalResumeEventStore.open(directory, inventory)
        }
    }
}

internal data class LocalResumeSnapshot(
    val key: String,
    val phase: String,
    val payload: ByteArray?,
    val sourceName: String,
    val problem: String? = null,
    val revision: Long = 0L
)
