package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.DelugeReadOnlyRpc
import io.github.gamesbyian.chunks.rpc.QBittorrentReadOnlyRpc
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.TransmissionReadOnlyRpc
import java.io.IOException
import kotlinx.coroutines.withTimeout

/**
 * One typed, read-only client access point for the three new remote protocols.
 * Profile persistence, privacy admission and navigation belong to callers:
 * neither an HTTP 200 nor this object alone grants a Chunks UI session.
 */
internal class RemoteProtocolReadOnlyClient(
    private val reader: Reader = RealReader,
    private val now: () -> Long = System::currentTimeMillis
) {
    internal data class Configuration(
        val backend: RemoteBackendKind,
        val credentials: RemoteCredentials,
        val delugeDaemonHostId: String? = null
    ) {
        override fun toString(): String = "Remote protocol connection (<redacted>)"
    }

    internal sealed interface ProtocolResponse {
        data class Transmission(val value: TransmissionReadOnlyRpc.Snapshot) : ProtocolResponse
        data class QBittorrent(val value: QBittorrentReadOnlyRpc.Snapshot) : ProtocolResponse
        data class Deluge(val value: DelugeReadOnlyRpc.Snapshot) : ProtocolResponse
    }

    internal fun interface Reader {
        suspend fun inspect(configuration: Configuration): ProtocolResponse
    }

    internal data class VerifiedRead(
        val backend: RemoteBackendKind,
        val environment: EnvironmentId,
        val snapshot: EnvironmentTransferSnapshot,
        val capabilities: EnvironmentCapabilitySnapshot,
        val reportedVersion: String
    ) {
        init {
            require(environment == snapshot.environment && environment == capabilities.environment)
            require(snapshot.canClaimCompleteLiveInventory)
            require(capabilities.of(EnvironmentOperation.LIST) == CapabilityEvidence.UNKNOWN)
            require(EnvironmentOperation.entries.filter { it != EnvironmentOperation.LIST }
                .all { !capabilities.decide(it, EnvironmentAvailability.LIVE).allowed })
        }
    }

    /**
     * An explicit read-only challenge/login/session check must succeed before
     * publishing rows or marking a source LIVE. Transmission's 200 alone does
     * not prove the supplied principal, so it remains unverified unless its
     * HTTP Basic/Digest read challenge was actually accepted.
     *
     * This method does NOT save credentials, authorize Add/Remove, or grant
     * a transfer path as a file-system destination.
     */
    suspend fun inspect(configuration: Configuration): VerifiedRead {
        require(configuration.backend != RemoteBackendKind.RTORRENT) {
            "Use existing rTorrent adapter"
        }
        val identity = RemoteBackendIdentity.of(
            configuration.backend,
            configuration.credentials.endpoint,
            configuration.credentials.username,
            configuration.delugeDaemonHostId
        )
        // Real adapter constructors also enforce their own exact endpoint
        // grammar and refuse URL-embedded credentials, ambiguous paths and
        // public HTTP before any network access.
        val result = withTimeout(45_000) { reader.inspect(configuration) }
        val (rows, version) = when (configuration.backend) {
            RemoteBackendKind.TRANSMISSION -> {
                val snapshot = (result as? ProtocolResponse.Transmission)?.value
                    ?: throw IOException("Transmission response belonged to another protocol")
                if (!snapshot.challengedAuthentication) {
                    throw IOException("Transmission read did not authenticate this exact account")
                }
                RemoteProtocolObservations.transmission(identity, snapshot) to snapshot.version
            }
            RemoteBackendKind.QBITTORRENT -> {
                val snapshot = (result as? ProtocolResponse.QBittorrent)?.value
                    ?: throw IOException("qBittorrent response belonged to another protocol")
                RemoteProtocolObservations.qbittorrent(identity, snapshot) to snapshot.applicationVersion
            }
            RemoteBackendKind.DELUGE -> {
                val snapshot = (result as? ProtocolResponse.Deluge)?.value
                    ?: throw IOException("Deluge response belonged to another protocol")
                if (snapshot.selectedDaemonId != configuration.delugeDaemonHostId) {
                    throw IOException("Deluge read belonged to a different daemon")
                }
                RemoteProtocolObservations.deluge(identity, snapshot) to snapshot.daemonVersion
            }
            RemoteBackendKind.RTORRENT -> error("Already rejected")
        }
        val observedAt = now().also {
            if (it <= 0) throw IOException("Invalid client read timestamp")
        }
        return VerifiedRead(
            backend = configuration.backend,
            environment = identity,
            snapshot = EnvironmentTransferSnapshot(
                environment = identity,
                availability = EnvironmentAvailability.LIVE,
                inventoryComplete = true,
                observedAtEpochMillis = observedAt,
                transfers = rows
            ),
            capabilities = NewRemoteReadOnlyCapabilities.forBackend(
                configuration.backend, identity, observedAt
            ),
            reportedVersion = version
        )
    }

    private object RealReader : Reader {
        override suspend fun inspect(configuration: Configuration): ProtocolResponse =
            when (configuration.backend) {
                RemoteBackendKind.TRANSMISSION ->
                    ProtocolResponse.Transmission(
                        TransmissionReadOnlyRpc(configuration.credentials).inspect()
                    )
                RemoteBackendKind.QBITTORRENT ->
                    ProtocolResponse.QBittorrent(
                        QBittorrentReadOnlyRpc(configuration.credentials).inspect()
                    )
                RemoteBackendKind.DELUGE ->
                    ProtocolResponse.Deluge(
                        DelugeReadOnlyRpc(
                            configuration.credentials,
                            requireNotNull(configuration.delugeDaemonHostId)
                        ).inspect()
                    )
                RemoteBackendKind.RTORRENT -> error("Unsupported here")
            }
    }
}
