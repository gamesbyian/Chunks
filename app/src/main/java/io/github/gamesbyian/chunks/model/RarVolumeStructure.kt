package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.model.RarVolumeHeaderReader.Result
import io.github.gamesbyian.chunks.model.RarVolumeHeaderReader.Status

/** Observations about header structure only. Matching split flags cannot identify an archive set. */
internal enum class RarVolumeStructure {
    TERMINAL_CHAIN,
    MORE_VOLUMES_REQUIRED,
    NUMBERS_UNCONFIRMED,
    INCONSISTENT,
    ENCRYPTED_HEADERS,
    UNCONFIRMED;

    companion object {
        fun inspect(volumes: List<Result>): RarVolumeStructure {
            val first = volumes.firstOrNull() ?: return UNCONFIRMED
            if (volumes.any { it.status == Status.ENCRYPTED_HEADERS }) return ENCRYPTED_HEADERS
            if (volumes.any { it.status != Status.HEADER_CHAIN_OBSERVED }) return UNCONFIRMED

            if (first.splitBefore || volumes.any { it.format != first.format || it.multiVolume != first.multiVolume } ||
                (volumes.size > 1 && first.multiVolume != true)) return INCONSISTENT
            volumes.forEachIndexed { index, volume ->
                if (volume.ordinal != null && volume.ordinal != index + 1) return INCONSISTENT
                if (index < volumes.lastIndex &&
                    (volume.nextVolume != true || volume.splitAfter != volumes[index + 1].splitBefore))
                    return INCONSISTENT
            }
            if (volumes.last().nextVolume == true) return MORE_VOLUMES_REQUIRED
            if (volumes.any { it.ordinal == null }) return NUMBERS_UNCONFIRMED
            return TERMINAL_CHAIN
        }
    }
}
