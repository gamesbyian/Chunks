package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.model.StorageBudget
import io.github.gamesbyian.chunks.model.StorageWarning
import io.github.gamesbyian.chunks.model.StorageWarningHysteresis
import java.io.File
import java.security.MessageDigest

/**
 * Opt-in *advisory* limit for one app-specific phone download root.
 *
 * The fingerprinted canonical root prevents silently applying an old setting
 * to a different mounted volume. No transfer pausing, notifications, physical
 * disk-allocation claim, backing-file deletion or remote-server operations.
 */
internal class LocalCapPreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "chunks-local-cap-v1", Context.MODE_PRIVATE
    )

    private fun key(root: File): String? {
        val path = try { root.canonicalPath } catch (_: Exception) { return null }
        if (!root.isDirectory) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun get(root: File?): Long? {
        val id = root?.let(::key) ?: return null
        val raw = try { preferences.getLong("cap_" + id, 0L) }
            catch (_: ClassCastException) { 0L }
        return raw.takeIf { it > 0L }
    }

    /** Commit failure must be shown as a failed setting save. */
    fun set(root: File?, bytes: Long?): Boolean {
        if (bytes != null && bytes <= 0L) return false
        val id = root?.let(::key) ?: return false
        val edit = preferences.edit()
        if (bytes == null) edit.remove("cap_" + id)
        else edit.putLong("cap_" + id, bytes)
        // Threshold history belongs to this cap and resets on explicit edit.
        return edit.remove("latch_" + id).commit()
    }

    /** Only call after a COMPLETE measured scan. No automatic alerts are posted. */
    fun observe(root: File?, capBytes: Long?, usedBytes: Long, freeBytes: Long?): StorageWarning? {
        if (capBytes == null || usedBytes < 0L) return null
        val id = root?.let(::key) ?: return null
        val old = preferences.getString("latch_" + id, null)?.let {
            runCatching { StorageWarning.valueOf(it) }.getOrNull()
        } ?: StorageWarning.NONE
        val decision = StorageWarningHysteresis.transition(
            StorageBudget(id, capBytes, usedBytes, freeBytes), old
        )
        // A failure to record the latch must not result in claiming a new notice.
        return if (preferences.edit().putString("latch_" + id, decision.nextLatch.name).commit())
            decision.newNotice else null
    }
}
