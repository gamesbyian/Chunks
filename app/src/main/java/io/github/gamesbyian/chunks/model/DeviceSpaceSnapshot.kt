package io.github.gamesbyian.chunks.model

/**
 * OS-wide free-space observation on Chunks' internal filesystem.
 * A low-storage broadcast is advisory, never a measured Chunks quota.
 */
enum class DeviceSpaceStatus { LOW_WARNING_ACTIVE, NO_WARNING_DETECTED, UNKNOWN }

data class DeviceSpaceSnapshot(
    val totalBytes: Long?,
    val availableBytes: Long?,
    val androidLowSpaceAlert: Boolean?
) {
    init {
        require(totalBytes == null || totalBytes > 0)
        require(availableBytes == null || availableBytes >= 0)
    }

    val status: DeviceSpaceStatus get() = when (androidLowSpaceAlert) {
        true -> DeviceSpaceStatus.LOW_WARNING_ACTIVE
        false -> DeviceSpaceStatus.NO_WARNING_DETECTED
        null -> DeviceSpaceStatus.UNKNOWN
    }

    val occupiedFraction: Float? get() {
        val total = totalBytes ?: return null
        val available = availableBytes ?: return null
        // Unavailable capacity includes other apps and system reserves. Never
        // label this amount as Chunks-owned or torrent-allocation usage.
        return (1.0 - available.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
}
