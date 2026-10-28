package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.engine.FinishInOrderRecord
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import java.security.MessageDigest
import java.util.Locale

/**
 * Device-private Finish in Order policies for phone transfers, keyed by the
 * local engine scope + verified infohash (the same identity rule as aliases
 * and Collections). Commits are synchronous: the runner's write-ahead journal
 * is only trusted after [save] returns true.
 */
internal class FinishInOrderStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "chunks_finish_in_order_v1", Context.MODE_PRIVATE)
    private val hashPattern = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")

    fun get(infoHash: String): FinishInOrderRecord? = try {
        prefs.getString(key(infoHash), null)?.let(FinishInOrderRecord::decode)
    } catch (_: RuntimeException) { null }

    @Synchronized
    fun save(infoHash: String, record: FinishInOrderRecord): Boolean = try {
        prefs.edit().putString(key(infoHash), record.encode()).commit()
    } catch (_: RuntimeException) { false }

    /** Runner writes never recreate a policy the user has just turned off. */
    @Synchronized
    fun update(infoHash: String, record: FinishInOrderRecord): Boolean =
        prefs.contains(key(infoHash)) && save(infoHash, record)

    @Synchronized
    fun remove(infoHash: String): Boolean = try {
        prefs.edit().remove(key(infoHash)).commit()
    } catch (_: RuntimeException) { false }

    private fun key(infoHash: String): String {
        require(hashPattern.matches(infoHash)) { "Invalid transfer identity" }
        val identity = TransferCollectionStore.LOCAL_ENGINE_SCOPE + "\u0000" +
            infoHash.uppercase(Locale.ROOT)
        return "policy_" + MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/**
 * Drives every enabled policy from the foreground service's single poll lane
 * (and once immediately after the user enables or resumes one). Steps are
 * throttled per transfer so priorities never churn on every tick; a change is
 * only ever written when a file completes, a peer-availability blocker
 * appears or clears, or the user edits the policy.
 */
internal object FinishInOrderController {
    private const val MIN_INTERVAL_MS = 10_000L
    private val lastStep = HashMap<String, Long>()
    private val lock = Any()

    /** Serializes scheduler steps with user enable/resume/turn-off edits. */
    fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }

    fun runner(engine: LocalTorrentEngine, store: FinishInOrderStore) =
        FinishInOrderRunner(object : FinishInOrderRunner.Port {
            override fun observe(infoHash: String) = engine.observeFinishInOrder(infoHash)
            override fun apply(infoHash: String, record: FinishInOrderRecord,
                               changes: List<io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Change>) =
                engine.applyFinishInOrder(infoHash, record, changes)
            override fun save(infoHash: String, record: FinishInOrderRecord) = store.update(infoHash, record)
            override fun persistNativeState() { try { engine.saveResumeData(2_000L) } catch (_: Exception) {} }
        })

    /** Called off the main thread. Never starts an engine. */
    fun tick(context: Context, hashes: List<String>) {
        val engine = LocalEngineHolder.current() ?: return
        val store = FinishInOrderStore(context)
        val now = System.nanoTime() / 1_000_000L
        for (hash in hashes) {
            val record = store.get(hash) ?: continue
            if (record.suspendedReason != null || (record.finished && record.pending.isEmpty())) continue
            synchronized(lastStep) {
                val previous = lastStep[hash]
                if (previous != null && now - previous < MIN_INTERVAL_MS) return@synchronized null
                lastStep[hash] = now
                Unit
            } ?: continue
            try {
                exclusive {
                    // Re-read under the lock: the user may have edited it.
                    store.get(hash)?.takeIf { it.suspendedReason == null }
                        ?.let { runner(engine, store).step(hash, it) }
                }
            } catch (_: Exception) {}
        }
    }

    /** An explicit user action (enable/resume) runs one step immediately. */
    fun stepNow(context: Context, infoHash: String): FinishInOrderRunner.Step? {
        val engine = LocalEngineHolder.current() ?: return null
        val store = FinishInOrderStore(context)
        synchronized(lastStep) { lastStep[infoHash] = System.nanoTime() / 1_000_000L }
        return exclusive {
            store.get(infoHash)?.let { runner(engine, store).step(infoHash, it) }
        }
    }
}
