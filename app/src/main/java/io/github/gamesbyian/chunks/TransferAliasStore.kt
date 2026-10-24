package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.model.TransferDisplayNames
import java.security.MessageDigest
import java.util.Locale

/**
 * Device-local, Chunks-owned aliases. Preference keys are fingerprints of the connected endpoint
 * and infohash, never torrent names, credentials, metainfo or remote paths.
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

    private fun key(endpoint: String, hash: String): String {
        require(endpoint.isNotBlank() && hash.isNotBlank()) { "Missing transfer identity" }
        val identity = endpoint.trim() + "\u0000" + hash.trim().uppercase(Locale.ROOT)
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return "alias_" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
