package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import io.github.gamesbyian.chunks.model.AliasReviewWrite
import io.github.gamesbyian.chunks.model.AliasReviewPlanner
import io.github.gamesbyian.chunks.model.TransferDisplayNames
import java.security.MessageDigest
import java.util.Locale

/**
 * Device-local, Chunks-owned aliases. Preference keys are fingerprints of the connected endpoint
 * and infohash, never torrent names, credentials, metainfo or remote paths.
 * The UI includes the authenticated account name in the scope so two accounts at one RPC URL
 * cannot accidentally share aliases. The scope itself is fingerprinted, not persisted directly.
 *
 * A stable server profile identifier can replace the endpoint scope once profiles are implemented.
 * Importantly, saving an alias here cannot call rTorrent or change any backing file.
 */
internal class TransferAliasStore(context: Context) {
    companion object {
        private val currentRevision = mutableIntStateOf(0)
        // All UI/store instances share the same preferences file. A Move's
        // "copy only if target is absent" must be atomic against manual edits.
        private val aliasWriteLock = Any()
        val revision: Int get() = currentRevision.intValue
        private fun changed() { currentRevision.intValue++ }
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        "chunks_display_aliases_v1", Context.MODE_PRIVATE
    )

    fun get(endpoint: String, hash: String): String? =
        preferences.getString(key(endpoint, hash), null)?.let { stored ->
            runCatching { TransferDisplayNames.validateAlias(stored) }.getOrNull()
        }

    /**
     * One preferences snapshot for a scoped set of transfer identities. This
     * can hash thousands of keys, so callers MUST run it off the UI thread.
     * Malformed persisted aliases are ignored rather than displayed.
     */
    fun getBatch(requests: Set<CombinedAliasRequest>): Map<CombinedAliasRequest, String> {
        if (requests.isEmpty()) return emptyMap()
        val stored = preferences.all
        return buildMap {
            requests.forEach { request ->
                val value = stored[key(request.aliasScope, request.key.infoHash)] as? String
                    ?: return@forEach
                val alias = runCatching { TransferDisplayNames.validateAlias(value) }.getOrNull()
                    ?: return@forEach
                put(request, alias)
            }
        }
    }

    /** A null alias clears the override. A write failure must not be called a saved change. */
    fun save(endpoint: String, hash: String, alias: String?): Boolean = synchronized(aliasWriteLock) {
        val preferenceKey = key(endpoint, hash)
        val editor = preferences.edit()
        if (alias == null) editor.remove(preferenceKey)
        else editor.putString(preferenceKey, TransferDisplayNames.validateAlias(alias))
        val committed = editor.commit()
        if (committed) changed()
        committed
    }

    /** A new Add may preselect a Chunks-only display name. It may *never*
     * overwrite an existing manual alias, even if that alias was edited while
     * the torrent's network readback was pending. No torrent/backend mutation.
     * Caller supplies an independently verified NEW registration exact hash
     * from the existing phone/remote coordinator. */
    fun saveVerifiedNewAddAlias(scope: String, hash: String, input: String): Boolean =
        synchronized(aliasWriteLock) {
            if (scope.isBlank() || !Regex("[0-9a-fA-F]{40}").matches(hash)) return@synchronized false
            val normalized = runCatching {
                io.github.gamesbyian.chunks.model.TransferDisplayNames.validateAlias(input)
            }.getOrNull() ?: return@synchronized false
            val storageKey = runCatching { key(scope, hash) }.getOrNull()
                ?: return@synchronized false
            if (preferences.contains(storageKey)) return@synchronized false
            val saved = preferences.edit().putString(storageKey, normalized).commit()
            if (saved) changed()
            saved
        }

    /** Chunks-only display label carried after independent destination Add readback.
     * Caller must already have checked the exact Move journal, target hash and
     * explicit original Move review. Never reads/metainfo, calls engines, alters
     * filenames, or sends the alias over rTorrent. Target manual edits win. */
    enum class MoveAliasCarry { COPIED, SOURCE_UNSET, TARGET_ALREADY_SET, FAILED }

