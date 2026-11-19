package io.github.gamesbyian.chunks.seeding

import android.content.Context
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import kotlinx.coroutines.runBlocking

/**
 * One authenticated rTorrent account as a [SeedingController.EnginePort], so
 * remote Smart Seeding shares Local's rules, snapshots and at-most-once Stop
 * journal instead of a second engine (SMART-SEEDING-RULES).
 *
 * Capability boundaries, from the live primary-account getter matrix (run
 * 44481003865) and rTorrent semantics:
 * - ratio comes from `d.up.total` / `d.down.total`; a zero denominator cannot
 *   be judged;
 * - there is no accumulated seeding-time counter (`d.timestamp.finished` is
 *   the completion instant), so time conditions are never judged here;
 * - there is no per-transfer upload cap (only shared throttle groups), so a
 *   rule's cap is reported as not applied, never emulated;
 * - a private transfer's tracker URLs are never read (they carry passkeys):
 *   it can only take the server default, never a tracker-domain rule;
 * - Stop is the existing exact-hash `setStarted(false)` with verified readback.
 *
 * Calls block: run the controller off the main thread. Only transfers in the
 * server's main view are considered.
 */
internal class RTorrentSeedingPort(
    private val remote: RTorrentRemote,
    /** True on the activation tick: every current transfer predates it and stays unmanaged. */
    private val activationBaseline: Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis
) : SeedingController.EnginePort {
    override val supportsPerTransferUploadCap: Boolean get() = false

    override fun transfers(): List<SeedingController.Facts> = runBlocking {
        val at = if (activationBaseline) 0L else nowMs()
        remote.listTransfers().map { SeedingController.Facts(it.hash.lowercase(), at, null, null) }
    }

    override fun details(facts: SeedingController.Facts): SeedingController.Facts = runBlocking {
        when (val trackers = remote.readPublicTrackers(facts.infoHash)) {
            RTorrentRemote.PublicTrackers.Private -> facts.copy(trackerUrls = emptyList(), isPrivate = true)
            is RTorrentRemote.PublicTrackers.Urls -> facts.copy(trackerUrls = trackers.urls, isPrivate = false)
        }
    }

    override fun observe(infoHash: String): SeedingObservation? = runBlocking {
        val e = remote.readSeedingEvidence(infoHash)
        val up = e.uploadedBytes ?: return@runBlocking null
        val down = e.downloadedBytes ?: return@runBlocking null
        val complete = e.complete ?: return@runBlocking null
        val active = e.active ?: return@runBlocking null
        SeedingObservation(up, down, seedingSeconds = null, seeding = complete && active)
    }

    override fun uploadLimit(infoHash: String): Int? = null
    override fun setUploadLimit(infoHash: String, bytesPerSecond: Long?): Boolean = false

    /** Whether this tick sent (or may have sent) a Stop: the caller must then refresh before other writes. */
    var stopAttempted = false
        private set

    override fun pause(infoHash: String): Boolean {
        stopAttempted = true
        return runBlocking { remote.setStarted(infoHash, desiredStarted = false) == LifecycleOutcome.Verified(false) }
    }
}

/**
 * Remote Smart Seeding for one exact authenticated rTorrent account, while
 * Chunks is open and connected. Off unless the person explicitly enables it
 * for that server; nothing runs (not even a read) otherwise.
 */
internal object RemoteSeeding {
    /** The seeding-rule environment ID of the exact account behind [remote]. */
    fun environmentOf(remote: RTorrentRemote): String? =
        EnvironmentSnapshots.serverIdOfAliasScope(remote.addAccountScope())?.let { "remote:" + it.value }

    /** Per-account opt-in, default off; stored per exact environment ID only. */
    class EnforcementSwitch(context: Context) {
        private val prefs = context.applicationContext
            .getSharedPreferences("chunks_remote_seeding_enforcement", Context.MODE_PRIVATE)
        fun enabled(environment: String): Boolean =
            runCatching { prefs.getBoolean(key(environment), false) }.getOrDefault(false)
        fun set(environment: String, on: Boolean): Boolean =
            runCatching { prefs.edit().putBoolean(key(environment), on).commit() }.getOrDefault(false)
        private fun key(environment: String) = "on:" + PreferencesAssignmentStore.digest(environment)
    }

    enum class Tick {
        /** Disabled, unknown account or unreadable rules: nothing was read or sent. */
        NOT_RUN,
        /** Rules were evaluated; no Stop was sent. */
        NO_WRITE,
        /** A Stop was sent (verified or not): refresh before any other server change. */
        STOP_ATTEMPTED
    }

    /** Cheap gate check before taking the server mutation gate: no RPC. */
    fun enabledFor(context: Context, remote: RTorrentRemote): Boolean =
        environmentOf(remote)?.let { EnforcementSwitch(context).enabled(it) } == true

    /** One controller step for [remote]. Blocking; call off main, holding the server's mutation gate. */
    fun tick(context: Context, remote: RTorrentRemote, nowMs: Long = System.currentTimeMillis()): Tick {
        val environment = environmentOf(remote) ?: return Tick.NOT_RUN
        if (!EnforcementSwitch(context).enabled(environment)) return Tick.NOT_RUN
        val snapshot = SeedingRuleStore(context).load()
        if (snapshot.unreadable) return Tick.NOT_RUN
        val store = PreferencesAssignmentStore(context, environment)
        val baseline = store.epoch() == null
        val port = RTorrentSeedingPort(remote, baseline) { nowMs }
        try {
            SeedingController(port, store)
                .tick(environment, snapshot.rules, snapshot.defaults[environment] ?: SeedingPolicy(), nowMs)
        } catch (_: Exception) {
            // A read fault ends this tick; a fault after a Stop still needs a refresh.
            if (!port.stopAttempted) return Tick.NO_WRITE
        }
        return if (port.stopAttempted) Tick.STOP_ATTEMPTED else Tick.NO_WRITE
    }
}
