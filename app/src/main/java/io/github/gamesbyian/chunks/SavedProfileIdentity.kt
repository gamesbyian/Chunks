package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentSnapshots

/**
 * A saved server listing may use only the exact identity originally verified
 * for its authenticated endpoint + account. A changed account or ruTorrent
 * instance must be reconnected and reverified, never silently relabelled.
 */
internal object SavedProfileIdentity {
    fun matches(savedId: String, aliasScope: String?): Boolean =
        aliasScope != null &&
            EnvironmentSnapshots.serverIdOfAliasScope(aliasScope)?.value == savedId
}
