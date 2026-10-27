package io.github.gamesbyian.chunks.model

/**
 * An exception from a native inventory read cannot truthfully mean that all
 * transfers vanished. Preserve the most recent known snapshot, mark it stale,
 * and withhold transfer-changing actions until a fresh successful read.
 *
 * A successfully observed empty list, by contrast, is authoritative for this
 * in-process engine at that moment. Never infer freshness from a timeout.
 */
data class LocalInventoryFrame<T>(
    val entries: List<T>,
    val stale: Boolean
) {
    val actionsAllowed: Boolean get() = !stale
}

object LocalInventoryFreshness {
    fun <T> advance(previous: LocalInventoryFrame<T>, observed: List<T>?): LocalInventoryFrame<T> =
        if (observed == null) previous.copy(stale = true)
        else LocalInventoryFrame(observed, stale = false)
}