    fun carryVerifiedMoveAlias(
        sourceScope: String, targetScope: String, infoHash: String
    ): MoveAliasCarry = synchronized(aliasWriteLock) {
        try {
            if (!Regex("[0-9a-fA-F]{40}").matches(infoHash))
                return@synchronized MoveAliasCarry.FAILED
            val from = key(sourceScope, infoHash)
            val to = key(targetScope, infoHash)
            if (from == to) return@synchronized MoveAliasCarry.TARGET_ALREADY_SET
            // Even a malformed existing target value is user state, never
            // silent permission for an automated Move to overwrite it.
            // O(1) exact-key reads: never scan the full alias map on the
            // Compose click path, which may contain thousands of labels.
            if (preferences.contains(to)) return@synchronized MoveAliasCarry.TARGET_ALREADY_SET
            val alias = runCatching { preferences.getString(from, null) }.getOrNull()
                ?.let { runCatching { TransferDisplayNames.validateAlias(it) }.getOrNull() }
                ?: return@synchronized MoveAliasCarry.SOURCE_UNSET
            if (!preferences.edit().putString(to, alias).commit())
                MoveAliasCarry.FAILED
            else {
                changed()
                MoveAliasCarry.COPIED
            }
        } catch (_: RuntimeException) { MoveAliasCarry.FAILED }
    }

    /**
     * One local preferences transaction for a reviewed batch. A fresh existing
     * alias, including a malformed legacy value, prevents every write rather
     * than allowing a partial overwrite of someone's manual label.
     */
    @Synchronized
    fun saveReviewedBatch(endpoint: String, writes: List<AliasReviewWrite>): Boolean = synchronized(aliasWriteLock) {
        if (endpoint.isBlank() || writes.isEmpty() ||
            writes.size > AliasReviewPlanner.MAX_REVIEW_ITEMS) return false
        val keys = writes.map { entry ->
            require(Regex("[0-9a-fA-F]{40}").matches(entry.hash))
            require(TransferDisplayNames.validateAlias(entry.alias) == entry.alias)
            require(entry.alias != entry.originalName)
            key(endpoint, entry.hash)
        }
        if (keys.toSet().size != keys.size || keys.any(preferences::contains)) return false
        val editor = preferences.edit()
        writes.forEachIndexed { i, entry -> editor.putString(keys[i], entry.alias) }
        val committed = editor.commit()
        if (committed) changed()
        return committed
    }

    /**
     * Undo only while every currently stored alias still matches the exact
     * batch that Chunks applied. Never erase a newer manually edited alias.
     */
    @Synchronized
    fun undoReviewedBatch(endpoint: String, writes: List<AliasReviewWrite>): Boolean = synchronized(aliasWriteLock) {
        if (endpoint.isBlank() || writes.isEmpty() ||
            writes.size > AliasReviewPlanner.MAX_REVIEW_ITEMS) return false
        val keys = writes.map { key(endpoint, it.hash) }
        if (keys.toSet().size != keys.size ||
            writes.indices.any { i -> preferences.getString(keys[i], null) != writes[i].alias })
            return false
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        val committed = editor.commit()
        if (committed) changed()
        return committed
    }

    private fun key(endpoint: String, hash: String): String {
        require(endpoint.isNotBlank() && hash.isNotBlank()) { "Missing transfer identity" }
        // The server scope is endpoint + NUL + the *exact* authenticated
        // username. Trimming the entire scope silently drops trailing spaces
        // from usernames, potentially projecting one account's private alias
        // onto another account on the same RPC endpoint.
        val separator = endpoint.indexOf('\u0000')
        val scope = if (separator < 0) endpoint.trim() else {
            require(separator > 0 && separator == endpoint.lastIndexOf('\u0000')) {
                "Invalid exact server account scope"
            }
            endpoint.substring(0, separator).trim() + '\u0000' +
                endpoint.substring(separator + 1)
        }
        require(scope.isNotBlank()) { "Missing transfer scope" }
        val identity = scope + "\u0000" + hash.trim().uppercase(Locale.ROOT)
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return "alias_" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
