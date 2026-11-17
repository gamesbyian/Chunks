package io.github.gamesbyian.chunks.model

import java.net.URI
import java.security.MessageDigest

/**
 * A remote profile identity is protocol + exact endpoint + exact account,
 * and Deluge additionally includes the WebUI-selected daemon host ID.
 *
 * This is a pure key function, not authentication or endpoint verification.
 * A caller must first pass the backend's exact endpoint validation and
 * authenticated-client checks before it may populate any LIVE snapshot.
 */
internal enum class RemoteBackendKind {
    RTORRENT, TRANSMISSION, QBITTORRENT, DELUGE
}

internal object RemoteBackendIdentity {
    private const val DOMAIN = "chunks-remote-protocol-profile-v1"
    private val DELUGE_DAEMON_ID = Regex("[A-Za-z0-9._:-]{1,128}")

    /**
     * Preserve every existing rTorrent saved profile and environment key
     * exactly. New protocols get a domain-separated key; a torrent hash alone
     * can never select a different engine's operation or saved view.
     */
    fun of(
        kind: RemoteBackendKind,
        endpoint: String,
        username: String,
        delugeDaemonHostId: String? = null
    ): EnvironmentId {
        require(endpoint == endpoint.trim() && endpoint.length in 1..2048) {
            "Remote profile endpoint must be exact and bounded"
        }
        require(username.length <= 512 && !username.any { it.isISOControl() }) {
            "Remote profile principal is invalid"
        }
        val uri = try { URI(endpoint) } catch (_: Exception) {
            throw IllegalArgumentException("Invalid remote profile endpoint")
        }
        require(uri.scheme.equals("https", true) ||
            (uri.scheme.equals("http", true) && uri.host in setOf("127.0.0.1", "localhost", "[::1]", "::1"))) {
            "Remote profile requires HTTPS except loopback"
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.startsWith("/")) {
            "Invalid remote profile endpoint identity"
        }
        require(endpoint.none { it.isISOControl() }) { "Invalid remote profile endpoint" }
        when (kind) {
            RemoteBackendKind.DELUGE -> require(delugeDaemonHostId != null &&
                DELUGE_DAEMON_ID.matches(delugeDaemonHostId)) {
                    "Explicit Deluge daemon identity required"
                }
            else -> require(delugeDaemonHostId == null) {
                "A daemon ID cannot be attached to another backend"
            }
        }
        if (kind == RemoteBackendKind.RTORRENT) {
            // Existing encrypted rTorrent roster and seeding-rule key format
            // must survive upgrades. New protocols use a separate hash domain.
            return EnvironmentSnapshots.serverId(endpoint, username)
        }
        val payload = listOf(DOMAIN, kind.name, endpoint, username, delugeDaemonHostId.orEmpty())
            .joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return EnvironmentId(hash)
    }
}

/**
 * Boundary for new *read-only* protocols. Unknown is a tentatively usable
 * operation in the existing generic capability resolver, so an empty map
 * would accidentally enable Add/Remove/priority controls after login.
 *
 * Make every non-LIST operation explicitly unavailable until a specific
 * backend implementation, scoped permissions and post-write readback
 * have been separately accepted. LIST still requires a complete, fresh
 * authenticated inventory; this snapshot never grants that proof by itself.
 */
internal object NewRemoteReadOnlyCapabilities {
    fun forBackend(
        kind: RemoteBackendKind,
        environment: EnvironmentId,
        observedAtEpochMillis: Long? = null
    ): EnvironmentCapabilitySnapshot {
        require(kind != RemoteBackendKind.RTORRENT) {
            "rTorrent capabilities must continue using its own negotiated probe"
        }
        require(environment != EnvironmentSnapshots.LOCAL) {
            "A remote capability snapshot cannot authorize the local engine"
        }
        return EnvironmentCapabilitySnapshot(
            environment = environment,
            source = kind.name.lowercase() + " read-only adapter",
            observedAtEpochMillis = observedAtEpochMillis,
            evidence = EnvironmentOperation.entries.associateWith { operation ->
                when (operation) {
                    EnvironmentOperation.LIST -> CapabilityEvidence.UNKNOWN
                    EnvironmentOperation.SERVER_DEFAULT_ADD_FOLDER -> CapabilityEvidence.NOT_APPLICABLE
                    else -> CapabilityEvidence.MISSING
                }
            }
        )
    }
}
