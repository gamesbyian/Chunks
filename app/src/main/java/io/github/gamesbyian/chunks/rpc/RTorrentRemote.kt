package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RemoteCredentials(val endpoint: String, val username: String, val password: String)

/**
 * Method enumeration is nullable: legacy or restricted installations may
 * reject system.listMethods even when ordinary RPC commands work.
 */
data class RemoteProbe(val version: String, val advertisedMethods: Set<String>?) {
    val multicallAdvertised: Boolean? get() = advertisedMethods?.contains("d.multicall2")
}

/** Stable identity and observed values from one specific rTorrent server snapshot. */
data class RemoteTransfer(
    val hash: String,
    val name: String,
    val sizeBytes: Long,
    val completedBytes: Long,
    val started: Boolean,
    val directory: String,
    val downBytesPerSecond: Long? = null,
    val upBytesPerSecond: Long? = null,
    val ratioThousandths: Long? = null
) {
    val fractionComplete: Float
        get() = if (sizeBytes > 0) (completedBytes.toDouble() / sizeBytes).coerceIn(0.0, 1.0).toFloat() else 0f
}

data class RemoteFile(val path: String, val sizeBytes: Long, val priority: Int)

/**
 * Read-only facts for fetching one transfer's bytes from this server as a
 * BitTorrent peer. [rpcHost] is the host Chunks reaches the RPC endpoint at;
 * [announcedAddress] is rTorrent's own configured public address, if any.
 */
data class RemotePeerSource(
    val rpcHost: String,
    val announcedAddress: String?,
    val listenPort: Long,
    val isPrivate: Boolean,
    val isActive: Boolean,
    /** network.bind_address when it names one specific address, else null. */
    val boundAddress: String? = null
)

/**
 * d.erase removes an rTorrent session item; ordinary payload bytes remain.
 * Custom server hooks can have different side effects, so this is NOT
 * a promise about filesystem state. It is not a delete-with-data API.
 */
sealed class SessionEraseOutcome {
    data class VerifiedRemoved(val hash: String) : SessionEraseOutcome()
    data class AlreadyAbsent(val hash: String) : SessionEraseOutcome()
    data class PendingRemoval(val hash: String) : SessionEraseOutcome()
    data class Uncertain(val hash: String) : SessionEraseOutcome()
}


/**
 * One exact file priority change, not a blanket torrent or disk operation.
 * A lost acknowledgement after either write must never be retried blindly.
 */
sealed class FilePriorityOutcome {
    data class Verified(val hash: String, val fileIndex: Int, val priority: Int) : FilePriorityOutcome()
    data class Unchanged(val hash: String, val fileIndex: Int, val priority: Int) : FilePriorityOutcome()
    data class PendingVerification(val hash: String, val fileIndex: Int) : FilePriorityOutcome()
    data class Uncertain(val hash: String, val fileIndex: Int) : FilePriorityOutcome()
}


/**
 * Acknowledged means the RPC returned without a fault. Verified means a
 * subsequent read reports the requested engine state. Uncertain means
 * an RPC may have applied but its outcome could not be read back.
 */
sealed class LifecycleOutcome {
    data class Verified(val started: Boolean) : LifecycleOutcome()
    data class Pending(val requestedStarted: Boolean, val observedStarted: Boolean) : LifecycleOutcome()
    data class Uncertain(val requestedStarted: Boolean) : LifecycleOutcome()
}


/**
 * rTorrent XML-RPC adapter with strictly named, hash-scoped operations.\n * No automatic retries after a mutation and no raw arbitrary command surface.
 * HTTPS only, except HTTP on literal loopback used for controlled development fixtures.
 */
class RTorrentRemote(private val auth: RemoteCredentials) {
    private val endpoint: URI = URI(RemoteEndpointHints.rpcAddress(auth.endpoint)).also { uri ->
        require(uri.scheme?.lowercase() == "https" ||
            (uri.scheme?.lowercase() == "http" && uri.host in listOf("localhost", "127.0.0.1", "[::1]"))) {
            "Use an HTTPS reverse proxy to connect securely to rTorrent"
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "Invalid rTorrent endpoint"
        }
        // Stored/explicit RemoteCredentials can bypass Smart Connect. Refuse
        // paths that a reverse proxy might decode into a different RPC mount,
        // even after a second or third percent-decoding pass. Benign %20 and
        // %25 in hosted account names remain valid.
        val rawPath = uri.rawPath.orEmpty()
        val encodedSeparatorOrControl = Regex("(?i)%(?:25){0,2}(?:2f|5c|0[0-9a-f]|1[0-9a-f]|7f)")
        val dotTraversalSegment = Regex("(?i)(?:^|/)(?:\\.|%2e|%252e|%25252e){1,2}(?:/|$)")
        require(!encodedSeparatorOrControl.containsMatchIn(rawPath) &&
            !dotTraversalSegment.containsMatchIn(rawPath)) {
            "Ambiguous or unsafe rTorrent RPC path; use an exact unencoded server mount"
        }
    }

    suspend fun version(): String =
        call("system.client_version") as? String ?: error("Unexpected rTorrent version reply")

    suspend fun probe(): RemoteProbe {
        val serverVersion = version()
        val methods = try {
            (call("system.listMethods") as? List<*>)?.map { item ->
                item as? String ?: error("Non-string RPC method name")
            }?.toSet()
        } catch (_: RpcFault) {
            // Missing introspection does not imply a missing torrent-list method.
            null
        }
        return RemoteProbe(serverVersion, methods)
    }

    /**
     * The server's configured default download directory (read-only). Used only
     * to *suggest* a Place; Adds still require an explicit user-confirmed path.
     */
    suspend fun defaultDirectory(): String? {
        val value = try { call("directory.default") } catch (_: RpcFault) { return null }
        val path = (value as? String)?.trim()?.takeIf { it.startsWith("/") && it.length <= 4096 } ?: return null
        return path.trimEnd('/').ifEmpty { "/" }
    }

