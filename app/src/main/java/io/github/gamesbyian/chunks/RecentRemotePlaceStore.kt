package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.rpc.StoppedRemoteAddPlan
import java.security.MessageDigest
import org.json.JSONArray

/**
 * Only locations of *verified successful custom Adds*, never guessed or failed
 * targets. A recent is a suggestion, not proof that the host path still exists
 * or permits writes today. Using it still requires the regular Add readback.
 *
 * Scope includes the current endpoint and authenticated account, then is
 * irreversibly fingerprinted before becoming a preference key. Paths are
 * device-private Chunks UI metadata; no passwords, magnets or torrent names.
 */
internal class RecentRemotePlaceStore(context: Context) {
    companion object {
        const val MAX_RECENT = 8
        private val lock = Any()
        private const val PREFS = "chunks_recent_remote_places_v1"
        private const val VALIDATION_MAGNET =
            "magnet:?xt=urn:btih:0000000000000000000000000000000000000000"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun places(scope: String): List<String> = synchronized(lock) {
        decode(prefs.getString(key(scope), null))
    }

    /**
     * Called only after exact-hash/destination readback of a custom Add.
     * Return false on persistence failure; no optimistic phantom recent.
     */
    fun recordVerifiedCustom(scope: String, parent: String): Boolean = synchronized(lock) {
        require(validParent(parent)) { "Invalid remote parent Place" }
        val prefKey = key(scope)
        val newList = (listOf(parent) + decode(prefs.getString(prefKey, null)))
            .distinct().take(MAX_RECENT)
        val packed = JSONArray().apply { newList.forEach { put(it) } }.toString()
        prefs.edit().putString(prefKey, packed).commit()
    }

    private fun decode(value: String?): List<String> {
        if (value == null || value.length !in 2..(MAX_RECENT * 1150)) return emptyList()
        return try {
            val array = JSONArray(value)
            if (array.length() > 64) emptyList()
            else (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf(::validParent)
            }.distinct().take(MAX_RECENT)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun validParent(value: String): Boolean =
        runCatching { StoppedRemoteAddPlan.fromMagnet(VALIDATION_MAGNET, value) }.isSuccess

    private fun key(scope: String): String {
        require(scope.isNotBlank() && scope.length <= 8192) { "Invalid remote profile scope" }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(scope.toByteArray(Charsets.UTF_8))
        return "scope_" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
