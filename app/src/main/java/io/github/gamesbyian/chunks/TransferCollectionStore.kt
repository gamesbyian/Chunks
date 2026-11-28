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
    companion object {
        // Every instance addresses the same process-local preferences file.
        private val editLock = Any()
        // SharedPreferences commit(false) may already have changed the process cache.
        // A cached membership is not durable evidence until explicitly re-committed.
        private val unverifiedKeys = mutableSetOf<String>()

        /** Separate from every URL+account scope; no engine side effects. */
        const val LOCAL_ENGINE_SCOPE = "\u0001chunks-local-private-v1"
    }

    data class Observation(val names: List<String>, val writeUnverified: Boolean)

    private val prefs = context.applicationContext.getSharedPreferences(
        "chunks_collection_memberships_v1", Context.MODE_PRIVATE
    )
    private val hashPattern = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")

    /** Best-effort read-only projection for aggregate collection views; never edit authority. */
    fun forTransfer(scope: String, infoHash: String): List<String> =
        synchronized(editLock) { CollectionMembershipRules.ordered(readOrNull(key(scope, infoHash)).orEmpty()) }

    /** Strict observation; missing is empty, invalid data is unavailable, uncertain writes are flagged. */
    fun observeForEdit(scope: String, infoHash: String): Observation = synchronized(editLock) {
        val key = key(scope, infoHash)
        Observation(CollectionMembershipRules.ordered(read(key)), key in unverifiedKeys)
    }

    /** Confirmed read only; callers must not mistake an uncertain cached value for a durable one. */
    fun readForEdit(scope: String, infoHash: String): List<String> = observeForEdit(scope, infoHash).let {
        check(!it.writeUnverified) { "Membership write not verified" }
        it.names
    }

    fun add(scope: String, infoHash: String, name: String): Boolean = synchronized(editLock) {
        val key = key(scope, infoHash)
        if (key in unverifiedKeys) return@synchronized false
        val original = readOrNull(key) ?: return@synchronized false
        val updated = CollectionMembershipRules.add(original, name) ?: return@synchronized false
        if (updated == original) return@synchronized true
        commit(mapOf(key to updated))
    }

    /**
     * Validate every reviewed member before opening an editor. An invalid or
     * uncertain member refuses the whole batch without silently applying a subset.
     */
    fun addBatch(scope: String, hashes: Collection<String>, proposedName: String): Boolean = synchronized(editLock) {
        if (scope.isBlank() || hashes.isEmpty() || hashes.size > 512) return@synchronized false
        val collection = CollectionMembershipRules.cleanName(proposedName) ?: return@synchronized false
        val normalized = hashes.map { it.trim().uppercase(Locale.ROOT) }
        if (normalized.any { !hashPattern.matches(it) } ||
            normalized.distinct().size != normalized.size) return@synchronized false

        val updates = LinkedHashMap<String, Set<String>>()
        for (hash in normalized) {
            val key = key(scope, hash)
            if (key in unverifiedKeys) return@synchronized false
            val before = readOrNull(key) ?: return@synchronized false
            val after = CollectionMembershipRules.add(before, collection) ?: return@synchronized false
            if (after != before) updates[key] = after
        }
        if (updates.isEmpty()) return@synchronized true
        commit(updates)
    }

    fun remove(scope: String, infoHash: String, name: String): Boolean = synchronized(editLock) {
        val key = key(scope, infoHash)
        if (key in unverifiedKeys) return@synchronized false
        val original = readOrNull(key) ?: return@synchronized false
        if (original.none { it.equals(name, ignoreCase = true) }) return@synchronized false
        commit(mapOf(key to CollectionMembershipRules.remove(original, name)))
    }

    /** Explicit recovery of the exact displayed set, never a stale automatic retry. */
    fun confirmObserved(scope: String, infoHash: String, expected: List<String>): Boolean = synchronized(editLock) {
        val key = key(scope, infoHash)
        if (key !in unverifiedKeys) return@synchronized false
        val current = readOrNull(key) ?: return@synchronized false
        if (CollectionMembershipRules.ordered(current) != expected) return@synchronized false
        commit(mapOf(key to current))
    }

    /** Called only under the shared edit lock; false/exception leaves each affected key fenced. */
    private fun commit(updates: Map<String, Set<String>>): Boolean {
        unverifiedKeys.addAll(updates.keys)
        return try {
            val editor = prefs.edit()
            for ((key, members) in updates) {
                if (members.isEmpty()) editor.remove(key)
                else editor.putStringSet(key, members.toHashSet())
            }
            val verified = editor.commit() && updates.all { (key, members) -> read(key) == members }
            if (verified) unverifiedKeys.removeAll(updates.keys)
            verified
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun read(key: String): Set<String> {
        val stored = prefs.getStringSet(key, null)?.toSet().orEmpty()
        // Never normalize malformed stored state into a silently writable subset.
        check(stored.size <= CollectionMembershipRules.MAX_PER_TRANSFER &&
            stored.all { CollectionMembershipRules.cleanName(it) == it } &&
            CollectionMembershipRules.ordered(stored).size == stored.size) {
            "Invalid stored collection membership"
        }
        return stored
    }

    private fun readOrNull(key: String): Set<String>? =
        try { read(key) } catch (_: ClassCastException) { null } catch (_: IllegalStateException) { null }

    private fun key(scope: String, infoHash: String): String {
        require(scope.isNotBlank() && hashPattern.matches(infoHash)) { "Invalid scoped transfer identity" }
        val identity = scope + "\u0000" + infoHash.uppercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        return "membership_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
