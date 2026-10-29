package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.security.MessageDigest

/**
 * Builds read-only [EnvironmentTransferSnapshot]s from the observations the
 * phone and server screens already hold. Identity is the stable environment
 * ID: "phone", or the saved server profile ID (SHA-256 of endpoint + NUL +
 * username, the same key remembered profiles and seeding rules use).
 */
object EnvironmentSnapshots {
    val PHONE = EnvironmentId("phone")

    fun serverId(endpoint: String, username: String): EnvironmentId = EnvironmentId(
        MessageDigest.getInstance("SHA-256")
            .digest((endpoint.trim() + "\u0000" + username.trim()).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) })

    /**
     * A privacy-locked phone list reports no rows at all. [confirmed] is false
     * until at least one native list read has been accepted; an initial empty
     * list is not an observed empty engine.
     */
    fun phone(
        frame: LocalInventoryFrame<LocalTransferStatus>,
        confirmed: Boolean,
        locked: Boolean,
        observedAtEpochMillis: Long?
    ): EnvironmentTransferSnapshot {
        if (locked) return EnvironmentTransferSnapshot(PHONE, EnvironmentAvailability.LOCKED,
            inventoryComplete = false, observedAtEpochMillis = null, transfers = emptyList())
        val (rows, clean) = accept(frame.entries.map { it.infoHash to it }) {
            UnifiedTransferObservations.fromPhone(PHONE, it)
        }
        return EnvironmentTransferSnapshot(PHONE,
            if (frame.stale) EnvironmentAvailability.STALE else EnvironmentAvailability.LIVE,
            inventoryComplete = confirmed && clean, observedAtEpochMillis = observedAtEpochMillis,
            transfers = rows)
    }

    /**
     * [connected] is false when the profile is saved but not signed in this
     * session; its rows are not shown as live. A server list is complete only
     * when loaded from rTorrent's full "main" view, not a filtered view.
     */
    fun server(
        environment: EnvironmentId,
        transfers: List<RemoteTransfer>,
        connected: Boolean,
        stale: Boolean,
        locked: Boolean,
        fullView: Boolean,
        observedAtEpochMillis: Long?
    ): EnvironmentTransferSnapshot {
        if (locked || !connected) return EnvironmentTransferSnapshot(environment,
            if (locked) EnvironmentAvailability.LOCKED else EnvironmentAvailability.OFFLINE,
            inventoryComplete = false, observedAtEpochMillis = null, transfers = emptyList())
        val (rows, clean) = accept(transfers.map { it.hash to it }) {
            UnifiedTransferObservations.fromRTorrent(environment, it)
        }
        return EnvironmentTransferSnapshot(environment,
            if (stale) EnvironmentAvailability.STALE else EnvironmentAvailability.LIVE,
            inventoryComplete = fullView && clean && observedAtEpochMillis != null,
            observedAtEpochMillis = observedAtEpochMillis, transfers = rows)
    }

    /**
     * Rows with an invalid hash, or a hash reported twice by one source, are
     * dropped rather than guessed at, and the inventory is marked incomplete.
     */
    private fun <T> accept(items: List<Pair<String, T>>, convert: (T) -> UnifiedTransferObservation):
        Pair<List<UnifiedTransferObservation>, Boolean> {
        val converted = items.mapNotNull { (_, item) -> runCatching { convert(item) }.getOrNull() }
        val counts = converted.groupingBy { it.key }.eachCount()
        val unique = converted.filter { counts.getValue(it.key) == 1 }
        return unique to (unique.size == items.size)
    }
}
