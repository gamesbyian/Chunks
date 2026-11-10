package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Credentials are passed verbatim to HTTP authentication, but must never be
 * printable through Kotlin's generated data-class toString. Endpoint paths
 * and usernames can also identify private hosted accounts.
 */
data class RemoteCredentials(val endpoint: String, val username: String, val password: String) {
    override fun toString(): String = "RemoteCredentials(<redacted>)"
}

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
    val ratioThousandths: Long? = null,
    /** rTorrent d.is_meta, never inferred from a small positive size. */
    val metadataPending: Boolean? = null
) {
    val fractionComplete: Float
        get() = if (metadataPending != true && sizeBytes > 0) (completedBytes.toDouble() / sizeBytes).coerceIn(0.0, 1.0).toFloat() else 0f
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
 * A strictly corroborated exact-session contradiction, never inferred from
 * the main-view catalog, a generic RPC failure or a transient metadata bit.
 * This observation is read-only and does not authorize any new Add.
 */
internal enum class RemoteAddContradiction { ABSENT, WRONG_PLACE }

/** Read-only post-Remove evidence; only PresentMatching permits a NEW human-reviewed attempt. */
sealed class SessionEraseRecheck {
    data object Absent : SessionEraseRecheck()
    data object PresentMatching : SessionEraseRecheck()
    data object Changed : SessionEraseRecheck()
    data object Unavailable : SessionEraseRecheck()
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
/** Evidence about HTTP authentication, never proof of human/account ownership. */
internal enum class RemoteReadAuthEvidence {
    NO_READ_CHALLENGE, READ_CHALLENGE_ACCEPTED
}

class RTorrentRemote(private val auth: RemoteCredentials) {
    @Volatile private var acceptedReadChallenge = false

    /**
     * A public HTTP 200 version/inventory does not authenticate the supplied
     * username. A challenged-and-accepted read is stronger transport evidence,
     * but a reverse proxy may still authenticate a different principal.
     */
    /** Exact credential scope that created this transport, including numbered route. */
    internal fun addAccountScope(): String =
        io.github.gamesbyian.chunks.RemoteVerifiedUiIdentity.aliasScopeOf(auth)

    internal fun readAuthenticationEvidence(): RemoteReadAuthEvidence =
        if (acceptedReadChallenge) RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED
        else RemoteReadAuthEvidence.NO_READ_CHALLENGE
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
        val encodedSeparatorOrControl = Regex("(?i)%(?:25)*(?:2f|5c|0[0-9a-f]|1[0-9a-f]|7f)")
        val dotTraversalSegment = Regex("(?i)(?:^|/)(?:\\.|%(?:25)*2e){1,2}(?:/|$)")
        require(!encodedSeparatorOrControl.containsMatchIn(rawPath) &&
            !dotTraversalSegment.containsMatchIn(rawPath)) {
            "Ambiguous or unsafe rTorrent RPC path; use an exact unencoded server mount"
        }
    }

    /** Fresh Connect and saved-profile reauthentication must accept the same
     * bounded server identity evidence, before displaying a live inventory. */
    suspend fun version(): String {
        val reported = call("system.client_version") as? String
            ?: error("Unexpected rTorrent version reply")
        check(reported.isNotBlank() && reported.length <= 120 &&
            reported.none { it.isISOControl() }) {
            "Invalid rTorrent version response"
        }
        return reported
    }

    suspend fun probe(): RemoteProbe {
        val serverVersion = version()
        val methods = try {
            (call("system.listMethods") as? List<*>)?.map { item ->
                item as? String ?: error("Non-string RPC method name")
            }?.toSet()
        } catch (fault: RpcFault) {
            // Only actual missing introspection means capabilities unknown.
            // A busy/forbidden backend response is a connection error and
            // must not be presented as an unsupported optional method.
            if (!fault.isUnsupportedOptionalGetter()) throw fault
            null
        }
        return RemoteProbe(serverVersion, methods)
    }

    /**
     * The server's configured default download directory (read-only). Used only
     * to *suggest* a Place; Adds still require an explicit user-confirmed path.
     */
    suspend fun defaultDirectory(): String? {
        val value = try {
            call("directory.default")
        } catch (fault: RpcFault) {
            // No usable default is a valid old-server capability, but a
            // permission or busy error cannot silently become that state.
            if (!fault.isUnsupportedOptionalGetter()) throw fault
            return null
        }
        // Unix directory names may end in spaces. Trimming the RPC value
        // silently changes the exact Place, making a valid server-default
        // Add appear to target a different directory at readback. Preserve
        // the server's literal path; refuse malformed leading whitespace or
        // control-tainted replies rather than inventing a "clean" location.
        val path = (value as? String)?.takeIf {
            it.startsWith("/") && it.length <= 4096 &&
                it.none { ch -> ch.code < 32 || ch.code == 127 }
        } ?: return null
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
        suspend fun guardIdentity() {
            check((call("d.hash", listOf(hash)) as? String)?.equals(hash, ignoreCase = true) == true) {
                "rTorrent peer-source identity not confirmed; refresh"
            }
        }
        // Global listen settings and exact-torrent flags are read at different
        // instants. Verify the selected hash on both sides of that interval.
        guardIdentity()
        val announced = try {
            (call("network.local_address") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 253 }
        } catch (fault: RpcFault) {
            if (!fault.isUnsupportedOptionalGetter()) throw fault
            null
        }
        // Seedboxes often bind rTorrent to an address other than the web
        // host's; a wildcard bind says nothing about which one.
        val bound = try {
            (call("network.bind_address") as? String)?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 253 && it !in setOf("0.0.0.0", "::", "[::]") }
        } catch (fault: RpcFault) {
            if (!fault.isUnsupportedOptionalGetter()) throw fault
            null
        }
        val port = number(call("network.listen.port"))
        // A remote peer-copy destination must have a real TCP listen port.
        // Integer coercion, negative values and >65535 must never be silently
        // published as a usable BitTorrent address.
        require(port in 1L..65535L) { "Invalid rTorrent peer listening port" }
        fun exactFlag(method: String, value: Long): Boolean {
            require(value in 0L..1L) { "Invalid rTorrent $method flag" }
            return value == 1L
        }
        val isPrivate = exactFlag("privacy", number(call("d.is_private", listOf(hash))))
        val isActive = exactFlag("activity", number(call("d.is_active", listOf(hash))))
        guardIdentity()
        return RemotePeerSource(
            rpcHost = endpoint.host.removePrefix("[").removeSuffix("]"),
            announcedAddress = announced,
            boundAddress = bound,
            listenPort = port,
            isPrivate = isPrivate,
            isActive = isActive
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
            } catch (fault: RpcFault) {
                // A busy/forbidden RPC fault is not evidence that optional
                // rate fields are missing. Do not launch another full-list
                // request unless the server actually lacks a getter.
                if (!fault.isUnsupportedOptionalGetter()) throw fault
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

    /** Read metadata state in the same rTorrent row as size/progress. A
     * second request could combine a replaced placeholder with payload flags. */
    internal suspend fun listTransfersWithMetadata(view: String = "main"): List<RemoteTransfer> {
        require(Regex("[A-Za-z][A-Za-z0-9_-]{0,39}").matches(view)) { "Invalid view" }
        return try {
            try {
                val rows = extractRows(call("d.multicall2", listOf("", view) + EXTENDED_FIELDS + "d.is_meta="))
                require(rows.size <= MAX_TRANSFERS)
                requireUniqueIdentities(rows.map { raw ->
                    transferWithMetadata(raw as? List<*> ?: error("Invalid metadata row"))
                })
            } catch (_: RpcReplyTooLarge) {
                listTransfersPaged(view, includeMetadata = true)
            }
        } catch (fault: RpcFault) {
            if (!fault.isUnsupportedOptionalGetter()) throw fault
            listTransfers(view)
        }
    }

    private fun transferWithMetadata(row: List<*>): RemoteTransfer {
        require(row.size == EXTENDED_FIELDS.size + 1) { "Invalid metadata field layout" }
        val flag = number(row.last())
        require(flag in 0L..1L) { "Invalid metadata state flag" }
        return transferFromRow(row.dropLast(1), true).copy(metadataPending = flag == 1L)
    }

    /**
     * Large-queue path: one bounded `download_list` for identities, then
     * `system.multicall` pages of exact-hash getters. Any per-call fault (for
     * example a torrent erased mid-read) fails the whole snapshot closed rather
     * than publishing a partial or mixed list. Read-only; never retried here.
     */
    internal suspend fun listTransfersPaged(view: String, includeMetadata: Boolean = false): List<RemoteTransfer> {
        require(Regex("[A-Za-z][A-Za-z0-9_-]{0,39}").matches(view)) { "Invalid view" }
        val minimum = MINIMUM_FIELDS
        val extended = if (includeMetadata) EXTENDED_FIELDS + "d.is_meta=" else EXTENDED_FIELDS
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
        } catch (fault: RpcFault) {
            // Large-list paging must not turn a transient operational error
            // into thousands of additional credentialed field reads.
            if (includeMetadata || !fault.isUnsupportedOptionalGetter()) throw fault
            false
        }
        val fields = getters(if (rich) extended else minimum)
        val transfers = ArrayList<RemoteTransfer>(hashes.size)
        for (page in hashes.chunked(PAGE_SIZE)) {
            val rows = pagedRowsWithinLimit(page, fields)
            for ((index, row) in rows.withIndex()) {
                val transfer = if (includeMetadata) transferWithMetadata(row) else transferFromRow(row, rich)
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

    /**
     * Only a *size-limited* read-only page may be retried as two smaller pages.
     * Server faults, timeouts, bad XML, identity mismatches and cancellations
     * still fail the entire snapshot. A single oversized row is irreducible.
     *
     * All pages retain their original order. The caller validates each
     * requested hash and re-reads view membership before publishing anything.
     */
    private suspend fun pagedRowsWithinLimit(
        hashes: List<String>, getters: List<String>
    ): List<List<Any?>> = try {
        pagedRows(hashes, getters)
    } catch (tooLarge: RpcReplyTooLarge) {
        if (hashes.size <= 1) throw tooLarge
        val middle = hashes.size / 2
        pagedRowsWithinLimit(hashes.subList(0, middle), getters) +
            pagedRowsWithinLimit(hashes.subList(middle, hashes.size), getters)
    }

    private fun transferFromRow(row: List<*>, rich: Boolean): RemoteTransfer {
        val hash = row[0] as? String ?: error("Missing info hash")
        require(identityPattern.matches(hash)) { "Invalid torrent infohash in server view" }
        val size = nonNegativeNumber(row[2], "torrent size")
        val completed = nonNegativeNumber(row[3], "verified bytes")
        require(completed <= size) { "Verified bytes exceed torrent size" }
        val state = number(row[4])
        require(state in 0L..1L) { "Invalid rTorrent state flag" }
        return RemoteTransfer(
            hash = hash,
            name = (row[1] as? String ?: error("Missing torrent name")).take(2048),
            sizeBytes = size,
            completedBytes = completed,
            started = state == 1L,
            directory = (row[5] as? String ?: error("Missing server path")).take(4096),
            downBytesPerSecond = if (rich) nonNegativeNumber(row[6], "download rate") else null,
            upBytesPerSecond = if (rich) nonNegativeNumber(row[7], "upload rate") else null,
            ratioThousandths = if (rich) nonNegativeNumber(row[8], "ratio") else null
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
     * Add-and-Start uses rTorrent's native start-on-load commands, the same
     * lifecycle entry point that ruTorrent uses for its ordinary Start option.
     * Stopped-only and priority-before-start adds retain the existing two-step
     * flow. The single non-idempotent load is preflighted and NEVER replayed.
     *
     * An ACK is insufficient: only this exact hash, expected parent directory,
     * and both started AND active flags can produce Started. This is engine
     * activity, not evidence of peers or downloaded bytes.
     */
    internal suspend fun loadStarted(plan: StoppedRemoteAddPlan): RemoteAddAndStart {
        val hash = plan.expectedInfoHash
        if (listTransfers("main").any { it.hash.equals(hash, ignoreCase = true) } ||
            lookupName(hash) != null) {
            return RemoteAddAndStart.AddedStopped(RemoteAddOutcome.AlreadyPresent(hash))
        }
        requireCurrentServerDefault(plan)
        try {
            val params: List<Any> = if (plan.useServerDefault) listOf("", plan.source)
                else listOf("", plan.source, "d.directory.set=" + plan.destination)
            call("load.start", params)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // A failed acknowledgement can follow a successful load. No retry.
            return RemoteAddAndStart.StartNotVerified(hash, null)
        }
        return awaitNativeStartedReadback(plan)
    }

    internal suspend fun loadStartedTorrentBytes(
        torrentBytes: ByteArray, destination: String, useServerDefault: Boolean = false
    ): RemoteAddAndStart {
        val hash = TorrentMetainfoReader.preview(torrentBytes).infoHashV1
        val link = "magnet:?xt=urn:btih:" + hash
        val plan = if (useServerDefault) StoppedRemoteAddPlan.fromMagnetAtServerDefault(link, destination)
            else StoppedRemoteAddPlan.fromMagnet(link, destination)
        if (listTransfers("main").any { it.hash.equals(hash, ignoreCase = true) } ||
            lookupName(hash) != null) {
            return RemoteAddAndStart.AddedStopped(RemoteAddOutcome.AlreadyPresent(hash))
        }
        requireCurrentServerDefault(plan)
        try {
            val params: List<Any> = if (plan.useServerDefault) listOf("", torrentBytes)
                else listOf("", torrentBytes, "d.directory.set=" + plan.destination)
            call("load.raw_start", params)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return RemoteAddAndStart.StartNotVerified(hash, null)
        }
        return awaitNativeStartedReadback(plan)
    }

    /**
     * Explicit read-only recovery for the original native Add-with-Start
     * intent after a lost ACK, timeout or pending activation. The original
     * reviewed identity and Place must agree; no mutation is attempted.
     * Never use stopped-Add reconciliation for an already active torrent.
     */
    internal suspend fun reconcileStartedAdd(plan: StoppedRemoteAddPlan): RemoteAddAndStart =
        awaitNativeStartedReadback(plan)

    /** Read only, scoped to the submitted hash and reviewed server parent. */
    internal suspend fun readAddProgress(plan: StoppedRemoteAddPlan): RemoteAddProgress {
        val hash = plan.expectedInfoHash
        try {
            val name = lookupName(hash) ?: return RemoteAddProgress(registered = false)
            if (readVerifiedAddDirectory(hash, plan.destination, name) == null) {
                if (lookupName(hash) != name) return RemoteAddProgress(registered = true)
                error("The registered torrent is outside the reviewed destination")
            }
            suspend fun metadataFlag(): Boolean? = try {
                val flag = number(call("d.is_meta", listOf(hash)))
                require(flag in 0L..1L) { "Invalid metadata flag" }
                flag == 1L
            } catch (fault: RpcFault) {
                if (!fault.isUnsupportedOptionalGetter()) throw fault
                null
            }
            val pending = metadataFlag()
            val traffic = readTrafficSample(hash)
            val state = number(call("d.state", listOf(hash)))
            require(state in 0L..1L) { "Invalid started flag" }
            // Metadata promotion replaces a placeholder with a new full job.
            // Never combine its tiny metadata size with payload counters.
            if (pending != metadataFlag()) return RemoteAddProgress(registered = true)
            check((call("d.hash", listOf(hash)) as? String)?.equals(hash, true) == true)
            return RemoteAddProgress(true, pending, state == 1L, traffic)
        } catch (fault: RpcFault) {
            if (!fault.isUnknownInfoHash()) throw fault
            // Upstream process_meta_download temporarily erases the exact
            // placeholder while recreating the full download. No write retry.
            return RemoteAddProgress(registered = false)
        }
    }

    /**
     * Confirm only the exact registered hash and reviewed parent Place.
     * Used after a lost Start reply when a peerless magnet may be registered
     * but not yet have metadata or active payload. This is deliberately less
     * than verification of requested Start and cannot attribute who added it.
     *
     * Do not use readAddProgress.registered here: metadata promotion can
     * momentarily expose a name without a stable/verified directory. The hash
     * must agree both before and after the existing strict folder readback.
     * A mismatched folder, vanished hash or fault cannot unlock a new Add.
     */
    internal suspend fun readRegisteredAddAtReviewedPlace(plan: StoppedRemoteAddPlan): Boolean {
        val hash = plan.expectedInfoHash
        val name = lookupName(hash) ?: return false
        suspend fun guardHash() {
            check((call("d.hash", listOf(hash)) as? String)?.equals(hash, ignoreCase = true) == true) {
                "rTorrent session identity changed during Add recovery"
            }
        }
        guardHash()
        val reviewedPlaceVerified = readVerifiedAddDirectory(hash, plan.destination, name) != null
        guardHash()
        return reviewedPlaceVerified
    }

    /**
     * Extra read-only proof for revoking an *unacknowledged* exact Add-session
     * acknowledgment on a newer, original-account readback. An empty main
     * view, failed RPC, bad flag, unknown metadata or one disappeared placeholder
     * are inconclusive. Repeated exact-hash reads are needed because rTorrent
     * can replace a magnet's metadata-only job with its final session entry.
     *
     * Null means no authoritative contrary witness. All checks issue d.* getters
     * only. Neither result grants authorization to replay the original write.
     */
    internal suspend fun readExactAddContradiction(
        plan: StoppedRemoteAddPlan
    ): RemoteAddContradiction? {
        val hash = plan.expectedInfoHash
        data class DifferentPlace(val name: String, val directory: String, val multi: Long)

        suspend fun stableDifferentPlace(): DifferentPlace? {
            val name = lookupName(hash) ?: return null
            // A malformed/partial metainfo name cannot establish a changed Place.
            if (name.isBlank() || name.length > 255 || name == "." || name == ".." ||
                name.any { it == '/' || it == '\\' || it.code < 32 }) return null
            suspend fun exactHash(): Boolean =
                (call("d.hash", listOf(hash)) as? String)?.equals(hash, true) == true
            if (!exactHash()) return null
            // Metadata promotion can temporarily replace the directory/name
            // and leave the exact hash unavailable. Never revoke in that phase.
            if (number(call("d.is_meta", listOf(hash))) != 0L) return null
            val directory = call("d.directory", listOf(hash)) as? String ?: return null
            val multi = number(call("d.is_multi_file", listOf(hash)))
            if (multi !in 0L..1L) return null
            if (!exactHash() || lookupName(hash) != name) return null
            val expected = if (multi == 1L) plan.destination + "/" + name
                else plan.destination
            if (directory == expected || directory.length !in 2..4096 ||
                !directory.startsWith("/") || directory.any { it.code < 32 || it.code == 127 } ||
                directory.split('/').drop(1).any { it.isBlank() || it == "." || it == ".." })
                return null
            return DifferentPlace(name, directory, multi)
        }

        return try {
            val initialName = lookupName(hash)
            if (initialName == null) {
                // d.name alone can momentarily disappear during magnet
                // promotion. Independently check d.hash and repeat both.
                if (sessionContainsExactHash(hash)) return null
                kotlinx.coroutines.delay(350L)
                if (lookupName(hash) != null || sessionContainsExactHash(hash)) null
                else RemoteAddContradiction.ABSENT
            } else {
                val first = stableDifferentPlace() ?: return null
                kotlinx.coroutines.delay(350L)
                val second = stableDifferentPlace()
                if (first == second) RemoteAddContradiction.WRONG_PLACE else null
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // 401/403, unknown capability, malformed reply and transient
            // session churn cannot revoke a previously legitimate positive.
            null
        }
    }

    /**
     * Bounded READ-ONLY post-load polling, never a second load or lifecycle
     * command. In-flight hashing or a server policy can delay activation.
     * Unknown readback must remain non-verified, with explicit user refresh.
     */
    private suspend fun awaitNativeStartedReadback(
        plan: StoppedRemoteAddPlan
    ): RemoteAddAndStart {
        val hash = plan.expectedInfoHash
        var observedState: Boolean? = null
        for (attempt in 0 until 8) {
            try {
                val name = lookupName(hash)
                if (name != null) {
                    suspend fun exactHash(): Boolean =
                        (call("d.hash", listOf(hash)) as? String)?.equals(hash, ignoreCase = true) == true
                    if (!exactHash()) return RemoteAddAndStart.StartNotVerified(hash, null)
                    val destination = readVerifiedAddDirectory(hash, plan.destination, name)
                    val state = number(call("d.state", listOf(hash)))
                    val active = number(call("d.is_active", listOf(hash)))
                    if (!exactHash() || state !in 0L..1L || active !in 0L..1L)
                        return RemoteAddAndStart.StartNotVerified(hash, null)
                    observedState = state == 1L
                    if (destination == null) return RemoteAddAndStart.StartNotVerified(hash, null)
                    if (state == 1L && active == 1L) return RemoteAddAndStart.Started(hash)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                return RemoteAddAndStart.StartNotVerified(hash, null)
            }
            if (attempt < 7) kotlinx.coroutines.delay(400L)
        }
        return RemoteAddAndStart.StartNotVerified(hash,
            observedState?.let { LifecycleOutcome.Pending(true, it) })
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
            // The upload form is non-idempotent. Discover authorization at
            // its own URL without posting the form, and never replay it.
            val authorization = preauthorizeWrite(upload, requestTarget)
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
            if (matches.isEmpty()) {
                // A host may not publish newly loaded jobs in the main view.
                // An exact session lookup can still positively verify the
                // stopped destination without a second non-idempotent Add.
                reconcileStoppedAddOutsideMain(plan)
            } else if (matches.size != 1) {
                RemoteAddOutcome.PendingVerification(plan.expectedInfoHash)
            } else {
                val item = matches.single()
                val directory = readVerifiedAddDirectory(
                    plan.expectedInfoHash, plan.destination, item.name)
                // d.state=0 can coexist transiently with an active engine.
                // The hidden-view readback already requires inactivity; the
                // visible main-view path must uphold the same stopped promise
                // before Add-and-Start can dispatch a second mutation.
                val state = number(call("d.state", listOf(plan.expectedInfoHash)))
                val active = number(call("d.is_active", listOf(plan.expectedInfoHash)))
                if (!item.started && state == 0L && active == 0L && directory != null) {
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
     * No main-view row does not mean the load failed: a session can hold jobs
     * outside that view. Verify the exact infohash independently with bounded
     * read-only getters, both before and after checking its destination and
     * stopped/inactive state. Never issue load/start or infer absence from
     * view membership. All exceptions remain pending/uncertain in the caller.
     */
    private suspend fun reconcileStoppedAddOutsideMain(
        plan: StoppedRemoteAddPlan
    ): RemoteAddOutcome {
        val hash = plan.expectedInfoHash
        val name = lookupName(hash) ?: return RemoteAddOutcome.PendingVerification(hash)
        suspend fun guardHash() {
            check((call("d.hash", listOf(hash)) as? String)?.equals(hash, ignoreCase = true) == true) {
                "rTorrent returned another infohash during exact Add readback"
            }
        }
        guardHash()
        val directory = readVerifiedAddDirectory(hash, plan.destination, name)
        val state = number(call("d.state", listOf(hash)))
        val active = number(call("d.is_active", listOf(hash)))
        guardHash()
        return if (directory != null && state == 0L && active == 0L)
            RemoteAddOutcome.VerifiedStopped(hash, directory)
        else RemoteAddOutcome.PendingVerification(hash)
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
        // rTorrent defines this as a boolean. Treat malformed/unknown
        // gateway values as inconclusive, never as proof of a multi-file
        // root that can release an attempted Universal Add ticket.
        val multiFlag = number(call("d.is_multi_file", listOf(hash)))
        require(multiFlag == 0L || multiFlag == 1L) { "Invalid rTorrent multi-file flag" }
        val expected = if (multiFlag == 1L) {
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
            if (confirmedAbsentExactHash(hash)) return SessionEraseOutcome.AlreadyAbsent(hash)
            error("Torrent still exists outside the main view; refresh before removal")
        }
        require(matching.size == 1) { "Ambiguous torrent identity" }
        require(matching.single().name == expectedName &&
            matching.single().directory == expectedDirectory) {
            "Torrent name or location changed; refresh before removal"
        }
        // The main-view snapshot is not a lock. A second client can rename or
        // relocate the selected job while this confirmation is in progress.
        // Re-read exact-session identity and location immediately before erase,
        // refusing stale consent instead of relying on the unchanged hash alone.
        // These reads narrow the race; rTorrent exposes no atomic compare/erase.
        check(sessionContainsExactHash(hash)) { "Torrent disappeared before removal; refresh" }
        val latestName = lookupName(hash)
        val latestDirectory = call("d.directory", listOf(hash)) as? String
        check(latestName == expectedName && latestDirectory == expectedDirectory) {
            "Torrent name or location changed; refresh before removal"
        }
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
            if (confirmedAbsentExactHash(hash)) SessionEraseOutcome.VerifiedRemoved(hash)
            else SessionEraseOutcome.PendingRemoval(hash)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Invalid or unavailable readback never proves absence.
            SessionEraseOutcome.Uncertain(hash)
        }
    }


    /** Endpoint shape permits an erasedata request; plugin presence is verified by its POST receipt. */
    fun supportsRuTorrentDeleteData(): Boolean = RuTorrentDeleteData.endpoint(endpoint) != null

    /**
     * Remove one exact reviewed rTorrent transfer AND ask ruTorrent erasedata
     * for non-force asynchronous payload cleanup. Only the matching mounted
     * ruTorrent instance qualifies. An HTTP acknowledgement is not file proof.
     * Never repeat the mutation after a timeout or unexpected response.
     */
    suspend fun removeWithDataViaRuTorrent(
        hash: String, expectedName: String, expectedDirectory: String
    ): RemoteDeleteDataOutcome {
        require(Regex("[0-9a-fA-F]{40}").matches(hash))
        val plugin = RuTorrentDeleteData.endpoint(endpoint)
            ?: return RemoteDeleteDataOutcome.Unsupported
        if (expectedName.isBlank() ||
            !RuTorrentDeleteData.safeDirectory(expectedDirectory))
            return RemoteDeleteDataOutcome.Refused

        // The main view may exclude a stopped or custom-view torrent. A
        // destructive payload removal must not rely on that partial catalog.
        // rTorrent's unfiltered "default" view is the broad session inventory.
        // If unavailable, inconsistent with main, or changing, refuse.
        suspend fun fullSession(): List<RemoteTransfer>? = try {
            listTransfers("default")
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) { null }
        fun identityRows(rows: List<RemoteTransfer>) = rows.map {
            Triple(it.hash.uppercase(java.util.Locale.ROOT), it.name, it.directory)
        }.toSet()
        fun coversMain(main: List<RemoteTransfer>, all: List<RemoteTransfer>): Boolean {
            val byHash = all.associateBy { it.hash.uppercase(java.util.Locale.ROOT) }
            val selected = byHash[hash.uppercase(java.util.Locale.ROOT)]
            if (selected == null || selected.name != expectedName ||
                selected.directory != expectedDirectory)
                return false
            return main.all { row ->
                val sessionRow = byHash[row.hash.uppercase(java.util.Locale.ROOT)]
                sessionRow != null && sessionRow.name == row.name &&
                    sessionRow.directory == row.directory
            }
        }
        val inventory = try { listTransfers("main") }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { return RemoteDeleteDataOutcome.Refused }
        val current = inventory.filter { it.hash.equals(hash, true) }
        if (current.size != 1 || current.single().name != expectedName ||
            current.single().directory != expectedDirectory)
            return RemoteDeleteDataOutcome.Refused

        val allSession = fullSession() ?: return RemoteDeleteDataOutcome.Refused
        if (!coversMain(inventory, allSession)) return RemoteDeleteDataOutcome.Refused
        val files = try { listFiles(hash) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { return RemoteDeleteDataOutcome.Refused }
        if (!RuTorrentDeleteData.safeFilePaths(files))
            return RemoteDeleteDataOutcome.Refused

        fun full(dir: String, file: String) =
            (dir.trimEnd('/') + "/" + file).lowercase(java.util.Locale.ROOT)
        val selectedPaths = files.map { full(expectedDirectory, it.path) }.toSet()
        suspend fun noSharedSessionFiles(rows: List<RemoteTransfer>): Boolean {
            for (other in rows) {
                if (other.hash.equals(hash, true)) continue
                val selectedRoot = expectedDirectory.trimEnd('/')
                val otherRoot = other.directory.trimEnd('/').ifEmpty { "/" }
                // A relative, dot-segment or otherwise ambiguous directory
                // cannot prove non-overlap, even if its text looks disjoint.
                if (!RuTorrentDeleteData.safeDirectory(other.directory, allowRoot = true))
                    return false
                fun nested(path: String, ancestor: String) =
                    ancestor == "/" || path == ancestor || path.startsWith("$ancestor/")
                if (!nested(selectedRoot, otherRoot) &&
                    !nested(otherRoot, selectedRoot)) continue
                val otherFiles = try { listFiles(other.hash) }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { return false }
                if (!RuTorrentDeleteData.safeFilePaths(otherFiles) ||
                    otherFiles.any { full(other.directory, it.path) in selectedPaths }) return false
            }
            return true
        }
        if (!noSharedSessionFiles(allSession)) return RemoteDeleteDataOutcome.Refused

        // Recheck both the exact target and session membership immediately
        // before the one permitted erasedata POST. If another client adds,
        // removes, renames or moves a job during the preflight, refuse instead
        // of deleting files that a newly registered job may share.
        val fresh = try {
            sessionContainsExactHash(hash) &&
                lookupName(hash) == expectedName &&
                (call("d.directory", listOf(hash)) as? String) == expectedDirectory &&
                sessionContainsExactHash(hash)
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
          catch (_: Exception) { false }
        if (!fresh) return RemoteDeleteDataOutcome.Refused
        val latestSession = fullSession() ?: return RemoteDeleteDataOutcome.Refused
        if (identityRows(allSession) != identityRows(latestSession) ||
            !coversMain(inventory, latestSession) || !noSharedSessionFiles(latestSession))
            return RemoteDeleteDataOutcome.Refused
        val latestFiles = try { listFiles(hash) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { return RemoteDeleteDataOutcome.Refused }
        if (latestFiles != files) return RemoteDeleteDataOutcome.Refused

        val accepted = try {
            withContext(Dispatchers.IO) {
                val target = plugin.rawPath
                // Credentials, when required, are learned through a harmless
                // HEAD challenge at this exact plugin endpoint, not a POST retry.
                val authorization = preauthorizeWrite(plugin, target)
                val bytes = "mode=removewithdata&hash=$hash&v=1".toByteArray(Charsets.US_ASCII)
                val connection = plugin.toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setRequestProperty("Accept", "application/json")
                if (authorization != null) connection.setRequestProperty("Authorization", authorization)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                try {
                    connection.outputStream.use { it.write(bytes) }
                    if (connection.responseCode !in 200..299)
                        return@withContext false
                    if (connection.contentLengthLong > 16_384L)
                        return@withContext false
                    val response = connection.inputStream.use { it.readNBytes(16_385) }
                    response.size <= 16_384 && RuTorrentDeleteData.acceptedResponse(
                        response.toString(Charsets.UTF_8))
                } finally { connection.disconnect() }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) { return RemoteDeleteDataOutcome.Uncertain(hash) }
        if (!accepted) return RemoteDeleteDataOutcome.Uncertain(hash)
        // A valid plugin response means it staged cleanup; its collector may
        // take a while and may refuse unsafe targets. No false "files deleted".
        return try {
            if (sessionContainsExactHash(hash))
                RemoteDeleteDataOutcome.Uncertain(hash)
            else RemoteDeleteDataOutcome.QueuedForCleanup(hash)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) { RemoteDeleteDataOutcome.Uncertain(hash) }
    }

    /**
     * A new user action after an unsuccessful/uncertain session removal, never
     * an automatic command replay. Check the exact session, not the main view:
     * another client may have removed, replaced, renamed or moved the job.
     *
     * Missing/invalid RPC responses remain unavailable. Even a matching read
     * is not removal authority: eraseFromSession repeats its full fresh
     * main-view, exact-name and exact-directory preflight before writing.
     */
    suspend fun recheckSessionErase(
        hash: String, expectedName: String, expectedDirectory: String
    ): SessionEraseRecheck {
        require(Regex("[0-9a-fA-F]{40}").matches(hash)) { "Invalid torrent identity" }
        require(expectedName.isNotBlank() && expectedDirectory.startsWith("/")) {
            "Invalid reviewed transfer identity"
        }
        // A magnet's metadata-only session can briefly disappear while
        // rTorrent replaces it. One unknown-hash reply is not proof that a
        // prior Remove completed. Confirm absence with a fresh exact read;
        // never use this observation to replay an uncertain mutation.
        suspend fun independentlyConfirmAbsence(): SessionEraseRecheck {
            kotlinx.coroutines.delay(350L)
            return if (sessionContainsExactHash(hash)) SessionEraseRecheck.Unavailable
                else SessionEraseRecheck.Absent
        }
        return try {
            if (!sessionContainsExactHash(hash)) return independentlyConfirmAbsence()
            val currentName = lookupName(hash)
            if (currentName == null) {
                // The job disappeared between d.hash and d.name. A different
                // client may already have re-added the same hash: observe
                // again before calling the old review absent. A fresh positive
                // identity is uncertain, never proof that the old one is gone.
                return if (sessionContainsExactHash(hash)) SessionEraseRecheck.Unavailable
                else independentlyConfirmAbsence()
            }
            val currentDirectory = call("d.directory", listOf(hash)) as? String
                ?: return SessionEraseRecheck.Unavailable
            if (!sessionContainsExactHash(hash)) return independentlyConfirmAbsence()
            if (currentName == expectedName && currentDirectory == expectedDirectory)
                SessionEraseRecheck.PresentMatching
            else SessionEraseRecheck.Changed
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            SessionEraseRecheck.Unavailable
        }
    }

    /**
     * An exact-hash miss can be transient during magnet metadata promotion.
     * Two independently acquired misses are the minimum evidence for an
     * AlreadyAbsent or VerifiedRemoved result. A reappearance is inconclusive
     * and must never trigger another d.erase.
     */
    private suspend fun confirmedAbsentExactHash(hash: String): Boolean {
        if (sessionContainsExactHash(hash)) return false
        kotlinx.coroutines.delay(350L)
        return !sessionContainsExactHash(hash)
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
            val path = columns[0] as? String ?: error("Missing file path")
            require(path.isNotBlank() && path.length <= 4096) { "Invalid remote file path" }
            val priority = number(columns[2])
            require(priority in 0L..2L) { "Invalid remote file priority" }
            RemoteFile(
                path = path,
                sizeBytes = nonNegativeNumber(columns[1], "file size"),
                priority = priority.toInt()
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
            val path = c[0] as? String ?: error("Missing file path")
            require(path.isNotBlank() && path.length <= 4096) { "Invalid remote file path" }
            val priority = number(c[2])
            require(priority in 0L..2L) { "Invalid remote file priority" }
            FileCompletion(path, nonNegativeNumber(c[1], "file size"), priority.toInt(),
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
        suspend fun requireExactSessionHash() {
            // The UI-selected hash is not proof that the connected account
            // still owns that session. A stale, missing or contradictory
            // exact-hash getter must refuse BEFORE d.open/d.stop can run.
            // A failed initial read sends no mutation and is not an
            // ambiguous write eligible for retry.
            check((call("d.hash", listOf(hash)) as? String)
                ?.equals(hash, ignoreCase = true) == true) {
                "Selected rTorrent infohash could not be verified; refresh this account"
            }
        }
        fun lifecycleFlag(value: Any?): Boolean {
            // XML-RPC int/i4/i8 all decode to Long. Engine flags must be
            // exactly 0 or 1: Number.toLong() would turn NaN/0.5 into stopped
            // and 1.9 into started, fabricating a verified postcondition.
            require(value is Long && value in 0L..1L) { "Invalid rTorrent lifecycle flag" }
            return value == 1L
        }
        // Do not allow an obsolete list selection to issue even the first
        // non-idempotent RPC against an unknown/replaced job.
        requireExactSessionHash()
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
            // d.state is not itself proof of actual peer/piece activity. The
            // active bit is a separate engine-level postcondition, and both
            // remain scoped to the exact selected infohash.
            val active = if (observed == desiredStarted) try {
                lifecycleFlag(call("d.is_active", listOf(hash)))
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
              catch (_: RpcFault) { null } else null
            // A second independent exact-hash witness brackets the
            // asynchronous observation. Failure after a write stays
            // uncertain, never success or permission to replay it.
            requireExactSessionHash()
            if (observed == desiredStarted && active == desiredStarted)
                LifecycleOutcome.Verified(desiredStarted)
            else LifecycleOutcome.Pending(desiredStarted, observed)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // After accepted writes, unavailable, malformed or nonnumeric
            // state readback cannot establish the requested postcondition.
            LifecycleOutcome.Uncertain(desiredStarted)
        }
    }

    /**
     * A method-unavailable XML-RPC fault is a bounded optional-capability
     * fallback. An XML-RPC envelope may also contain an operational error
     * despite HTTP 200. Busy, permission, unknown hash and opaque faults
     * must retain their original failure, never trigger a substitute read.
     */
    private fun RpcFault.isUnsupportedOptionalGetter(): Boolean {
        val reason = message.orEmpty().lowercase(java.util.Locale.ROOT)
        return code in setOf("-32601", "-506") && "method" in reason &&
            listOf("not found", "not defined", "unknown", "unsupported").any { it in reason }
    }

    /**
     * Read-only post-Start diagnosis. Optional calls are best-effort so older
     * restricted RPC gateways remain usable; do not emit server fault text.
     * This does not imply progress from rTorrent's started bit.
     */
    suspend fun readActivity(hash: String): RemoteTransferActivity {
        require(Regex("[0-9a-fA-F]{40}").matches(hash)) { "Invalid v1 torrent hash" }
        suspend fun get(method: String, binaryFlag: Boolean = false): Long? = try {
            val result = number(call(method, listOf(hash)))
            // Activity/open are engine Boolean flags, not truthy counters.
            // Malformed responses must not fabricate a connected/active job.
            if (binaryFlag) {
                require(result in 0L..1L) { "Invalid rTorrent $method flag" }
                result
            } else result.takeIf { it >= 0L }
        } catch (fault: RpcFault) {
            // Older servers may omit optional getters. Only a recognizable
            // "method unavailable" RPC fault can become an unknown statistic.
            // A 200 XML-RPC envelope can also carry backend busy/permission
            // faults. Those, and a vanished exact hash, must stop inspection.
            if (!fault.isUnsupportedOptionalGetter()) throw fault
            null
        }
        // HTTP 401/403/407/429, TLS/socket loss, truncated or malformed XML,
        // and cancellation MUST propagate. Swallowing them would send up to
        // four more requests against an expired login and falsely report a
        // connected server with merely unavailable optional statistics.
        return RemoteTransferActivity(
            active = get("d.is_active", binaryFlag = true)?.let { it == 1L },
            open = get("d.is_open", binaryFlag = true)?.let { it == 1L },
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
        } catch (fault: RpcFault) {
            // Optional counters can be unsupported on older rTorrent.
            // HTTP 401/503, lost transport, invalid XML and an XML-RPC
            // operational fault cannot become "unknown bytes" while the
            // client silently continues with five more credentialed reads.
            if (!fault.isUnsupportedOptionalGetter()) throw fault
            null
        }
        guardIdentity()
        val received = optionalNumber("d.down.total")
        val verified = optionalNumber("d.completed_bytes")
        val size = optionalNumber("d.size_bytes")
        val active = optionalNumber("d.is_active")?.let {
            require(it in 0L..1L) { "Invalid rTorrent activity flag" }
            it == 1L
        }
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
        } catch (fault: RpcFault) {
            // Only a recognized missing optional getter allows a read-only
            // three-field fallback. Server-busy, permission and disappeared
            // infohash faults are operational failures, not capabilities.
            if (!fault.isUnsupportedOptionalGetter()) throw fault
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
        value as? Long ?: throw IllegalArgumentException("Expected integral rTorrent value")

    private fun nonNegativeNumber(value: Any?, field: String): Long =
        number(value).also { require(it >= 0L) { "Invalid negative rTorrent $field" } }

    /**
     * Obtain Basic/Digest authorization without sending a mutation. RPC
     * uses a read-only call on its own endpoint; a different upload URL uses
     * its own HEAD challenge, never credentials learned at the RPC mount.
     *
     * Never challenge-negotiate by repeating an already-sent write.
     * This helper is called only from Dispatchers.IO.
     */
    private fun preauthorizeWrite(writeEndpoint: URI, requestTarget: String): String? {
        if (auth.username.isEmpty()) return null
        if (writeEndpoint != endpoint) return challengeWriteEndpoint(writeEndpoint, requestTarget)
        val probeBody = XmlRpcCodec.request("system.client_version").toByteArray(Charsets.UTF_8)
        val probe = endpoint.toURL().openConnection() as HttpURLConnection
        probe.connectTimeout = 8_000
        probe.readTimeout = 15_000
        probe.instanceFollowRedirects = false
        probe.requestMethod = "POST"
        probe.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
        probe.setRequestProperty("Accept", "text/xml")
        probe.doOutput = true
        probe.setFixedLengthStreamingMode(probeBody.size)
        try {
            probe.outputStream.use { it.write(probeBody) }
            val status = probe.responseCode
            return when {
                status == HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    val challenges = probe.headerFields.entries
                        .filter { (name, _) -> name?.equals("WWW-Authenticate", true) == true }
                        .flatMap { it.value }
                    HttpRpcAuthorization.fromChallenge(challenges, auth, "POST", requestTarget)
                }
                // Some gateways allow anonymous XML-RPC reads but protect the
                // write resource. A credential-free HEAD can reveal that
                // resource's challenge without dispatching a mutation.
                status in 200..299 -> challengeWriteEndpoint(writeEndpoint, requestTarget)
                else -> throw IOException("rTorrent authentication preflight returned HTTP $status")
            }
        } finally {
            probe.disconnect()
        }
    }

    private fun challengeWriteEndpoint(writeEndpoint: URI, requestTarget: String): String? {
        val probe = writeEndpoint.toURL().openConnection() as HttpURLConnection
        probe.connectTimeout = 8_000
        probe.readTimeout = 15_000
        probe.instanceFollowRedirects = false
        probe.requestMethod = "HEAD"
        try {
            val status = probe.responseCode
            return when {
                status == HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    val challenges = probe.headerFields.entries
                        .filter { (name, _) -> name?.equals("WWW-Authenticate", true) == true }
                        .flatMap { it.value }
                    HttpRpcAuthorization.fromChallenge(challenges, auth, "POST", requestTarget)
                }
                // A public or unsupported HEAD gives no authority to send a
                // credential. Most method-specific 4xx responses, including
                // 403, say nothing about POST. Preserve one anonymous POST.
                // A proxy-auth response or rate limit must stop the operation.
                status in 200..299 || (status in 400..499 && status !in listOf(407, 429)) || status == 501 -> null
                else -> throw IOException("rTorrent write authentication preflight returned HTTP $status")
            }
        } finally {
            probe.disconnect()
        }
    }

    internal suspend fun call(method: String, params: List<Any> = emptyList()): Any? =
        withContext(Dispatchers.IO) {
            // A challenge-based credential exchange is scoped to the *same*
            // HTTPS/loopback endpoint. Never enable redirects or set a
            // process-global Java Authenticator that could leak credentials.
            val request = XmlRpcCodec.request(method, params).toByteArray(Charsets.UTF_8)
            val requestTarget = (endpoint.rawPath?.ifEmpty { "/" } ?: "/") +
                (endpoint.rawQuery?.let { "?" + it } ?: "")
            val mutation = !isReadOnlyRpcMethod(method, params)
            // A 401 may arrive after a broken gateway has applied a write.
            // Learn a fresh Basic/Digest challenge using a read-only RPC on
            // this same endpoint, then send the mutation only once.
            var authorization: String? = if (mutation) preauthorizeWrite(endpoint, requestTarget) else null
            // Read-only RPCs retain their one challenge retry. For writes,
            // even a 401 with WWW-Authenticate proves nothing about rollback.
            var attemptedChallenge = mutation
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
                        // A genuine HTTP 401 with unusable Basic/Digest challenge
                        // remains an authentication denial, never a transient
                        // read I/O error. Writes still authorize independently
                        // and must never be blindly replayed.
                        authorization = try {
                            HttpRpcAuthorization.fromChallenge(
                                challenges, auth, "POST", requestTarget)
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            throw RpcHttpStatusException(HttpURLConnection.HTTP_UNAUTHORIZED)
                        }
                        attemptedChallenge = true
                        continue
                    }
                    if (status !in 200..299) {
                        if (status in 100..599) throw RpcHttpStatusException(status)
                        // HttpURLConnection can report -1 for a malformed
                        // status line. It is NOT an HTTP response receipt and
                        // cannot be used to advance Smart Connect discovery.
                        throw IOException("Invalid rTorrent HTTP response")
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
                    return@withContext try {
                        val decoded = XmlRpcCodec.response(xml)
                        // Only a successfully decoded READ that first received
                        // HTTP 401 and retried with this exact endpoint's
                        // challenge provides read-authentication evidence.
                        // An anonymous 200, failed 401, malformed XML or write
                        // preflight does not prove a supplied username.
                        if (!mutation && attemptedChallenge && authorization != null)
                            acceptedReadChallenge = true
                        decoded
                    } catch (tooWide: XmlRpcSizeLimitExceeded) {
                        throw RpcReplyTooLarge(tooWide.message.orEmpty())
                    }
                } finally {
                    connection.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("RPC challenge negotiation exhausted")
        }

    private companion object {
        // No method is retryable just because its name *looks* like a getter.
        // A future setter, or a setter smuggled inside a multicall, must never
        // inherit the read-only HTTP 401 challenge/replay path.
        private val DIRECT_READ_METHODS = setOf(
            "system.client_version", "system.listMethods", "directory.default",
            "download_list", "network.local_address", "network.bind_address",
            "network.listen.port", "d.hash", "d.name", "d.size_bytes",
            "d.completed_bytes", "d.state", "d.directory", "d.down.rate",
            "d.up.rate", "d.ratio", "d.is_multi_file", "d.size_chunks",
            "d.bitfield", "d.is_private", "d.is_active", "d.is_open",
            "d.peers_connected", "d.down.total", "d.tracker_size",
            // Explicit rTorrent read-only diagnostic getters. Never classify
            // methods by name prefix; .set variants remain write-only.
            "d.message", "d.hashing", "d.hashing_failed", "d.is_hash_checking", "d.is_meta"
        )
        private val FILE_READ_FIELDS = setOf(
            "f.path=", "f.size_bytes=", "f.priority=",
            "f.completed_chunks=", "f.size_chunks=",
            "f.range_first=", "f.range_second="
        )
        private val TRACKER_READ_FIELDS = setOf(
            "t.url=", "t.is_enabled=", "t.success_counter=",
            "t.failed_counter=", "t.latest_sum_peers="
        )

        private fun isReadOnlyRpcMethod(method: String, params: List<Any>): Boolean {
            if (method in DIRECT_READ_METHODS) return true
            if (method == "d.multicall2") {
                return params.size in 3..(3 + EXTENDED_FIELDS.size) &&
                    params.drop(2).all { it is String && (it in EXTENDED_FIELDS || it == "d.is_meta=") }
            }
            if (method == "f.multicall" || method == "t.multicall") {
                val allowed = if (method == "f.multicall") FILE_READ_FIELDS else TRACKER_READ_FIELDS
                return params.size in 3..8 &&
                    params.drop(2).all { it is String && it in allowed }
            }
            if (method == "system.multicall") {
                val nested = params.singleOrNull() as? List<*> ?: return false
                // Production paging emits one exact-hash getter per entry.
                // Refuse nested multicall recursion and unrecognized methods.
                return nested.isNotEmpty() && nested.size <= PAGE_SIZE * (EXTENDED_FIELDS.size + 1) &&
                    nested.all { item ->
                        val fields = item as? Map<*, *> ?: return@all false
                        val name = fields["methodName"] as? String ?: return@all false
                        val args = fields["params"] as? List<*> ?: return@all false
                        name in DIRECT_READ_METHODS && name.startsWith("d.") &&
                            args.size == 1 && args.single() is String
                    }
            }
            return false // all unknown future RPCs are single-dispatch
        }
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
