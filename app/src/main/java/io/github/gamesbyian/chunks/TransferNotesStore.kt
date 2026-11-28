package io.github.gamesbyian.chunks

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

/**
 * Private, device-local user notes. Exact authenticated account scope + verified
 * infohash; no transfer metadata, engine, tracker, file or provider mutation.
 * A note is deliberately not a display alias, backend field or export payload.
 */
internal class TransferNotesStore(context: Context) {
    companion object {
        const val MAX_LENGTH = 4_000
        private val lock = Any()
        private val hashPattern = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")
    }

    enum class SaveStatus { VERIFIED, CONFLICT, UNVERIFIED }
    data class SaveResult(val status: SaveStatus, val observed: String)

    private val prefs = context.applicationContext.getSharedPreferences(
        "chunks_private_transfer_notes_v1", Context.MODE_PRIVATE
    )

    /** Missing is empty. Unreadable or invalid stored data must not become a writable empty note. */
    fun read(scope: String, hash: String): String = synchronized(lock) {
        readKey(key(scope, hash))
    }

    /** Compare the last reviewed value, commit explicitly, then observe the same key. */
    fun save(scope: String, hash: String, expected: String, proposed: String): SaveResult =
        synchronized(lock) {
            require(valid(proposed)) { "Invalid note" }
            val key = key(scope, hash)
            val before = readKey(key)
            if (before != expected) return@synchronized SaveResult(SaveStatus.CONFLICT, before)
            if (before == proposed) return@synchronized SaveResult(SaveStatus.VERIFIED, before)
            val editor = prefs.edit()
            if (proposed.isEmpty()) editor.remove(key) else editor.putString(key, proposed)
            val committed = editor.commit()
            val observed = readKey(key)
            SaveResult(
                if (committed && observed == proposed) SaveStatus.VERIFIED else SaveStatus.UNVERIFIED,
                observed
            )
        }

    private fun readKey(key: String): String {
        val value = prefs.getString(key, null).orEmpty()
        check(valid(value)) { "Stored note is invalid" }
        return value
    }

    private fun valid(value: String): Boolean = value.length <= MAX_LENGTH && '\u0000' !in value

    private fun key(scope: String, hash: String): String {
        require(scope.isNotBlank() && scope.length <= 8192 && hashPattern.matches(hash)) {
            "Invalid exact transfer identity"
        }
        val identity = scope + "\u0000" + hash.uppercase(Locale.ROOT)
        return "note_" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
