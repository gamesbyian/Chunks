package io.github.gamesbyian.chunks.model

/**
 * OS-wide free-space observation on the app's internal physical filesystem.
 * Does not measure Chunks payload allocations, a SAF tree or rTorrent storage.
 */
enum class DeviceSpaceStatus { ABOVE_ANDROID_FLOOR, AT_OR_BELOW_ANDROID_FLOOR, UNKNOWN }

data class DeviceSpaceSnapshot(
    val totalBytes: Long?,
    val availableBytes: Long?,
    val androidLowSpaceBytes: Long?
) {
    init {
        require(totalBytes == null || totalBytes > 0)
        require(availableBytes == null || availableBytes >= 0)
        require(androidLowSpaceBytes == null || androidLowSpaceBytes >= 0)
    }

    val status: DeviceSpaceStatus get() = when {
        availableBytes == null || androidLowSpaceBytes == null ->
            DeviceSpaceStatus.UNKNOWN
        availableBytes <= androidLowSpaceBytes ->
            DeviceSpaceStatus.AT_OR_BELOW_ANDROID_FLOOR
        else -> DeviceSpaceStatus.ABOVE_ANDROID_FLOOR
    }

    val occupiedFraction: Float? get() {
        val total = totalBytes ?: return null
        val available = availableBytes ?: return null
        // Used across the *whole device volume*, including other apps. Never
        // label this number as Chunks-owned or torrent-allocation usage.
        return (1.0 - (available.toDouble() / total.toDouble()))
            .coerceIn(0.0, 1.0).toFloat()
    }
}
