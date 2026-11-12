package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import io.github.gamesbyian.chunks.model.ProgressChunkHistory
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Presentation-only cache shared by local, rTorrent and Combined lists. Each
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
    // Live process ordering only. Cold relaunch accepts a newly verified recheck,
    // even if the device clock changed while Chunks was absent.
    private val latestObservedAt = mutableMapOf<String, Long>()

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

    fun isOutOfOrder(identity: String, observedAtEpochMillis: Long?): Boolean =
        observedAtEpochMillis != null &&
            (latestObservedAt[identity]?.let { observedAtEpochMillis < it } == true)

    suspend fun observe(
        context: Context, identity: String, ppm: Int, minVisiblePpm: Int,
        observedAtEpochMillis: Long? = null
    ) {
        if (ppm !in 0..1_000_000) return
        val sampleTime = observedAtEpochMillis ?: System.currentTimeMillis()
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
        // Saved-profile and direct-server views can overlap. An older read must
        // not overwrite newer completed area or synthesize a backward correction.
        if (isOutOfOrder(identity, sampleTime)) return
        latestObservedAt[identity] = sampleTime
        val previous = histories[identity] ?: ProgressChunkHistory()
        val next = previous.observe(ppm, minVisiblePpm)
        if (next == previous) return
        histories[identity] = next
        if (histories.size > MAX_STORED) {
            // A large lifetime of viewed transfers must not grow the UI cache forever.
            histories.keys.firstOrNull { it != identity }?.let {
                histories.remove(it)
                latestObservedAt.remove(it)
            }
        }
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
