package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.engine.LocalDiskIoMode
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalRateLimits
import io.github.gamesbyian.chunks.engine.LocalRestoreDiscovery
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import java.io.File

/** Owns the process's native engine and its persisted startup settings. */
internal object LocalEngineHolder {
    private var engine: LocalTorrentEngine? = null

    /** The running engine, if the user has added anything; never starts networking. */
    @Synchronized
    fun current(): LocalTorrentEngine? = engine

    /**
     * Starts the engine (and its listening socket) on first explicit use, or to
     * restore transfers saved earlier. Restored transfers always come back stopped.
     */
    @Synchronized
    fun get(context: Context): LocalTorrentEngine {
        engine?.let { return it }
        // The upstream portable backend avoids the reproduced Android 15 FUSE
        // NodeTracker abort during memory-mapped writes. Paths and file policy stay unchanged.
        val candidate = LocalTorrentEngine(
            LocalEngineConfig(stateDirectory = stateDirectory(context), diskIoMode = LocalDiskIoMode.POSIX)
        )
        val verified = LocalEngineStartupSettings.open(
            candidate = candidate,
            savedDht = dhtPreferred(context),
            savedLimits = rateLimitsPreferred(context),
            start = { it.start() },
            setDht = { running, desired -> running.setDhtEnabled(desired) },
            setLimits = { running, desired -> running.setRateLimits(desired) },
            close = { it.close() }
        )
        // Never expose a partially started engine as the live singleton.
        engine = verified
        return verified
    }

    private fun prefs(context: Context) = context.getSharedPreferences("local-engine", Context.MODE_PRIVATE)
    fun dhtPreferred(context: Context) = prefs(context).getBoolean("dht", false)

    /**
     * No optimistic DHT setting: do not claim/persist a change that the running
     * engine could not read back. A commit failure is also unconfirmed.
     * This never starts an engine merely to toggle a future preference.
     */
    @Synchronized
    fun setDhtPreferred(context: Context, enabled: Boolean): Boolean {
        val running = engine
        return LocalDhtPreferenceTransaction.commitIfApplied(
            requested = enabled,
            applyToRunningEngine = running?.let { { it.setDhtEnabled(enabled) } },
            savePreference = { value -> prefs(context).edit().putBoolean("dht", value).commit() }
        )
    }

    fun rateLimitsPreferred(context: Context) = prefs(context).let {
        LocalRateLimits(it.getInt("download-limit", 0).coerceAtLeast(0), it.getInt("upload-limit", 0).coerceAtLeast(0))
    }

    /**
     * Save a future-start preference only if no engine exists; otherwise demand
     * an exact native readback before the durable commit. Failed/uncertain
     * application must not overwrite the previous saved speed limits.
     */
    @Synchronized
    fun setRateLimitsPreferred(context: Context, limits: LocalRateLimits): Boolean {
        val running = engine
        return LocalRateLimitPreferenceTransaction.commitIfApplied(
            requested = limits,
            applyToRunningEngine = running?.let { { it.setRateLimits(limits) } },
            savePreference = { value ->
                prefs(context).edit()
                    .putInt("download-limit", value.downloadBytesPerSecond)
                    .putInt("upload-limit", value.uploadBytesPerSecond)
                    .commit()
            }
        )
    }

    /** Start only if an earlier session left transfers to restore. */
    @Synchronized
    fun restoreIfSaved(context: Context): LocalTorrentEngine? = engine
        ?: if (LocalRestoreDiscovery.shouldAttemptRestore(stateDirectory(context))) get(context) else null

    private fun stateDirectory(context: Context) = File(context.filesDir, "local-engine")
}
