package io.github.gamesbyian.chunks.model

/** Typed, namespaced provider IDs, never plain person names or unqualified numeric IDs. */
data class ProviderPersonId(val provider: String, val type: String, val value: String) {
    init {
        require(provider.matches(Regex("[a-z][a-z0-9-]*")))
        require(type.matches(Regex("[a-z][a-z0-9-]*")))
        require(value.isNotBlank() && !value.any { it.isISOControl() })
    }
    val key: String get() = provider + ":" + type + ":" + value
}

/** The calling metadata adapter must have verified this mapping from a permitted source. */
data class VerifiedPersonBridge(val qid: String, val identifiers: Set<ProviderPersonId>) {
    init {
        require(qid.matches(Regex("Q[1-9][0-9]*")))
        require(identifiers.isNotEmpty())
    }
}

class ProviderPersonCrosswalk private constructor(
    private val owners: Map<ProviderPersonId, Set<String>>
) {
    companion object {
        fun build(bridges: Collection<VerifiedPersonBridge>): ProviderPersonCrosswalk {
            val ownership = mutableMapOf<ProviderPersonId, MutableSet<String>>()
            for (bridge in bridges) for (id in bridge.identifiers) {
                ownership.getOrPut(id) { mutableSetOf() }.add(bridge.qid)
            }
            return ProviderPersonCrosswalk(ownership)
        }
    }

    /** Returns null when unresolved OR when two different QIDs claim the same provider ID. */
    fun resolveCanonical(id: ProviderPersonId): String? =
        owners[id]?.singleOrNull()?.let { "wd:" + it }

    fun isConflicted(id: ProviderPersonId): Boolean = (owners[id]?.size ?: 0) > 1
}