    /**
     * Read-only exact-identity lookup outside any view: the torrent's name if the
     * session holds it, null if rTorrent reports the hash unknown. Used to
     * reconcile an acknowledged Add that has not (yet) appeared in a view.
     */
    suspend fun lookupName(hash: String): String? {
        require(Regex("[0-9a-fA-F]{40}([0-9a-fA-F]{24})?").matches(hash)) { "Invalid torrent identity" }
        return try {
            (call("d.name", listOf(hash)) as? String)?.take(2048)
                ?: error("Unexpected exact-hash name reply")
        } catch (fault: RpcFault) {
            // Only rTorrent's "unknown info-hash" reply (legacy or modern) proves absence.
            // Unknown method/server faults must not be mistaken for a missing Add.
            if (fault.isUnknownInfoHash()) null else throw fault
        }
    }

    /**
     * Read-only: what the phone engine needs to download this exact transfer
     * directly from this server's BitTorrent listener. Changes nothing.
     */
    suspend fun peerSource(hash: String): RemotePeerSource {
        require(Regex("[0-9a-fA-F]{40}").matches(hash)) { "Invalid v1 torrent hash" }
        val announced = try {
            (call("network.local_address") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 253 }
        } catch (_: RpcFault) { null }
        // Seedboxes often bind rTorrent to an address other than the web
        // host's; a wildcard bind says nothing about which one.
        val bound = try {
            (call("network.bind_address") as? String)?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 253 && it !in setOf("0.0.0.0", "::", "[::]") }
        } catch (_: RpcFault) { null }
        return RemotePeerSource(
            rpcHost = endpoint.host.removePrefix("[").removeSuffix("]"),
            announcedAddress = announced,
            boundAddress = bound,
            listenPort = number(call("network.listen.port")),
            isPrivate = number(call("d.is_private", listOf(hash))) != 0L,
            isActive = number(call("d.is_active", listOf(hash))) != 0L
        )
    }

    /** Only server-owned view names, never XML-RPC command fragments. */
    suspend fun listTransfers(view: String = "main"): List<RemoteTransfer> {
        require(Regex("[A-Za-z][A-Za-z0-9_-]{0,39}").matches(view)) { "Invalid view" }
        val minimum = MINIMUM_FIELDS
        val extended = EXTENDED_FIELDS
        // Optional fields vary across rTorrent builds. Fall back only for
        // an explicit server-side command fault, not on timeouts or network loss.
        // A queue too large for one bounded reply is re-read in pages instead.
        val (rows, rich) = try {
            try {
                extractRows(call("d.multicall2", listOf("", view) + extended)) to true
            } catch (_: RpcFault) {
                extractRows(call("d.multicall2", listOf("", view) + minimum)) to false
            }
        } catch (_: RpcReplyTooLarge) {
            return listTransfersPaged(view)
        }
        require(rows.size <= MAX_TRANSFERS) { "Unexpectedly large torrent list" }
        val fields = if (rich) extended.size else minimum.size
        return requireUniqueIdentities(rows.map { raw ->
            val row = raw as? List<*> ?: error("Invalid torrent row")
            require(row.size == fields) { "Incompatible rTorrent field layout" }
            transferFromRow(row, rich)
        })
    }

    /**
     * Large-queue path: one bounded `download_list` for identities, then
     * `system.multicall` pages of exact-hash getters. Any per-call fault (for
     * example a torrent erased mid-read) fails the whole snapshot closed rather
     * than publishing a partial or mixed list. Read-only; never retried here.
     */
    internal suspend fun listTransfersPaged(view: String): List<RemoteTransfer> {
        require(Regex("[A-Za-z][A-Za-z0-9_-]{0,39}").matches(view)) { "Invalid view" }
        val minimum = MINIMUM_FIELDS
        val extended = EXTENDED_FIELDS
        val hashes = readViewIdentities(view)
        if (hashes.isEmpty()) {
            // Even an empty first read is not a stable snapshot: a server
            // could add a transfer before the paged fallback returns.
            check(readViewIdentities(view).isEmpty()) {
                "rTorrent view membership changed during paged read; refresh"
            }
            return emptyList()
        }

        fun getters(fields: List<String>) = fields.map { it.removeSuffix("=") }
        val rich = try {
            pagedRows(hashes.take(1), getters(extended))
            true
        } catch (_: RpcFault) { false }
        val fields = getters(if (rich) extended else minimum)
        val transfers = ArrayList<RemoteTransfer>(hashes.size)
        for (page in hashes.chunked(PAGE_SIZE)) {
            val rows = pagedRows(page, fields)
            for ((index, row) in rows.withIndex()) {
                val transfer = transferFromRow(row, rich)
                require(transfer.hash.equals(page[index], ignoreCase = true)) {
                    "rTorrent returned a different torrent than requested"
                }
                transfers += transfer
            }
        }
        // The view can change while thousands of exact-hash getters are read.
        // Refuse to publish a supposedly complete mixed-time inventory if its
        // membership changed; do not retry or assume that page success proves
        // a stable view. Reordering alone does not invalidate membership.
        val endHashes = readViewIdentities(view)
        check(hashes.size == endHashes.size &&
            hashes.map { it.uppercase(java.util.Locale.ROOT) }.toSet() ==
            endHashes.map { it.uppercase(java.util.Locale.ROOT) }.toSet()) {
            "rTorrent view membership changed during paged read; refresh"
        }
        return requireUniqueIdentities(transfers)
    }

    private suspend fun readViewIdentities(view: String): List<String> {
        val hashes = extractRows(call("download_list", listOf("", view))).map {
            val hash = it as? String ?: error("Invalid torrent identity")
            require(identityPattern.matches(hash)) { "Invalid torrent infohash in server view" }
            hash
        }
        require(hashes.size <= MAX_TRANSFERS) { "Unexpectedly large torrent list" }
        require(hashes.map { it.uppercase(java.util.Locale.ROOT) }.toSet().size == hashes.size) {
            "rTorrent server view contains duplicate transfer identities"
        }
        return hashes
    }

    private suspend fun pagedRows(hashes: List<String>, getters: List<String>): List<List<Any?>> {
        val calls = hashes.flatMap { hash -> getters.map { mapOf("methodName" to it, "params" to listOf(hash)) } }
        val results = extractRows(call("system.multicall", listOf(calls)))
        require(results.size == calls.size) { "Incomplete rTorrent multicall reply" }
        return results.chunked(getters.size).map { row ->
            row.map { result ->
                val fault = result as? Map<*, *>
                if (fault != null) throw RpcFault(fault["faultCode"]?.toString() ?: "unknown",
                    "Transfer list changed or a field is unsupported; refresh to try again")
                val single = result as? List<*> ?: error("Invalid multicall result")
                require(single.size == 1) { "Invalid multicall result" }
                single[0]
            }
        }
    }

    private fun transferFromRow(row: List<*>, rich: Boolean): RemoteTransfer {
        val hash = row[0] as? String ?: error("Missing info hash")
        require(identityPattern.matches(hash)) { "Invalid torrent infohash in server view" }
        return RemoteTransfer(
            hash = hash,
            name = (row[1] as? String ?: error("Missing torrent name")).take(2048),
            sizeBytes = number(row[2]).coerceAtLeast(0),
            completedBytes = number(row[3]).coerceAtLeast(0),
            started = number(row[4]) != 0L,
            directory = (row[5] as? String ?: error("Missing server path")).take(4096),
            downBytesPerSecond = if (rich) number(row[6]).coerceAtLeast(0) else null,
            upBytesPerSecond = if (rich) number(row[7]).coerceAtLeast(0) else null,
            ratioThousandths = if (rich) number(row[8]).coerceAtLeast(0) else null
        )
    }

    // A case-insensitive duplicate hash in a single server view cannot be
    // treated as two independent Transfers. Compose uses infohash for keys,
    // and all control RPCs target it exactly. Refuse the entire ambiguous
    // snapshot; never silently pick one row or merge differing metadata.
    private fun requireUniqueIdentities(parsed: List<RemoteTransfer>): List<RemoteTransfer> {
        val identities = HashSet<String>(parsed.size)
        require(parsed.all {
            identities.add(it.hash.uppercase(java.util.Locale.ROOT))
        }) { "rTorrent server view contains duplicate transfer identities" }
        return parsed
    }

    /**
     * Load a NEW test transfer stopped at an explicit server directory.
     *
     * The d.directory.set post-load expression selects a parent destination
     * consistently for single- and multi-file transfers. For multi-file
     * torrents, rTorrent creates/uses a child named after the torrent.
     * There is no automatic retry,
     * external content download, filesystem move, or silent duplicate edit.
     * Real hosted-server acceptance is still required.
     */
    /** Refuse a stale default preview before sending even the first Add write. */
    private suspend fun requireCurrentServerDefault(plan: StoppedRemoteAddPlan) {
        if (plan.useServerDefault) {
            check(defaultDirectory() == plan.destination) {
                "Server default location is missing or changed. Review this Add again."
            }
        }
    }

    suspend fun loadStopped(plan: StoppedRemoteAddPlan): RemoteAddOutcome {
        val existing = listTransfers("main")
        if (existing.any { it.hash.equals(plan.expectedInfoHash, ignoreCase = true) }) {
            return RemoteAddOutcome.AlreadyPresent(plan.expectedInfoHash)
        }
        // View membership cannot prove absence from the server session.
        // This exact-hash read is required before any non-idempotent load.
        if (lookupName(plan.expectedInfoHash) != null) {
            return RemoteAddOutcome.AlreadyPresent(plan.expectedInfoHash)
        }
        requireCurrentServerDefault(plan)
        try {
            val params: List<Any> = if (plan.useServerDefault) listOf("", plan.source)
                else listOf("", plan.source, "d.directory.set=" + plan.destination)
            call("load.verbose", params)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // The backend may apply load before returning a timeout, fault,
            // truncated XML, or other malformed acknowledgement. None proves
            // rollback or authorizes a second Add.
            return RemoteAddOutcome.Uncertain(plan.expectedInfoHash)
        }
        return awaitFreshStoppedAddReadback(plan)
    }

    /**
     * Load exact validated v1 .torrent metadata bytes with an explicitly
     * chosen server directory. The raw bytes are sent as XML-RPC base64,
     * matching the manually testable Python load.raw_verbose protocol.
     *
     * No implicit start, automatic retry, overwrite, filesystem move or
     * post-failure cleanup. Live Seedhost acceptance remains separate.
     */
    suspend fun loadStoppedTorrentBytes(
        torrentBytes: ByteArray, destination: String, useServerDefault: Boolean = false
    ): RemoteAddOutcome {
        val inspected = TorrentMetainfoReader.preview(torrentBytes)
        val hash = inspected.infoHashV1
        val link = "magnet:?xt=urn:btih:" + hash
        val plan = if (useServerDefault) StoppedRemoteAddPlan.fromMagnetAtServerDefault(link, destination)
            else StoppedRemoteAddPlan.fromMagnet(link, destination)
        if (listTransfers("main").any { it.hash.equals(hash, ignoreCase = true) }) {
            return RemoteAddOutcome.AlreadyPresent(hash)
        }
        if (lookupName(hash) != null) return RemoteAddOutcome.AlreadyPresent(hash)
        requireCurrentServerDefault(plan)
        try {
            val params: List<Any> = if (plan.useServerDefault) listOf("", torrentBytes)
                else listOf("", torrentBytes, "d.directory.set=" + plan.destination)
            call("load.raw_verbose", params)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // A malformed acknowledgement may arrive after load.raw_verbose
            // was applied. Never retry an uncertain non-idempotent Add.
            return RemoteAddOutcome.Uncertain(hash)
        }
        return awaitFreshStoppedAddReadback(plan)
    }

    /**
     * READ ONLY: reconcile a previous stopped Add after an uncertain RPC reply.
     * Uses only server inventory + exact-hash directory/type/state getters.
     * Never invokes load, starts a torrent, changes a directory or retries.
     *
     * VerifiedStopped proves the desired observed state, NOT whether this
     * session or a different client originally added the torrent.
     */
    /**
     * ruTorrent's own upload endpoint, derived only when the RPC address is
     * ruTorrent's HTTP-RPC plugin on the same host. Null for plain RPC gateways.
     */
    internal fun ruTorrentUploadAddress(): URI? {
        val suffix = "/plugins/httprpc/action.php"
        val path = endpoint.rawPath ?: return null
        if (!path.endsWith(suffix)) return null
        // endpoint.rawPath is already percent encoded; the multi-argument URI
        // constructor would encode '%' again and break hosted account roots.
        val rawUploadPath = path.removeSuffix(suffix) + "/php/addtorrent.php"
        return URI.create("${endpoint.scheme}://${endpoint.rawAuthority}$rawUploadPath")
    }

    /**
     * Stopped Add through ruTorrent's upload form, for hosts that acknowledge
     * but drop XML-RPC `load.*` requests. Same contract as [loadStoppedTorrentBytes]:
     * explicit parent folder (`d.directory.set` semantics), duplicate preflight,
     * no retry, and success only after exact-hash readback of hash, folder and
     * stopped state. A transport failure after sending is Uncertain.
     */
    suspend fun loadStoppedViaRuTorrentUpload(torrentBytes: ByteArray, destination: String): RemoteAddOutcome {
        val hash = TorrentMetainfoReader.preview(torrentBytes).infoHashV1
        val plan = StoppedRemoteAddPlan.fromMagnet("magnet:?xt=urn:btih:" + hash, destination)
        val upload = ruTorrentUploadAddress() ?: throw IllegalStateException("This server has no ruTorrent upload endpoint")
        if (listTransfers("main").any { it.hash.equals(hash, ignoreCase = true) }) {
            return RemoteAddOutcome.AlreadyPresent(hash)
        }
        if (lookupName(hash) != null) return RemoteAddOutcome.AlreadyPresent(hash)
        try {
            postRuTorrentUpload(upload, plan.destination, torrentBytes)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Upload may have been applied despite a broken gateway response.
            return RemoteAddOutcome.Uncertain(hash)
        }
        // ruTorrent's reply text is not proof; only the server's own state is.
        for (attempt in 1..10) {
            val outcome = reconcileStoppedAdd(plan)
            if (outcome !is RemoteAddOutcome.PendingVerification) return outcome
            kotlinx.coroutines.delay(500)
        }
        return RemoteAddOutcome.PendingVerification(hash)
    }

    private suspend fun postRuTorrentUpload(upload: URI, destination: String, torrentBytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val boundary = "----chunks" + java.util.UUID.randomUUID().toString().replace("-", "")
            val body = java.io.ByteArrayOutputStream().apply {
                fun field(name: String, value: String) = write(("--$boundary\r\nContent-Disposition: form-data; " +
                    "name=\"$name\"\r\n\r\n$value\r\n").toByteArray(Charsets.UTF_8))
                field("dir_edit", destination)
                field("torrents_start_stopped", "1")
                write(("--$boundary\r\nContent-Disposition: form-data; name=\"torrent_file\"; " +
                    "filename=\"chunks.torrent\"\r\nContent-Type: application/x-bittorrent\r\n\r\n").toByteArray(Charsets.UTF_8))
                write(torrentBytes)
                write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }.toByteArray()
            val requestTarget = (upload.rawPath ?: "/") + (upload.rawQuery?.let { "?" + it } ?: "")
            var authorization: String? = null
            var attemptedChallenge = false
            while (true) {
                val connection = upload.toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                if (authorization != null) connection.setRequestProperty("Authorization", authorization)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                try {
                    connection.outputStream.use { it.write(body) }
                    val status = connection.responseCode
                    if (status == HttpURLConnection.HTTP_UNAUTHORIZED && !attemptedChallenge && auth.username.isNotEmpty()) {
                        val challenges = connection.headerFields.entries
                            .filter { (name, _) -> name?.equals("WWW-Authenticate", true) == true }
                            .flatMap { it.value }
                        authorization = HttpRpcAuthorization.fromChallenge(challenges, auth, "POST", requestTarget)
                        attemptedChallenge = true
                        continue
                    }
                    // ruTorrent answers the form with 200 or a same-page redirect; never follow it.
                    if (status !in 200..399) throw IOException("ruTorrent upload returned HTTP $status")
                    val stream = if (status in 200..299) connection.inputStream else null
                    return@withContext stream?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (out.size() < 65_536) {
                            val read = input.read(buffer, 0, minOf(buffer.size, 65_536 - out.size()))
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                        out.toString("UTF-8")
                    }.orEmpty()
                } finally {
                    connection.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Upload challenge negotiation exhausted")
        }

    /**
     * rTorrent may acknowledge load.verbose/load.raw_verbose before the new
     * torrent is visible through the main view. Wait a bounded ~2.8s using
     * READ-ONLY exact-hash observations. This never repeats Add, changes a
     * directory or silently starts a torrent. Start-after-Add may proceed
     * once the original reviewed identity, stopped state and Place agree.
     * A gateway error or unknown result exits immediately without retrying.
     */
    private suspend fun awaitFreshStoppedAddReadback(
        plan: StoppedRemoteAddPlan
    ): RemoteAddOutcome {
        for (attempt in 0 until 8) {
            val outcome = reconcileStoppedAdd(plan)
            if (outcome !is RemoteAddOutcome.PendingVerification) return outcome
            if (attempt < 7) kotlinx.coroutines.delay(400L)
        }
        return RemoteAddOutcome.PendingVerification(plan.expectedInfoHash)
    }

    suspend fun reconcileStoppedAdd(plan: StoppedRemoteAddPlan): RemoteAddOutcome {
        return try {
            val matches = listTransfers("main").filter {
                it.hash.equals(plan.expectedInfoHash, ignoreCase = true)
            }
            if (matches.size != 1) {
                RemoteAddOutcome.PendingVerification(plan.expectedInfoHash)
            } else {
                val item = matches.single()
                val directory = readVerifiedAddDirectory(
                    plan.expectedInfoHash, plan.destination, item.name)
                val started = number(call("d.state", listOf(plan.expectedInfoHash))) != 0L
                if (!item.started && !started && directory != null) {
                    RemoteAddOutcome.VerifiedStopped(plan.expectedInfoHash, directory)
                } else {
                    RemoteAddOutcome.PendingVerification(plan.expectedInfoHash)
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: RpcFault) {
            RemoteAddOutcome.PendingVerification(plan.expectedInfoHash)
        } catch (_: Exception) {
            // A successful Add followed by a malformed readback is uncertain,
            // not a failure that invites a duplicate load.
            RemoteAddOutcome.Uncertain(plan.expectedInfoHash)
        }
    }

    /**
     * A user-selected Add destination is the *parent* directory. rTorrent's
     * d.directory_base.set is NOT suitable here: for multi-file torrents it
     * suppresses the torrent-name subdirectory, potentially colliding with
     * unrelated existing files. Use d.directory.set and verify the resulting
     * root according to d.is_multi_file and the reported torrent name.
     */
    private suspend fun readVerifiedAddDirectory(
        hash: String, parent: String, name: String
    ): String? {
        val directory = call("d.directory", listOf(hash)) as? String ?: return null
        val multi = number(call("d.is_multi_file", listOf(hash))) != 0L
        val expected = if (multi) {
            if (name.isBlank() || name == "." || name == ".." ||
                name.contains('/') || name.contains('\\') || name.any { it.code < 32 }) {
                return null
            }
            parent + "/" + name
        } else parent
        return directory.takeIf { it == expected }
    }

    /**
     * Explicitly remove one known torrent identity from rTorrent's session.
     *
     * Name and path are optimistic concurrency guards against stale UI state.
     * No payload deletion, automatic retries, or implicit cleanup. An
     * HTTP timeout after d.erase is not proof of failure.
     */
    suspend fun eraseFromSession(
        hash: String, expectedName: String, expectedDirectory: String
    ): SessionEraseOutcome {
        require(Regex("[0-9a-fA-F]{40}").matches(hash)) {
            "Only exact 40-character torrent hashes can be erased"
        }
        require(expectedName.isNotBlank() && expectedDirectory.startsWith("/")) {
            "Expected current torrent name and absolute directory"
        }
        val matching = listTransfers("main").filter {
            it.hash.equals(hash, ignoreCase = true)
        }
        if (matching.isEmpty()) {
            // A view may hide a torrent without removing it from the session.
            // The exact-hash getter is independent of view membership.
            if (!sessionContainsExactHash(hash)) return SessionEraseOutcome.AlreadyAbsent(hash)
            error("Torrent still exists outside the main view; refresh before removal")
        }
        require(matching.size == 1) { "Ambiguous torrent identity" }
        require(matching.single().name == expectedName &&
            matching.single().directory == expectedDirectory) {
            "Torrent name or location changed; refresh before removal"
        }
        // Guard again outside the view before the irreversible session write.
        check(sessionContainsExactHash(hash)) { "Torrent disappeared before removal; refresh" }
        try {
            call("d.erase", listOf(hash))
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Even a malformed success/fault envelope may follow an applied
            // erase. Never repeat an ambiguous session mutation.
            return SessionEraseOutcome.Uncertain(hash)
        }
        return try {
            if (sessionContainsExactHash(hash)) SessionEraseOutcome.PendingRemoval(hash)
            else SessionEraseOutcome.VerifiedRemoved(hash)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Invalid or unavailable readback never proves absence.
            SessionEraseOutcome.Uncertain(hash)
        }
    }


    /** Read-only session identity independent of any visible rTorrent view. */
    private suspend fun sessionContainsExactHash(hash: String): Boolean = try {
        val returned = call("d.hash", listOf(hash)) as? String
        check(returned?.equals(hash, ignoreCase = true) == true) {
            "Unexpected rTorrent exact-hash identity reply"
        }
        true
    } catch (fault: RpcFault) {
        // Only rTorrent's "unknown info-hash" reply (legacy -501 or modern -500)
        // proves absence; other faults must never authorize mutation.
        if (fault.isUnknownInfoHash()) false else throw fault
    }

    /** A per-torrent read-only file inspector; never changes file priorities. */
    suspend fun listFiles(hash: String): List<RemoteFile> {
        require(Regex("[0-9a-fA-F]{40}([0-9a-fA-F]{24})?").matches(hash)) {
            "Invalid info hash"
        }
        val response = call("f.multicall", listOf(hash, "", "f.path=", "f.size_bytes=", "f.priority="))
        val rows = extractRows(response)
        require(rows.size <= 12000) { "Too many files in torrent" }
        return rows.map { item ->
            val columns = item as? List<*> ?: error("Invalid remote file")
            require(columns.size == 3) { "Invalid remote file fields" }
            RemoteFile(
                path = (columns[0] as? String ?: error("Missing file path")).take(4096),
                sizeBytes = number(columns[1]).coerceAtLeast(0),
                priority = number(columns[2]).toInt()
            )
        }
    }

    /** One file with rTorrent's own hash-verified chunk counts. */
    data class FileCompletion(
        val path: String, val sizeBytes: Long, val priority: Int,
        val completedChunks: Long, val sizeChunks: Long
    ) {
        /** Every chunk touching this file has passed rTorrent's hash check. */
        val verifiedComplete: Boolean get() = sizeChunks > 0 && completedChunks >= sizeChunks
    }

    /**
     * Read-only, identity-guarded per-file completion for Finish in Order.
     * Completion comes from the torrent's hash-verified chunk bitfield over
     * each file's half-open chunk range [f.range_first, f.range_second).
     * rTorrent 0.9.8's f.completed_chunks over-counts at file boundaries
     * (seen on the disposable rig), so it is not used.
     */
    suspend fun readFileCompletion(hash: String): List<FileCompletion> {
        require(Regex("[0-9a-fA-F]{40}").matches(hash)) { "Invalid v1 torrent hash" }
        suspend fun guardIdentity() {
            check((call("d.hash", listOf(hash)) as? String)?.equals(hash, ignoreCase = true) == true) {
                "rTorrent transfer identity not confirmed"
            }
        }
        guardIdentity()
        val totalChunks = number(call("d.size_chunks", listOf(hash)))
        require(totalChunks in 0L..10_000_000L) { "Invalid chunk count" }
        val bitfield = (call("d.bitfield", listOf(hash)) as? String).orEmpty().trim()
        require(bitfield.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } &&
            bitfield.length.toLong() * 4 >= totalChunks) { "Invalid chunk bitfield" }
        // Index each nibble once: a malicious/malformed overlapping file
        // inventory must not turn a bounded bitfield read into billions of
        // per-file piece checks. All ranges are still verified individually.
        val checkedPieces = RemoteVerifiedChunkIndex(bitfield, totalChunks)
        val rows = extractRows(call("f.multicall", listOf(hash, "", "f.path=", "f.size_bytes=",
            "f.priority=", "f.range_first=", "f.range_second=")))
        require(rows.size <= 12000) { "Too many files in torrent" }
        val files = rows.map { item ->
            val c = item as? List<*> ?: error("Invalid remote file")
            require(c.size == 5) { "Invalid remote file fields" }
            val first = number(c[3])
            val second = number(c[4])
            require(first in 0..totalChunks && second in first..totalChunks) { "Invalid file chunk range" }
            FileCompletion((c[0] as? String ?: error("Missing file path")).take(4096),
                number(c[1]).coerceAtLeast(0), number(c[2]).toInt(),
                completedChunks = checkedPieces.completedIn(first, second),
                sizeChunks = second - first)
        }
        guardIdentity()
        return files
    }

    /**
     * Change exactly one file's download priority after independently reading
     * and checking its index, path, byte length and expected old priority.
     *
     * rTorrent 0.9.x uses f.priority.set(hash:fINDEX, priority), followed
     * by d.update_priorities(hash). The second RPC is a distinct engine write;
     * if either acknowledgement is lost, do not replay either one.
     *
     * 0 = skip, 1 = normal, 2 = high. Skipped files can still contain
     * overlapping pieces; this does not erase physical payload bytes.
     */
    suspend fun setFilePriority(
        hash: String,
        fileIndex: Int,
        expectedPath: String,
        expectedSizeBytes: Long,
        expectedPriority: Int,
        desiredPriority: Int
    ): FilePriorityOutcome {
        require(Regex("[0-9a-fA-F]{40}").matches(hash)) { "Invalid v1 torrent hash" }
        require(fileIndex in 0..11999) { "Invalid file index" }
        require(expectedPath.isNotBlank() && expectedPath.length <= 4096 &&
            expectedPath.none { it.code < 32 }) { "Invalid expected file path" }
        require(expectedSizeBytes >= 0) { "Invalid file byte size" }
        require(expectedPriority in 0..2 && desiredPriority in 0..2) {
            "File priorities must be 0, 1, or 2"
        }

        val before = listFiles(hash)
        require(fileIndex < before.size) { "File index no longer exists; refresh" }
        val original = before[fileIndex]
        require(original.path == expectedPath &&
            original.sizeBytes == expectedSizeBytes &&
            original.priority == expectedPriority) {
            "File identity, size or priority changed; refresh before editing"
        }
        if (expectedPriority == desiredPriority) {
            return FilePriorityOutcome.Unchanged(hash, fileIndex, desiredPriority)
        }
        try {
            call("f.priority.set", listOf(hash + ":f" + fileIndex, desiredPriority))
            call("d.update_priorities", listOf(hash))
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Either priority write may have applied before a malformed
            // acknowledgement. Do not repeat either command automatically.
            return FilePriorityOutcome.Uncertain(hash, fileIndex)
        }

        return try {
            val after = listFiles(hash)
            val observed = after.getOrNull(fileIndex)
            if (observed != null &&
                observed.path == expectedPath &&
                observed.sizeBytes == expectedSizeBytes &&
                observed.priority == desiredPriority) {
                FilePriorityOutcome.Verified(hash, fileIndex, desiredPriority)
            } else {
                FilePriorityOutcome.PendingVerification(hash, fileIndex)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: RpcFault) {
            FilePriorityOutcome.PendingVerification(hash, fileIndex)
        } catch (_: Exception) {
            // A malformed post-write file snapshot cannot establish success.
            FilePriorityOutcome.Uncertain(hash, fileIndex)
        }
    }

    /**
     * Explicit hash-targeted lifecycle mutation. No retries: on transport loss
     * a command may already have applied, so the caller must refresh state.
     * Does not remove torrents, erase data or change any filesystem path.
     */
    suspend fun setStarted(hash: String, desiredStarted: Boolean): LifecycleOutcome {
        require(Regex("[0-9a-fA-F]{40}").matches(hash)) { "Invalid v1 torrent hash" }
        fun lifecycleFlag(value: Any?): Boolean {
            // XML-RPC int/i4/i8 all decode to Long. Engine flags must be
            // exactly 0 or 1: Number.toLong() would turn NaN/0.5 into stopped
            // and 1.9 into started, fabricating a verified postcondition.
            require(value is Long && value in 0L..1L) { "Invalid rTorrent lifecycle flag" }
            return value == 1L
        }
        try {
            // Mirror ruTorrent's proven lifecycle commands. d.start alone
            // can leave state=1 yet paused/inactive; the server UI sends
            // d.open, d.start, d.resume to actually begin traffic.
            // Stop likewise closes after stopping. Every step targets
            // the exact hash; a lost ACK aborts without any retries.
            val commands = if (desiredStarted) listOf("d.open", "d.start", "d.resume")
                else listOf("d.stop", "d.close")
            for (method in commands) call(method, listOf(hash))
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // A lost, faulting or malformed reply may follow an applied write.
            // A previous step may also have applied before this one failed.
            // No exception here proves rollback or authorizes a retry.
            return LifecycleOutcome.Uncertain(desiredStarted)
        }
        return try {
            val observed = lifecycleFlag(call("d.state", listOf(hash)))
            if (observed != desiredStarted) {
                LifecycleOutcome.Pending(desiredStarted, observed)
            } else {
                // d.state by itself is not proof the engine has finished
                // starting/stopping. Acknowledged commands may settle later.
                // Unknown activity does not become a verified postcondition.
                val active = try {
                    lifecycleFlag(call("d.is_active", listOf(hash)))
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                  catch (_: RpcFault) { null }
                if (active == desiredStarted) LifecycleOutcome.Verified(desiredStarted)
                else LifecycleOutcome.Pending(desiredStarted, observed)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // After accepted writes, unavailable, malformed or nonnumeric
            // state readback cannot establish the requested postcondition.
            LifecycleOutcome.Uncertain(desiredStarted)
        }
    }

    /**
     * Read-only post-Start diagnosis. Optional calls are best-effort so older
     * restricted RPC gateways remain usable; do not emit server fault text.
     * This does not imply progress from rTorrent's started bit.
     */
    suspend fun readActivity(hash: String): RemoteTransferActivity {
        require(Regex("[0-9a-fA-F]{40}").matches(hash)) { "Invalid v1 torrent hash" }
        suspend fun get(method: String): Long? = try {
            number(call(method, listOf(hash))).takeIf { it >= 0L }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        return RemoteTransferActivity(
            active = get("d.is_active")?.let { it != 0L },
            open = get("d.is_open")?.let { it != 0L },
            connectedPeers = get("d.peers_connected"),
            downloadBytesPerSecond = get("d.down.rate")
        )
    }

    /**
     * Read-only exact-target traffic sample. A d.hash guard before AND after
     * optional getters prevents attributing a vanished/replaced torrent's
     * counters to this selection. Faulting optional getters become unknown,
     * never zero. A disconnected client receives a failure, not fake samples.
     */
    suspend fun readTrafficSample(hash: String): RemoteTrafficSample {
        require(Regex("[0-9a-fA-F]{40}").matches(hash)) { "Invalid v1 torrent hash" }
        suspend fun guardIdentity() {
            val returned = call("d.hash", listOf(hash)) as? String
            check(returned?.equals(hash, ignoreCase = true) == true) {
                "rTorrent transfer identity not confirmed"
            }
        }
        suspend fun optionalNumber(method: String): Long? = try {
            number(call(method, listOf(hash))).takeIf { it >= 0L }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        guardIdentity()
        val received = optionalNumber("d.down.total")
        val verified = optionalNumber("d.completed_bytes")
        val size = optionalNumber("d.size_bytes")
        val active = optionalNumber("d.is_active")?.let { it != 0L }
        val peers = optionalNumber("d.peers_connected")
        val rate = optionalNumber("d.down.rate")
        guardIdentity()
        return RemoteTrafficSample(
            hash = hash, monotonicNanos = System.nanoTime(),
            receivedBytes = received, verifiedBytes = verified,
            expectedBytes = size, active = active, peers = peers,
            rateBytesPerSecond = rate
        )
    }

    /** Tracker addresses of a public transfer, or [Private] (never read). */
    sealed class PublicTrackers {
        object Private : PublicTrackers()
        data class Urls(val urls: List<String>) : PublicTrackers()
    }

    /**
     * Read-only: enabled tracker URLs for an explicit user Copy of one
     * **non-private** transfer. Private transfers return [PublicTrackers.Private]
     * before any t.url is read, so passkeys are never fetched. Identity is
     * guarded before and after; the caller still filters credential-like URLs.
     */
    suspend fun readPublicTrackers(hash: String): PublicTrackers {
        require(Regex("[0-9a-fA-F]{40}").matches(hash)) { "Invalid v1 torrent hash" }
        suspend fun guardIdentity() {
            val returned = call("d.hash", listOf(hash)) as? String
            check(returned?.equals(hash, ignoreCase = true) == true) {
                "rTorrent transfer identity not confirmed"
            }
        }
        guardIdentity()
        if (number(call("d.is_private", listOf(hash))) != 0L) return PublicTrackers.Private
        val count = number(call("d.tracker_size", listOf(hash)))
        require(count in 0L..64L) { "Unbounded or invalid tracker inventory" }
        if (count == 0L) return PublicTrackers.Urls(emptyList())
        val rows = extractRows(call("t.multicall", listOf(hash, "", "t.url=", "t.is_enabled=")))
        require(rows.size.toLong() == count) { "Tracker inventory changed during read" }
        val urls = rows.mapNotNull { raw ->
            val row = raw as? List<*> ?: error("Malformed tracker row")
            require(row.size == 2) { "Unsupported tracker field layout" }
            (row[0] as? String)?.takeIf { number(row[1]) == 1L }
        }
        // Privacy can change while tracker URLs are being read. Do not return
        // any URLs until a fresh server-side privacy check still says public.
        // An RPC fault here fails closed rather than exposing a passkey.
        if (number(call("d.is_private", listOf(hash))) != 0L) {
            return PublicTrackers.Private
        }
        guardIdentity()
        return PublicTrackers.Urls(urls)
    }

    /**
     * Bounded privacy-safe tracker health. NEVER query t.url, t.id, t.message
     * or any tracker passkey here (only [readPublicTrackers], for an explicit
     * Copy of a non-private transfer, reads t.url). Every command here is a read-only getter scoped
     * to one freshly guarded infohash. Older servers may omit recent-peer
     * counters; those become unknown rather than zero.
     */
    suspend fun readTrackerHealth(hash: String): RemoteTrackerHealth? {
        require(Regex("[0-9a-fA-F]{40}").matches(hash)) { "Invalid v1 torrent hash" }
        suspend fun guardIdentity() {
            val returned = call("d.hash", listOf(hash)) as? String
            check(returned?.equals(hash, ignoreCase = true) == true) {
                "rTorrent transfer identity not confirmed"
            }
        }
        guardIdentity()
        val count = number(call("d.tracker_size", listOf(hash)))
        require(count in 0L..256L) { "Unbounded or invalid tracker inventory" }
        if (count == 0L) {
            guardIdentity()
            return RemoteTrackerHealth(0, 0, 0, 0, 0)
        }
        val core = listOf("t.is_enabled=", "t.success_counter=", "t.failed_counter=")
        val withPeers = core + "t.latest_sum_peers="
        var peerDataSupported = true
        val rows = try {
            extractRows(call("t.multicall", listOf(hash, "") + withPeers))
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: RpcFault) {
            peerDataSupported = false
            extractRows(call("t.multicall", listOf(hash, "") + core))
        }
        require(rows.size.toLong() == count) { "Tracker inventory changed during read" }
        var enabled = 0
        var successes = 0L
        var failures = 0L
        var maxPeers = 0L
        for (raw in rows) {
            val row = raw as? List<*> ?: error("Malformed tracker row")
            require(row.size == (if (peerDataSupported) 4 else 3)) {
                "Unsupported tracker field layout"
            }
            val isEnabled = number(row[0])
            require(isEnabled == 0L || isEnabled == 1L) { "Invalid tracker enabled state" }
            if (isEnabled == 1L) enabled++
            val ok = number(row[1])
            val failed = number(row[2])
            require(ok >= 0L && failed >= 0L) { "Invalid tracker history counter" }
            successes = Math.addExact(successes, ok)
            failures = Math.addExact(failures, failed)
            if (peerDataSupported) {
                val peers = number(row[3])
                require(peers >= 0L) { "Invalid announced-peer count" }
                maxPeers = maxOf(maxPeers, peers)
            }
        }
        guardIdentity()
        return RemoteTrackerHealth(
            total = count.toInt(), enabled = enabled,
            successfulAnnounces = successes, failedAnnounces = failures,
            maxAnnouncedPeers = if (peerDataSupported) maxPeers else null
        )
    }

    private fun extractRows(response: Any?): List<*> =
        response as? List<*> ?: error("Unexpected rTorrent array reply")

    private fun number(value: Any?): Long =
        (value as? Number)?.toLong() ?: error("Expected numeric rTorrent value")

    internal suspend fun call(method: String, params: List<Any> = emptyList()): Any? =
        withContext(Dispatchers.IO) {
            // A challenge-based credential exchange is scoped to the *same*
            // HTTPS/loopback endpoint. Never enable redirects or set a
            // process-global Java Authenticator that could leak credentials.
            val request = XmlRpcCodec.request(method, params).toByteArray(Charsets.UTF_8)
            val requestTarget = (endpoint.rawPath?.ifEmpty { "/" } ?: "/") +
                (endpoint.rawQuery?.let { "?" + it } ?: "")
            var authorization: String? = null
            var attemptedChallenge = false
            while (true) {
                val connection = endpoint.toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                connection.setRequestProperty("Accept", "text/xml")
                if (authorization != null) connection.setRequestProperty("Authorization", authorization)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(request.size)
                try {
                    connection.outputStream.use { it.write(request) }
                    val status = connection.responseCode
                    if (status == HttpURLConnection.HTTP_UNAUTHORIZED &&
                        !attemptedChallenge && auth.username.isNotEmpty()) {
                        val challenges = connection.headerFields.entries
                            .filter { (name, _) -> name?.equals("WWW-Authenticate", true) == true }
                            .flatMap { it.value }
                        authorization = HttpRpcAuthorization.fromChallenge(
                            challenges, auth, "POST", requestTarget)
                        attemptedChallenge = true
                        continue
                    }
                    if (status !in 200..299) {
                        throw IOException("rTorrent gateway returned HTTP $status")
                    }
                    val length = connection.contentLengthLong
                    if (length > 4_194_304L) throw RpcReplyTooLarge("RPC response exceeded 4 MiB")
                    val xml = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val chars = CharArray(4096)
                        val sb = StringBuilder()
                        while (true) {
                            val read = reader.read(chars)
                            if (read < 0) break
                            sb.append(chars, 0, read)
                            if (sb.length > 4_194_304) throw RpcReplyTooLarge("RPC response exceeded 4 MiB")
                        }
                        sb.toString()
                    }
                    return@withContext try { XmlRpcCodec.response(xml) }
                        catch (tooWide: XmlRpcSizeLimitExceeded) { throw RpcReplyTooLarge(tooWide.message.orEmpty()) }
                } finally {
                    connection.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("RPC challenge negotiation exhausted")
        }

    private companion object {
        const val MAX_TRANSFERS = 12_000
        const val PAGE_SIZE = 250
        val MINIMUM_FIELDS = listOf("d.hash=", "d.name=", "d.size_bytes=", "d.completed_bytes=",
            "d.state=", "d.directory=")
        val EXTENDED_FIELDS = MINIMUM_FIELDS + listOf("d.down.rate=", "d.up.rate=", "d.ratio=")
        val identityPattern = Regex("[0-9a-fA-F]{40}([0-9a-fA-F]{24})?")
    }
}

/** A well-formed reply was too large for one bounded read; a paged read may work. */
class RpcReplyTooLarge(message: String) : IOException(message)
