package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Separate, exact-tenant ruTorrent File Manager capability.
 * Only the typed newDirectory command is allowed; never generic file operations
 * or rTorrent execute.*, and no directory is created merely by opening Add.
 */
internal sealed interface RemoteFolderCreateCapability {
    data class Ready(val root: String) : RemoteFolderCreateCapability
    data class Unavailable(val reason: Reason) : RemoteFolderCreateCapability
    enum class Reason { NOT_INSTALLED, ACCESS_DENIED, ROOT_MISMATCH, CANNOT_VERIFY }
}

internal sealed interface RemoteFolderCreateOutcome {
    data class Created(val path: String) : RemoteFolderCreateOutcome
    data object AlreadyExists : RemoteFolderCreateOutcome
    data object Refused : RemoteFolderCreateOutcome
    data object Uncertain : RemoteFolderCreateOutcome
}

internal object RuTorrentFileManager {
    private const val MAX_RESPONSE_BYTES = 64_000
    private data class Reply(val code: Int, val body: String, val challenges: List<String>)
    private data class Verified(val root: String, val authorization: String?)

    fun endpoint(rpc: URI): URI? =
        RuTorrentDirectoryListing.endpoint(rpc)?.let {
            URI.create(it.toString().removeSuffix("_getdir/listdir.php") +
                "filemanager/action.php")
        }

    fun validLeaf(name: String): Boolean =
        name.length in 1..120 && name.trim() == name && name.isNotBlank() &&
            name != "." && name != ".." && name.none {
                it == '/' || it == '\\' || it.code < 32 || it.code == 127
            } && !Regex("%[0-9A-Fa-f]{2}").containsMatchIn(name)

    private fun relative(root: String, absolute: String): String? {
        val canonicalRoot = RuTorrentDirectoryListing.canonical(root) ?: return null
        val canonicalPath = RuTorrentDirectoryListing.canonical(absolute) ?: return null
        if (canonicalRoot == "/" ||
            !RuTorrentDirectoryListing.contains(canonicalRoot, canonicalPath)) return null
        return canonicalPath.removePrefix(canonicalRoot).trimStart('/')
    }

