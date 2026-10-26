package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.model.CollectionNameRegistryRules
import java.security.MessageDigest

/**
 * User-created, empty-or-populated Collection *names* on this device.
 * A registry entry grants no storage permission, membership or write action.
 *
 * Keys use the same remote endpoint/account scope as transfer membership;
 * the local libtorrent engine uses its own reserved scope. Collection names
 * from unloaded remote views remain visible once explicitly registered.
 */
internal class CollectionNameRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "chunks_collection_names_v1", Context.MODE_PRIVATE
    )

    @Synchronized
    fun names(scope: String): List<String> {
        val stored = try { prefs.getStringSet(key(scope), null)?.toSet().orEmpty() }
        catch (_: ClassCastException) { emptySet() }
        return CollectionNameRegistryRules.ordered(stored)
    }

    /** True only if the validated name was already present or was saved. */
    @Synchronized
    fun create(scope: String, proposed: String): Boolean {
        val previous = names(scope)
        val updated = CollectionNameRegistryRules.add(previous, proposed) ?: return false
        if (updated == previous) return true
        return prefs.edit().putStringSet(key(scope), updated.toSet()).commit()
    }

    /**
     * Forget a reusable name, not any membership. Transfers carrying this
     * Collection are unaffected and may still show the same name.
     */
    @Synchronized
    fun forget(scope: String, name: String): Boolean {
        val previous = names(scope)
        if (previous.none { it.equals(name, ignoreCase = true) }) return false
        val updated = previous.filterNot { it.equals(name, ignoreCase = true) }
        val edit = prefs.edit()
        if (updated.isEmpty()) edit.remove(key(scope))
        else edit.putStringSet(key(scope), updated.toSet())
        return edit.commit()
    }

    private fun key(scope: String): String {
        require(scope.isNotBlank() && scope.length <= 8192) { "Invalid Collection scope" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scope.toByteArray(Charsets.UTF_8))
        return "scope_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
