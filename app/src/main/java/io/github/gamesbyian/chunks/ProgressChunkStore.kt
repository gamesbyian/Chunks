package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import io.github.gamesbyian.chunks.model.ProgressChunkHistory
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Presentation-only cache shared by phone, rTorrent and Combined lists. Each
 * key is the stable exact environment ID and infohash, never a display name.
 *
 * Read/write only from effects; no SharedPreferences I/O in composition or
 * drawing. Stored keys are digests so profile endpoints are not copied into
 * preference keys. The app's private preferences are not remote mutations.
 */
internal object ProgressChunkStore {
    private const val PREFS = "chunks_progress_observations_v1"
    private const val PREFIX = "progress:"
    private const val MAX_STORED = 512
    private val histories = mutableStateMapOf<String, ProgressChunkHistory>()

    internal fun identity(environment: String, infoHash: String): String? {
        if (environment.isBlank() ||
            !infoHash.matches(Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})"))) return null
        return environment + "\u0000" + infoHash.lowercase(java.util.Locale.ROOT)
    }

    private fun preferenceKey(identity: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        return PREFIX + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun current(identity: String): ProgressChunkHistory? = histories[identity]

    suspend fun observe(
        context: Context, identity: String, ppm: Int, minVisiblePpm: Int
    ) {
        if (ppm !in 0..1_000_000) return
        if (histories[identity] == null) {
            val saved = withContext(Dispatchers.IO) {
                val value = context.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(preferenceKey(identity), null)
                value?.substringAfter('|', missingDelimiterValue = "")
                    ?.let(ProgressChunkHistory::decode)
            }
            // Another visible surface may have installed a newer observation.
            if (histories[identity] == null) histories[identity] = saved ?: ProgressChunkHistory()
        }
        val previous = histories[identity] ?: ProgressChunkHistory()
        val next = previous.observe(ppm, minVisiblePpm)
        if (next == previous) return
        histories[identity] = next
        withContext(Dispatchers.IO) {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(
                preferenceKey(identity), System.currentTimeMillis().toString() + "|" + next.encode()
            ).apply()
            // Bound on-disk histories even after repeated add/remove/re-add.
            val entries = prefs.all.filterKeys { it.startsWith(PREFIX) }
            if (entries.size > MAX_STORED) {
                val excess = entries.size - MAX_STORED
                val oldest = entries.entries.sortedBy {
                    (it.value as? String)?.substringBefore('|')?.toLongOrNull() ?: 0L
                }.take(excess)
                prefs.edit().apply {
                    oldest.forEach { remove(it.key) }
                }.apply()
            }
        }
    }
}
