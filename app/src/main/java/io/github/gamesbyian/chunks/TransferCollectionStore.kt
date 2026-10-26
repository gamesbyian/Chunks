package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.model.CollectionMembershipRules
import java.security.MessageDigest
import java.util.Locale

/**
 * Explicit Collections remain exclusively on this device. The storage key is
 * scoped to endpoint + account + verified infohash, matching transfer aliases.
 * No remote RPC, path renaming, tracker disclosure or filesystem operation.
 *
 * Collection names are user-entered text, never inferred/saved automatically.
 */
internal class TransferCollectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "chunks_collection_memberships_v1", Context.MODE_PRIVATE
    )
    private val hashPattern = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")

    fun forTransfer(scope: String, infoHash: String): List<String> =
        CollectionMembershipRules.ordered(read(scope, infoHash))

    @Synchronized
    fun add(scope: String, infoHash: String, name: String): Boolean {
        val original = read(scope, infoHash)
        val updated = CollectionMembershipRules.add(original, name) ?: return false
        if (updated == original) return true
        return prefs.edit().putStringSet(key(scope, infoHash), updated.toHashSet()).commit()
    }

    @Synchronized
    fun remove(scope: String, infoHash: String, name: String): Boolean {
        val original = read(scope, infoHash)
        if (original.none { it.equals(name, ignoreCase = true) }) return false
        val updated = CollectionMembershipRules.remove(original, name)
        val editor = prefs.edit()
        if (updated.isEmpty()) editor.remove(key(scope, infoHash))
        else editor.putStringSet(key(scope, infoHash), updated.toHashSet())
        return editor.commit()
    }

    private fun read(scope: String, infoHash: String): Set<String> {
        val stored = try { prefs.getStringSet(key(scope, infoHash), null)?.toSet().orEmpty() }
        catch (_: ClassCastException) { emptySet() }
        return CollectionMembershipRules.ordered(stored).toSet()
    }

    private fun key(scope: String, infoHash: String): String {
        require(scope.isNotBlank() && hashPattern.matches(infoHash)) { "Invalid scoped transfer identity" }
        val identity = scope + "\u0000" + infoHash.uppercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        return "membership_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
