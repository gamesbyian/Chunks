package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.RemoteBackendIdentity
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.model.RemoteProtocolReadOnlyClient
import java.io.IOException

/**
 * The one read-only bridge from separately encrypted typed profiles to the
 * existing canonical Transfers catalog. A stored password is never copied to
 * a display row. The stored identity is checked against the authenticated
 * result on EVERY read, including background refresh after process restart.
 */
internal object RemoteProtocolCombinedProfiles {
    fun saved(
        profiles: List<RememberedRemoteProtocolProfiles.Profile>,
        reader: RemoteProtocolReadOnlyClient = RemoteProtocolReadOnlyClient()
    ): List<CombinedReadOnlyProtocolProfile> = profiles.map { profile ->
        val exactId = RemoteBackendIdentity.of(
            profile.backend, profile.credentials.endpoint,
            profile.credentials.username, profile.delugeDaemonHostId
        ).value
        require(profile.id == exactId && profile.backend != RemoteBackendKind.RTORRENT) {
            "A saved remote protocol profile must retain its exact client identity"
        }
        CombinedReadOnlyProtocolProfile(
            id = profile.id, backend = profile.backend, label = profile.label,
            verifiedAtMillis = profile.lastVerifiedAtMillis,
            inspect = {
                val result = reader.inspect(RemoteProtocolReadOnlyClient.Configuration(
                    profile.backend, profile.credentials, profile.delugeDaemonHostId
                ))
                if (result.environment.value != exactId || result.backend != profile.backend) {
                    throw IOException("A saved remote client response belonged to another profile")
                }
                CombinedReadOnlyProtocolReceipt(
                    result.backend, result.snapshot, result.capabilities
                )
            }
        )
    }

    /** Ephemeral Connect with remember OFF or unavailable; no persistence. */
    fun transient(
        config: RemoteProtocolReadOnlyClient.Configuration,
        verified: RemoteProtocolReadOnlyClient.VerifiedRead,
        label: String,
        reader: RemoteProtocolReadOnlyClient = RemoteProtocolReadOnlyClient()
    ): CombinedReadOnlyProtocolProfile {
        require(verified.backend == config.backend)
        val id = RemoteBackendIdentity.of(
            config.backend, config.credentials.endpoint,
            config.credentials.username, config.delugeDaemonHostId
        )
        require(id == verified.environment && label.length in 1..100 &&
            label.none { it.isISOControl() })
        return CombinedReadOnlyProtocolProfile(
            id = id.value, backend = config.backend, label = label,
            verifiedAtMillis = requireNotNull(verified.snapshot.observedAtEpochMillis),
            inspect = {
                val fresh = reader.inspect(config)
                if (fresh.environment != id || fresh.backend != config.backend) {
                    throw IOException("A transient client response changed its identity")
                }
                CombinedReadOnlyProtocolReceipt(
                    fresh.backend, fresh.snapshot, fresh.capabilities
                )
            }
        )
    }
}
