package io.github.gamesbyian.chunks.rpc

/**
 * Conservative, read-only selection for the owner-authorized synthetic Seedhost
 * test. An inventory-wide common path is NOT evidence that it exists or is writable.
 * Only a freshly observed completed single-file transfer can nominate its parent.
 *
 * This is test-only code. It does not change the app's default destination logic,
 * grant filesystem capabilities, or authorize writes to any unrelated torrent.
 */
internal object SeedhostDestinationPreflight {
    data class Observation(
        val directory: String,
        val complete: Boolean,
        val singleFile: Boolean?,
        val freshDirectoryMatches: Boolean
    )

    data class Selection(
        val directory: String,
        val usedConfiguredDefault: Boolean,
        val independentReferences: Int
    )

    private const val PROBE_MAGNET =
        "magnet:?xt=urn:btih:0000000000000000000000000000000000000000"

    private fun acceptedParent(path: String): Boolean =
        path.count { it == '/' } >= 2 &&
            runCatching { StoppedRemoteAddPlan.fromMagnet(PROBE_MAGNET, path) }.isSuccess

    fun select(configuredDefault: String?, observations: List<Observation>): Selection? {
        val counts = observations.asSequence()
            .filter { it.complete && it.singleFile == true && it.freshDirectoryMatches }
            .map { it.directory }
            .filter(::acceptedParent)
            .groupingBy { it }
            .eachCount()
        if (counts.isEmpty()) return null

        // Prefer a server-configured default ONLY if a real, complete single-file
        // transfer freshly corroborates it. Never trust an unobserved default path.
        val preferred = configuredDefault?.takeIf { counts.containsKey(it) }
        val selected = preferred ?: counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key
        return Selection(selected, preferred != null, counts.getValue(selected))
    }
}
