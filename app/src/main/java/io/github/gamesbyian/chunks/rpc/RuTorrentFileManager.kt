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

/** Independent, non-mutating status of one reviewed child path.
 * INCONSISTENT never authorizes retry or a claim of physical creation.
 */
internal enum class RemoteFolderReadback {
    VERIFIED_DIRECTORY, NOT_PRESENT, INCONSISTENT, CANNOT_VERIFY
}

internal object RuTorrentFileManager {
    private const val MAX_RESPONSE_BYTES = 64_000
    private data class Reply(val code: Int, val body: String, val challenges: List<String>)
    private data class Verified(
        val fileRoot: String, val authorization: String?, val challenges: List<String>
    )
    private sealed interface Configuration {
        data class Accepted(val value: Verified) : Configuration
        data class Rejected(val reason: RemoteFolderCreateCapability.Reason) : Configuration
    }

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
    ): Triple<Reply, String?, List<String>> {
        val first = post(endpoint, json, null)
        if (first.code != 401 || credentials.username.isBlank())
            return Triple(first, null, emptyList())
        val target = endpoint.rawPath.orEmpty()
        val authorization = HttpRpcAuthorization.fromChallenge(
            first.challenges, credentials, "POST", target)
        return Triple(post(endpoint, json, authorization), authorization, first.challenges)
    }

    /** Classify capability refusal without exposing hosts, credentials or filesystem paths. */
    private fun config(
        endpoint: URI, credentials: RemoteCredentials, expectedRoot: String
    ): Configuration {
        val (result, authorization, challenges) = read(endpoint, credentials,
            JSONObject().put("method", "getConfig"))
        val reason = when (result.code) {
            404, 405 -> RemoteFolderCreateCapability.Reason.NOT_INSTALLED
            401, 403, 407 -> RemoteFolderCreateCapability.Reason.ACCESS_DENIED
            200 -> null
            else -> RemoteFolderCreateCapability.Reason.CANNOT_VERIFY
        }
        if (reason != null) return Configuration.Rejected(reason)
        val response = runCatching { JSONObject(result.body) }.getOrNull()
            ?: return Configuration.Rejected(RemoteFolderCreateCapability.Reason.CANNOT_VERIFY)
        if (response.has("error") || response.has("errcode"))
            return Configuration.Rejected(RemoteFolderCreateCapability.Reason.CANNOT_VERIFY)
        val fileRoot = RuTorrentDirectoryListing.canonical(response.optString("homedir"))
            ?: return Configuration.Rejected(RemoteFolderCreateCapability.Reason.CANNOT_VERIFY)
        // ruTorrent _getdir may start at rTorrent's current download directory,
        // below File Manager's configured homedir. Only accept this ancestry
        // when every intermediate segment is independently proven to be a
        // *real directory* in File Manager (its symlink rows lack '/').
        // Never accept an unrelated root, a broader '/' root, or traverse up.
        val requestedRelative = relative(fileRoot, expectedRoot)
            ?: return Configuration.Rejected(RemoteFolderCreateCapability.Reason.ROOT_MISMATCH)
        var walked = ""
        for (segment in requestedRelative.split('/').filter { it.isNotEmpty() }) {
            val listing = read(endpoint, credentials,
                JSONObject().put("method", "listDirectory")
                    .put("dir", if (walked.isEmpty()) "/" else walked)).first
            if (listing.code == 401 || listing.code == 403 || listing.code == 407)
                return Configuration.Rejected(RemoteFolderCreateCapability.Reason.ACCESS_DENIED)
            if (listing.code != 200)
                return Configuration.Rejected(RemoteFolderCreateCapability.Reason.CANNOT_VERIFY)
            val entries = directoryNames(listing.body)
                ?: return Configuration.Rejected(RemoteFolderCreateCapability.Reason.CANNOT_VERIFY)
            if (segment + "/" !in entries)
                return Configuration.Rejected(RemoteFolderCreateCapability.Reason.ROOT_MISMATCH)
            walked = if (walked.isEmpty()) segment else walked + "/" + segment
        }
        return Configuration.Accepted(Verified(fileRoot, authorization, challenges))
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
            when (val configuration = config(uri, credentials, root)) {
                is Configuration.Rejected ->
                    RemoteFolderCreateCapability.Unavailable(configuration.reason)
                is Configuration.Accepted -> {
                    // One read-only typed listing demonstrates more than PHP reachability.
                    val list = read(uri, credentials, JSONObject()
                        .put("method", "listDirectory").put("dir", "/")).first
                    val reason = when (list.code) {
                        401, 403, 407 -> RemoteFolderCreateCapability.Reason.ACCESS_DENIED
                        404, 405 -> RemoteFolderCreateCapability.Reason.NOT_INSTALLED
                        else -> RemoteFolderCreateCapability.Reason.CANNOT_VERIFY
                    }
                    if (list.code == 200 && directoryNames(list.body) != null)
                        RemoteFolderCreateCapability.Ready(root)
                    else RemoteFolderCreateCapability.Unavailable(reason)
                }
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
     * Read-only reconciliation after an uncertain one-shot mkdir. Uses the
     * independently typed File Manager listing AND the exact-account _getdir
     * listing. Neither reads bytes, mutates files, nor relies on a POST receipt.
     */
    suspend fun observeChild(
        rpc: URI, credentials: RemoteCredentials, observedRoot: String,
        parent: String, child: String
    ): RemoteFolderReadback = withContext(Dispatchers.IO) {
        if (!validLeaf(child) || relative(observedRoot, parent) == null)
            return@withContext RemoteFolderReadback.CANNOT_VERIFY
        val endpoint = endpoint(rpc) ?: return@withContext RemoteFolderReadback.CANNOT_VERIFY
        try {
            val verified = (config(endpoint, credentials, observedRoot) as? Configuration.Accepted)
                ?.value ?: return@withContext RemoteFolderReadback.CANNOT_VERIFY
            val rel = relative(verified.fileRoot, parent)
                ?: return@withContext RemoteFolderReadback.CANNOT_VERIFY
            val direct = RuTorrentDirectoryListing.read(rpc, credentials, parent)
            if (direct !is RemoteDirectoryLookup.Ready || direct.page.path != parent)
                return@withContext RemoteFolderReadback.CANNOT_VERIFY
            val typed = read(endpoint, credentials,
                JSONObject().put("method", "listDirectory")
                    .put("dir", if (rel.isEmpty()) "/" else rel)).first
            val entries = if (typed.code == 200) directoryNames(typed.body) else null
            if (entries == null) return@withContext RemoteFolderReadback.CANNOT_VERIFY
            val inBrowser = child in direct.page.folders
            val inFileManager = (child + "/") in entries
            when {
                inBrowser && inFileManager -> RemoteFolderReadback.VERIFIED_DIRECTORY
                !inBrowser && !inFileManager && child !in entries ->
                    RemoteFolderReadback.NOT_PRESENT
                else -> RemoteFolderReadback.INCONSISTENT
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { RemoteFolderReadback.CANNOT_VERIFY }
    }

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
        // Restrict the reviewed target to the browser-observed root even if
        // File Manager's own validated root is an ancestor of that tree.
        if (relative(root, parent) == null) return@withContext RemoteFolderCreateOutcome.Refused
        if (!validLeaf(name)) return@withContext RemoteFolderCreateOutcome.Refused
        val confirmedPath = if (parent == "/") "/" + name else parent + "/" + name
        var sent = false
        var reply: Reply? = null
        var rel = ""
        try {
            val verified = (config(endpoint, credentials, root) as? Configuration.Accepted)
                ?.value ?: return@withContext RemoteFolderCreateOutcome.Refused
            // Send relative to *File Manager's* verified homedir, not _getdir's
            // narrower observed start. Otherwise mkdir can target a sibling.
            rel = relative(verified.fileRoot, parent)
                ?: return@withContext RemoteFolderCreateOutcome.Refused
            val target = if (rel.isEmpty()) name else rel + "/" + name
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
            // Digest Authorization includes a nonce-count/client nonce pair.
            // The header used for getConfig may have been consumed already.
            // Construct a FRESH write authorization from the verified, same-
            // endpoint challenge before dispatch; never retry the mutation.
            val writeAuthorization = if (verified.challenges.isEmpty())
                verified.authorization
            else HttpRpcAuthorization.fromChallenge(
                verified.challenges, credentials, "POST", endpoint.rawPath.orEmpty())
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            sent = true
            reply = post(endpoint,
                JSONObject().put("method", "newDirectory").put("target", target),
                writeAuthorization)
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) {
              if (!sent) return@withContext RemoteFolderCreateOutcome.Refused
          }

        // Independent dual-provider readback, including after a lost acknowledgement.
        // _getdir uses PHP is_dir(), which follows symlinks. Verify File
        // Manager's directory-type entry too; a symlink can otherwise appear
        // as a successful child directory even when it escapes the root.
        val after = try {
            RuTorrentDirectoryListing.read(rpc, credentials, parent)
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { null }
        val typedAfter = try {
            val response = read(endpoint, credentials,
                JSONObject().put("method", "listDirectory")
                    .put("dir", if (rel.isEmpty()) "/" else rel)).first
            if (response.code == 200) directoryNames(response.body) else null
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { null }
        if (after is RemoteDirectoryLookup.Ready &&
            name in after.page.folders &&
            typedAfter?.contains(name + "/") == true)
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
