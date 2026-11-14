package io.github.gamesbyian.chunks.rpc

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A read-only view of the actual directories returned by this exact ruTorrent tenant. */
internal data class RemoteDirectoryPage(val path: String, val folders: List<String>)

internal sealed interface RemoteDirectoryLookup {
    data class Ready(val page: RemoteDirectoryPage) : RemoteDirectoryLookup
    data class Unavailable(val reason: Reason) : RemoteDirectoryLookup

    enum class Reason {
        UNSUPPORTED, AUTHENTICATION, INVALID_RESPONSE, OUTSIDE_SCOPE, CONNECTION
    }
}

/**
 * ruTorrent _getdir is a separately authenticated PHP service, not an rTorrent RPC.
 * Only the authenticated, numbered, same-origin httprpc mount may be probed.
 * There is intentionally no mkdir, shell or arbitrary filesystem operation.
 */
internal object RuTorrentDirectoryListing {
    fun endpoint(rpc: URI): URI? {
        val existing = RuTorrentDeleteData.endpoint(rpc) ?: return null
        return URI.create(existing.toString().removeSuffix("erasedata/action.php") +
            "_getdir/listdir.php")
    }

    /** Older _getdir plugin path; never leave this tenant's mounted ruTorrent instance. */
    fun legacyEndpoint(rpc: URI): URI? = endpoint(rpc)?.let {
        URI.create(it.toString().removeSuffix("listdir.php") + "info.php")
    }

    /** A single canonical absolute directory; no path rewriting or traversal. */
    fun canonical(path: String): String? {
        if (path == "/") return "/"
        if (path.length !in 2..4096 || !path.startsWith("/") ||
            path.endsWith("/") && path.trimEnd('/').isEmpty()) return null
        val trimmed = path.trimEnd('/')
        // _getdir decodes paths twice; refuse percent-encoded octets that could
        // turn into separators/traversal after an extra decoding pass.
        if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(trimmed) ||
            trimmed.contains('\\') || trimmed.any { it.code < 32 || it.code == 127 }) return null
        if (trimmed.substring(1).split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return trimmed
    }

    fun contains(root: String, candidate: String): Boolean =
        canonical(root) == root && canonical(candidate) == candidate &&
            (candidate == root || root == "/" || candidate.startsWith(root + "/"))

    private fun childName(name: String): Boolean =
        name.length in 1..255 && name != "." && name != ".." &&
            '/' !in name && '\\' !in name &&
            name.none { it.code < 32 || it.code == 127 }

    /** Strict JSON contract from upstream _getdir/listdir.php, never fabricated data. */
    fun parse(body: String, requestedPath: String, legacy: Boolean = false): RemoteDirectoryLookup {
        if (body.length !in 2..150_000) return RemoteDirectoryLookup.Unavailable(
            RemoteDirectoryLookup.Reason.INVALID_RESPONSE)
        return try {
            val json = JSONObject(body)
            val path = canonical(json.getString(if (legacy) "basedir" else "path"))
                ?: return RemoteDirectoryLookup.Unavailable(RemoteDirectoryLookup.Reason.INVALID_RESPONSE)
            if (requestedPath.isNotBlank() && canonical(requestedPath) != path)
                return RemoteDirectoryLookup.Unavailable(RemoteDirectoryLookup.Reason.OUTSIDE_SCOPE)
            val directories = json.getJSONArray(if (legacy) "dirlist" else "directories")
            if (directories.length() > 512)
                return RemoteDirectoryLookup.Unavailable(RemoteDirectoryLookup.Reason.INVALID_RESPONSE)
            val names = mutableListOf<String>()
            val seen = HashSet<String>()
            for (i in 0 until directories.length()) {
                val name = directories.opt(i) as? String
                    ?: return RemoteDirectoryLookup.Unavailable(RemoteDirectoryLookup.Reason.INVALID_RESPONSE)
                // Older ruTorrent may include ".."; navigation is instead limited
                // to the server-observed root, never trusted from a child name.
                if (name == ".." || (legacy && name == ".")) continue
                if (!childName(name) || !seen.add(name))
                    return RemoteDirectoryLookup.Unavailable(RemoteDirectoryLookup.Reason.INVALID_RESPONSE)
                names.add(name)
            }
            RemoteDirectoryLookup.Ready(RemoteDirectoryPage(path, names.sortedWith(
                String.CASE_INSENSITIVE_ORDER)))
        } catch (_: Exception) {
            RemoteDirectoryLookup.Unavailable(RemoteDirectoryLookup.Reason.INVALID_RESPONSE)
        }
    }

