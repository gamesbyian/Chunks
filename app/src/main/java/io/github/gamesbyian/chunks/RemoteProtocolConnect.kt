package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.model.RemoteProtocolReadOnlyClient
import io.github.gamesbyian.chunks.rpc.ClientRefusedException
import io.github.gamesbyian.chunks.rpc.DelugeReadOnlyRpc
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RpcHttpStatusException
import java.io.IOException
import java.net.URI

/**
 * Connect for Transmission, qBittorrent and Deluge from the ONE existing
 * Connections form. Credentials are frozen by the caller before this runs;
 * every result is an authenticated, complete, exact-client read.
 */
internal object RemoteProtocolConnect {
    /**
     * Accept what people paste from their browser (the Web UI page) and turn
     * it into the exact API endpoint each adapter validates. Never changes
     * scheme, host, port or account; never adds credentials.
     */
    fun normalizeEndpoint(kind: RemoteBackendKind, raw: String): String {
        val trimmed = raw.trim()
        val uri = try { URI(trimmed) } catch (_: Exception) { return trimmed }
        if (uri.scheme == null || uri.host == null || uri.rawQuery != null || uri.rawFragment != null) return trimmed
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val fixed = when (kind) {
            RemoteBackendKind.TRANSMISSION -> when {
                path.endsWith("/rpc") -> path
                path.endsWith("/transmission/web/") || path.endsWith("/transmission/web") ->
                    path.substringBeforeLast("/web") + "/rpc"
                path.endsWith("/transmission/") || path.endsWith("/transmission") ->
                    path.trimEnd('/') + "/rpc"
                path == "/" -> "/transmission/rpc"
                else -> path
            }
            RemoteBackendKind.QBITTORRENT -> if (path.endsWith("/")) path else "$path/"
            RemoteBackendKind.DELUGE -> when {
                path.endsWith("/json") -> path
                else -> path.trimEnd('/') + "/json"
            }
            RemoteBackendKind.RTORRENT -> path
        }
        return trimmed.substring(0, trimmed.length - uri.rawPath.orEmpty().length) + fixed
    }

    /** Explicit ID wins; a blank one is adopted only if exactly one daemon is registered. */
    suspend fun resolveDelugeDaemon(credentials: RemoteCredentials, explicit: String): String {
        if (explicit.isNotBlank()) return explicit.trim()
        val ids = DelugeReadOnlyRpc(credentials, "unselected").registeredDaemonIds()
        return when (ids.size) {
            1 -> ids.single()
            0 -> throw ClientRefusedException("This Deluge Web UI has no registered daemon")
            else -> throw ClientRefusedException("This Deluge Web UI has ${ids.size} daemons; enter the daemon ID to use")
        }
    }

    data class Verified(
        val configuration: RemoteProtocolReadOnlyClient.Configuration,
        val read: RemoteProtocolReadOnlyClient.VerifiedRead
    ) {
        override fun toString(): String = "Verified remote protocol connection (<redacted>)"
    }

    suspend fun connect(
        kind: RemoteBackendKind,
        endpoint: String,
        username: String,
        password: String,
        delugeDaemonDraft: String,
        reader: RemoteProtocolReadOnlyClient = RemoteProtocolReadOnlyClient()
    ): Verified {
        require(kind != RemoteBackendKind.RTORRENT) { "rTorrent uses its existing Connect" }
        val exact = normalizeEndpoint(kind, endpoint)
        val credentials = RemoteCredentials(exact, if (kind == RemoteBackendKind.DELUGE) "" else username, password)
        val daemon = if (kind == RemoteBackendKind.DELUGE) resolveDelugeDaemon(credentials, delugeDaemonDraft) else null
        val configuration = RemoteProtocolReadOnlyClient.Configuration(kind, credentials, daemon)
        return Verified(configuration, reader.inspect(configuration))
    }

    /** Fixed, credential-free wording; never echoes server bodies or URLs. */
    fun explain(error: Throwable): String = when (error) {
        is RpcHttpStatusException -> when (error.status) {
            401, 403 -> "The login was refused. Check the username and password."
            404 -> "Nothing answered at that address. Check the client's URL."
            else -> "The client answered with an error (HTTP ${error.status})."
        }
        is ClientRefusedException -> error.message?.takeIf { "://" !in it }
            ?: "The client refused the connection."
        is IllegalArgumentException -> error.message?.takeIf { "://" !in it }
            ?: "Check the address. HTTPS is required."
        is IOException -> when {
            error.message?.contains("authenticat", true) == true ||
                error.message?.contains("login", true) == true ->
                "The login was refused. Check the username and password."
            error.message?.contains("daemon", true) == true -> error.message!!
            else -> "Could not reach or verify this client. Nothing was changed."
        }
        else -> "Could not verify this client. Nothing was changed."
    }
}
