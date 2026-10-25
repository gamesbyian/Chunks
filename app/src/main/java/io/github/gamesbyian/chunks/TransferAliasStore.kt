package io.github.gamesbyian.chunks

import android.content.Context
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
    private val preferences = context.applicationContext.getSharedPreferences(
        "chunks_display_aliases_v1", Context.MODE_PRIVATE
    )

    fun get(endpoint: String, hash: String): String? =
        preferences.getString(key(endpoint, hash), null)?.let { stored ->
            runCatching { TransferDisplayNames.validateAlias(stored) }.getOrNull()
        }

    /** A null alias clears the override. A write failure must not be called a saved change. */
    fun save(endpoint: String, hash: String, alias: String?): Boolean {
        val preferenceKey = key(endpoint, hash)
        val editor = preferences.edit()
        if (alias == null) editor.remove(preferenceKey)
        else editor.putString(preferenceKey, TransferDisplayNames.validateAlias(alias))
        return editor.commit()
    }

    /**
     * One local preferences transaction for a reviewed batch. A fresh existing
     * alias, including a malformed legacy value, prevents every write rather
     * than allowing a partial overwrite of someone's manual label.
     */
    @Synchronized
    fun saveReviewedBatch(endpoint: String, writes: List<AliasReviewWrite>): Boolean {
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
        return editor.commit()
    }

    /**
     * Undo only while every currently stored alias still matches the exact
     * batch that Chunks applied. Never erase a newer manually edited alias.
     */
    @Synchronized
    fun undoReviewedBatch(endpoint: String, writes: List<AliasReviewWrite>): Boolean {
        if (endpoint.isBlank() || writes.isEmpty() ||
            writes.size > AliasReviewPlanner.MAX_REVIEW_ITEMS) return false
        val keys = writes.map { key(endpoint, it.hash) }
        if (keys.toSet().size != keys.size ||
            writes.indices.any { i -> preferences.getString(keys[i], null) != writes[i].alias })
            return false
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        return editor.commit()
    }

    private fun key(endpoint: String, hash: String): String {
        require(endpoint.isNotBlank() && hash.isNotBlank()) { "Missing transfer identity" }
        val identity = endpoint.trim() + "\u0000" + hash.trim().uppercase(Locale.ROOT)
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return "alias_" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