    suspend fun read(
        rpc: URI, credentials: RemoteCredentials, requested: String
    ): RemoteDirectoryLookup {
        val modern = endpoint(rpc) ?: return RemoteDirectoryLookup.Unavailable(
            RemoteDirectoryLookup.Reason.UNSUPPORTED)
        val path = if (requested.isEmpty()) "" else canonical(requested)
            ?: return RemoteDirectoryLookup.Unavailable(RemoteDirectoryLookup.Reason.OUTSIDE_SCOPE)
        val first = readEndpoint(modern, credentials, path, legacy = false)
        if (first !is RemoteDirectoryLookup.Unavailable ||
            first.reason != RemoteDirectoryLookup.Reason.UNSUPPORTED) return first
        val older = legacyEndpoint(rpc) ?: return first
        return readEndpoint(older, credentials, path, legacy = true)
    }

    private suspend fun readEndpoint(
        endpoint: URI, credentials: RemoteCredentials, path: String, legacy: Boolean
    ): RemoteDirectoryLookup = withContext(Dispatchers.IO) {
        // The legacy _getdir/info.php route requires mode=dirlist and optional
        // basedir; modern _getdir/listdir.php accepts a 'dir' parameter.
        val query = if (legacy) {
            "mode=dirlist" + (if (path.isEmpty()) "" else
                "&basedir=" + URLEncoder.encode(path, Charsets.UTF_8.name()))
        } else {
            "dir=" + URLEncoder.encode(path, Charsets.UTF_8.name())
        }
        val url = URI.create(endpoint.toString() + "?" + query)
        val target = url.rawPath + "?" + url.rawQuery
        var authorization: String? = null
        try {
            repeat(2) { attempt ->
                val connection = url.toURL().openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 7_000
                connection.readTimeout = 7_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                if (authorization != null)
                    connection.setRequestProperty("Authorization", authorization)
                try {
                    val status = connection.responseCode
                    if (status == 401 && attempt == 0 && credentials.username.isNotBlank()) {
                        val challenges = connection.headerFields.entries.filter {
                            it.key?.equals("WWW-Authenticate", ignoreCase = true) == true
                        }.flatMap { it.value }
                        authorization = try {
                            HttpRpcAuthorization.fromChallenge(
                                challenges, credentials, "GET", target)
                        } catch (cancel: CancellationException) { throw cancel }
                          catch (_: Exception) {
                              return@withContext RemoteDirectoryLookup.Unavailable(
                                  RemoteDirectoryLookup.Reason.AUTHENTICATION)
                          }
                    } else {
                        return@withContext when (status) {
                            200 -> {
                                val bytes = connection.inputStream.use { it.readNBytes(150_001) }
                                if (bytes.size > 150_000) RemoteDirectoryLookup.Unavailable(
                                    RemoteDirectoryLookup.Reason.INVALID_RESPONSE)
                                else parse(bytes.toString(Charsets.UTF_8), path, legacy)
                            }
                            401, 403, 407 -> RemoteDirectoryLookup.Unavailable(
                                RemoteDirectoryLookup.Reason.AUTHENTICATION)
                            404, 405 -> RemoteDirectoryLookup.Unavailable(
                                RemoteDirectoryLookup.Reason.UNSUPPORTED)
                            else -> RemoteDirectoryLookup.Unavailable(
                                RemoteDirectoryLookup.Reason.CONNECTION)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            RemoteDirectoryLookup.Unavailable(RemoteDirectoryLookup.Reason.AUTHENTICATION)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            RemoteDirectoryLookup.Unavailable(RemoteDirectoryLookup.Reason.CONNECTION)
        }
    }
}
