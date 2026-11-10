package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot

/**
 * A source-exact background-media inventory, independent of display filters and composed
 * list rows. Every key needs both a LIVE read AND a currently authorized account scope.
 *
 * Old in-flight callbacks may only continue while the exact profile, original
 * title and environment/hash still belong to a freshly observed current inventory.
 * A newer LIVE read of the same account/job is continuity, not a new identity:
 * invalidating on every fresh timestamp would starve offscreen work.
 * Caller passes this snapshot as the expected state and compares it with the latest
 * snapshot at the moment of *each* network or storage commit.
 */
class AuthorizedMediaInventorySnapshot private constructor(
    val entries: List<Pair<MediaTransferKey, String>>,
    private val witnesses: Map<MediaTransferKey, Witness>
) {
    private data class Witness(val accountScope: String, val originalName: String)

    // Compose LaunchedEffect compares snapshot keys by value. Unchanged transfers
    // must NOT restart on every periodic read of the same authenticated account.
    override fun equals(other: Any?): Boolean =
        other is AuthorizedMediaInventorySnapshot &&
            entries == other.entries && witnesses == other.witnesses
    override fun hashCode(): Int = 31 * entries.hashCode() + witnesses.hashCode()

    fun stillAuthorizes(key: MediaTransferKey, current: AuthorizedMediaInventorySnapshot): Boolean =
        witnesses[key]?.let { it == current.witnesses[key] } ?: false

    companion object {
        fun build(sources: List<EnvironmentTransferSnapshot>,
                  ownerScopes: Map<EnvironmentId, String>): AuthorizedMediaInventorySnapshot {
            // Multiple snapshots claiming one environment would make profile identity
            // ambiguous. Fail the entire projection closed rather than guess.
            if (sources.map { it.environment }.toSet().size != sources.size)
                return AuthorizedMediaInventorySnapshot(emptyList(), emptyMap())
            val found = LinkedHashMap<MediaTransferKey, Witness>()
            val names = LinkedHashMap<MediaTransferKey, String>()
            for (source in sources) {
                if (source.availability != EnvironmentAvailability.LIVE) continue
                // Timestamp proves this is a freshly observed source; it is
                // deliberately not part of the session identity fingerprint.
                if (source.observedAtEpochMillis == null) continue
                val scope = ownerScopes[source.environment]?.takeIf { it.isNotBlank() } ?: continue
                for (job in source.transfers) {
                    // The source's authenticated account and each row must agree
                    // about environment identity. Otherwise a malformed or
                    // mixed-account inventory can borrow this source's scope to
                    // authorize a completely different environment/hash.
                    // Duplicate keys are ambiguous too: last-write-wins would
                    // silently replace the frozen title/authorization witness.
                    // Reject the entire projection instead of sending any
                    // third-party lookup for an uncertain account boundary.
                    if (job.key.environment != source.environment)
                        return AuthorizedMediaInventorySnapshot(emptyList(), emptyMap())
                    val key = MediaTransferKey.of(job.key)
                    if (key in found)
                        return AuthorizedMediaInventorySnapshot(emptyList(), emptyMap())
                    found[key] = Witness(scope, job.originalName)
                    names[key] = job.originalName
                }
            }
            return AuthorizedMediaInventorySnapshot(names.map { it.key to it.value }, found)
        }
    }
}
