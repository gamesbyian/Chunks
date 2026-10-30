package io.github.gamesbyian.chunks

/**
 * Test-only proof bound for a genuinely interrupted BitTorrent download.
 * At least one new verified byte must be required after the process restart,
 * even if fewer than four MiB remain in the selected fixture.
 */
internal object InterruptedSwarmEvidence {
    private const val REQUIRED_GROWTH_BYTES = 4L shl 20

    fun continuationGoal(verifiedBeforeKill: Long, wantedBytes: Long): Long? {
        if (verifiedBeforeKill <= 0L || wantedBytes <= verifiedBeforeKill) return null
        val remaining = wantedBytes - verifiedBeforeKill
        val growth = minOf(remaining, REQUIRED_GROWTH_BYTES)
        return (verifiedBeforeKill + growth).takeIf { it > verifiedBeforeKill }
    }
}
