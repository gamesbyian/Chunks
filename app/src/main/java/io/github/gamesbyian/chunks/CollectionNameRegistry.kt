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
    private companion object {
        // Several activities/tests may construct independent registry instances
        // sharing one Preferences file. Per-instance @Synchronized would lose
        // read/modify/write updates from concurrent instances in this process.
        val editLock = Any()
    }
    private val prefs = context.applicationContext.getSharedPreferences(
        "chunks_collection_names_v1", Context.MODE_PRIVATE
    )

    fun names(scope: String): List<String> = synchronized(editLock) {
        val stored = try { prefs.getStringSet(key(scope), null)?.toSet().orEmpty() }
        catch (_: ClassCastException) { emptySet() }
        CollectionNameRegistryRules.ordered(stored)
    }

    /** True only if the validated name was already present or was saved. */
    fun create(scope: String, proposed: String): Boolean = synchronized(editLock) {
        val previous = names(scope)
        val updated = CollectionNameRegistryRules.add(previous, proposed)
            ?: return@synchronized false
        if (updated == previous) true
        else prefs.edit().putStringSet(key(scope), updated.toSet()).commit()
    }

    /**
     * Forget a reusable name, not any membership. Transfers carrying this
     * Collection are unaffected and may still show the same name.
     */
    fun forget(scope: String, name: String): Boolean = synchronized(editLock) {
        val previous = names(scope)
        if (previous.none { it.equals(name, ignoreCase = true) }) return@synchronized false
        val updated = previous.filterNot { it.equals(name, ignoreCase = true) }
        val edit = prefs.edit()
        if (updated.isEmpty()) edit.remove(key(scope))
        else edit.putStringSet(key(scope), updated.toSet())
        edit.commit()
    }

    private fun key(scope: String): String {
        require(scope.isNotBlank() && scope.length <= 8192) { "Invalid Collection scope" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scope.toByteArray(Charsets.UTF_8))
        return "scope_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