    private fun post(uri: URI, json: JSONObject, authorization: String?): Reply {
        val encoded = "action=" +
            URLEncoder.encode(json.toString(), Charsets.UTF_8.name())
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 7_000
        connection.readTimeout = 7_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        if (authorization != null) connection.setRequestProperty("Authorization", authorization)
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes.size)
        try {
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val challenges = connection.headerFields.entries.filter {
                it.key?.equals("WWW-Authenticate", true) == true
            }.flatMap { it.value }.take(8).filter { it.length <= 8192 }
            val body = if (status == 200) {
                val result = connection.inputStream.use { it.readNBytes(MAX_RESPONSE_BYTES + 1) }
                if (result.size > MAX_RESPONSE_BYTES) throw IOException("Oversized File Manager response")
                result.toString(Charsets.UTF_8)
            } else ""
            return Reply(status, body, challenges)
        } finally {
            connection.disconnect()
        }
    }

    /** Read-only typed API calls can retry once after HTTP authentication; writes cannot. */
    private fun read(
        endpoint: URI, credentials: RemoteCredentials, json: JSONObject
    ): Pair<Reply, String?> {
        val first = post(endpoint, json, null)
        if (first.code != 401 || credentials.username.isBlank()) return first to null
        val target = endpoint.rawPath.orEmpty()
        val authorization = HttpRpcAuthorization.fromChallenge(
            first.challenges, credentials, "POST", target)
        return post(endpoint, json, authorization) to authorization
    }

    private fun config(
        endpoint: URI, credentials: RemoteCredentials, expectedRoot: String
    ): Verified? {
        val (result, authorization) = read(endpoint, credentials,
            JSONObject().put("method", "getConfig"))
        if (result.code != 200) return null
        val response = JSONObject(result.body)
        if (response.has("error") || response.has("errcode")) return null
        val root = RuTorrentDirectoryListing.canonical(response.optString("homedir"))
            ?: return null
        return if (root != expectedRoot || root == "/") null else Verified(root, authorization)
    }

    suspend fun capability(
        rpc: URI, credentials: RemoteCredentials, observedRoot: String
    ): RemoteFolderCreateCapability = withContext(Dispatchers.IO) {
        val uri = endpoint(rpc) ?: return@withContext RemoteFolderCreateCapability.Unavailable(
            RemoteFolderCreateCapability.Reason.NOT_INSTALLED)
        val root = RuTorrentDirectoryListing.canonical(observedRoot)
            ?: return@withContext RemoteFolderCreateCapability.Unavailable(
                RemoteFolderCreateCapability.Reason.ROOT_MISMATCH)
        try {
            val verified = config(uri, credentials, root)
            if (verified == null) RemoteFolderCreateCapability.Unavailable(
                RemoteFolderCreateCapability.Reason.CANNOT_VERIFY)
            else {
                // A successful read-only list on the same File Manager root
                // establishes that this is not merely an unrelated PHP page.
                val list = read(uri, credentials, JSONObject()
                    .put("method", "listDirectory").put("dir", "/")).first
                if (list.code == 200 && directoryNames(list.body) != null)
                    RemoteFolderCreateCapability.Ready(root)
                else RemoteFolderCreateCapability.Unavailable(
                    RemoteFolderCreateCapability.Reason.ACCESS_DENIED)
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) {
              RemoteFolderCreateCapability.Unavailable(
                  RemoteFolderCreateCapability.Reason.CANNOT_VERIFY)
          }
    }

    /** File Manager uses '/' suffix for real directory rows; symlinks lack it. */
    internal fun directoryNames(body: String): Set<String>? = try {
        if (body.length !in 2..MAX_RESPONSE_BYTES) null
        else {
            val json = JSONObject(body)
            if (json.has("error") || json.has("errcode")) null
            else {
                val entries = json.getJSONArray("listing")
                if (entries.length() > 1024) null
                else buildSet {
                    for (index in 0 until entries.length()) {
                        val name = entries.getJSONObject(index).getString("name")
                        if (name.length !in 1..255 || name.any { it.code < 32 }) throw IOException()
                        add(name)
                    }
                }
            }
        }
    } catch (_: Exception) { null }

    /**
     * Explicit one-child mutation, with fresh exact-root check and read-only
     * corroboration of every parent component. No retry after dispatch. The
     * PHP provider may use mkdir -p internally; verifying an existing parent
     * means Chunks never intentionally asks it to create missing ancestors.
     */
    suspend fun create(
        rpc: URI, credentials: RemoteCredentials, root: String,
        parent: String, name: String
    ): RemoteFolderCreateOutcome = withContext(Dispatchers.IO) {
        val endpoint = endpoint(rpc) ?: return@withContext RemoteFolderCreateOutcome.Refused
        val rel = relative(root, parent) ?: return@withContext RemoteFolderCreateOutcome.Refused
        if (!validLeaf(name)) return@withContext RemoteFolderCreateOutcome.Refused
        val target = if (rel.isEmpty()) name else rel + "/" + name
        val confirmedPath = if (parent == "/") "/" + name else parent + "/" + name
        var sent = false
        var reply: Reply? = null
        try {
            val verified = config(endpoint, credentials, root)
                ?: return@withContext RemoteFolderCreateOutcome.Refused
            // Verify each intermediate component is a directory, not a symlink.
            var walked = ""
            for (segment in rel.split('/').filter { it.isNotEmpty() }) {
                val listed = read(endpoint, credentials,
                    JSONObject().put("method", "listDirectory")
                        .put("dir", if (walked.isEmpty()) "/" else walked)).first
                val names = if (listed.code == 200) directoryNames(listed.body) else null
                if (names == null || segment + "/" !in names)
                    return@withContext RemoteFolderCreateOutcome.Refused
                walked = if (walked.isEmpty()) segment else walked + "/" + segment
            }
            // Cross-check this actual parent with _getdir before any mutation.
            val before = RuTorrentDirectoryListing.read(rpc, credentials, parent)
            if (before !is RemoteDirectoryLookup.Ready || before.page.path != parent)
                return@withContext RemoteFolderCreateOutcome.Refused
            if (name in before.page.folders) return@withContext RemoteFolderCreateOutcome.AlreadyExists
            val parentList = read(endpoint, credentials,
                JSONObject().put("method", "listDirectory")
                    .put("dir", if (rel.isEmpty()) "/" else rel)).first
            val parentEntries = if (parentList.code == 200)
                directoryNames(parentList.body) else null
            if (parentEntries == null) return@withContext RemoteFolderCreateOutcome.Refused
            if (name in parentEntries || name + "/" in parentEntries)
                return@withContext RemoteFolderCreateOutcome.AlreadyExists

            // Only a user-initiated create may reach this point. Never submit
            // again after 401, timeout, cancellation or malformed receipt.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            sent = true
            reply = post(endpoint,
                JSONObject().put("method", "newDirectory").put("target", target),
                verified.authorization)
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) {
              if (!sent) return@withContext RemoteFolderCreateOutcome.Refused
          }

        // Independent _getdir readback, including after a lost acknowledgement.
        val after = try {
            RuTorrentDirectoryListing.read(rpc, credentials, parent)
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { null }
        if (after is RemoteDirectoryLookup.Ready && name in after.page.folders)
            return@withContext RemoteFolderCreateOutcome.Created(confirmedPath)
        // The write may have reached the provider. Never automatically replay it.
        val explicitRefusal = reply?.takeIf { it.code == 200 }?.let { response ->
            runCatching { JSONObject(response.body).optBoolean("error", false) ||
                JSONObject(response.body).has("errcode") }.getOrDefault(false)
        } == true
        if (explicitRefusal) RemoteFolderCreateOutcome.Refused
        else if (sent) RemoteFolderCreateOutcome.Uncertain
        else RemoteFolderCreateOutcome.Refused
    }
}
