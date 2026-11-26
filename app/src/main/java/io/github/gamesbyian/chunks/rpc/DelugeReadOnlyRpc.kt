package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only torrent inventory on an explicitly selected Deluge daemon.
 *
 * Deluge's authenticated Web UI may have multiple registered daemons. Web
 * login is insufficient to grant any torrent operation: inspect() confirms a
 * known host ID and its status before connecting this isolated web session
 * to that exact daemon. web.connect changes only this WebUI session's daemon,
 * never the torrent inventory. No Add/Start/Stop/Remove methods are exposed.
 */
internal class DelugeReadOnlyRpc(
    credentials: RemoteCredentials,
    private val daemonHostId: String,
    private val wire: Wire = HttpWire,
    private val routing: DelugeRoutingOwner = DelugeRoutingOwner.forEndpoint(
        validateEndpoint(credentials.endpoint))
) {
    private val auth = credentials
    private val endpoint = validateEndpoint(credentials.endpoint)

    init {
        require(daemonHostId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) {
            "A valid explicit Deluge daemon ID is required"
        }
    }

    internal class Request(val endpoint: URI, val body: ByteArray, val sessionCookie: String?) {
        override fun toString(): String = "Deluge RPC request (<redacted>)"
    }

    internal data class Response(
        val status: Int, val body: String,
        val headers: Map<String, List<String>> = emptyMap()
    ) {
        fun header(name: String): List<String> =
            headers.entries.filter { it.key.equals(name, true) }.flatMap { it.value }
    }

    internal fun interface Wire {
        suspend fun exchange(request: Request): Response
    }

    internal data class Torrent(
        val hash: String, val name: String, val state: String,
        val totalSizeBytes: Long?, val completedBytes: Long?,
        val downloadBytesPerSecond: Long?, val uploadBytesPerSecond: Long?,
        /** Daemon-reported only, never a verified client-side Place. */
        val reportedSavePath: String?
    )

    internal data class Snapshot(
        val selectedDaemonId: String, val daemonVersion: String,
        val torrents: List<Torrent>
    )

    /**
     * One bind/read/post-check bracket, serialized with every other Chunks caller of the same Web UI
     * through [DelugeRoutingOwner] (#2072). The owner is local serialization, not server atomicity.
     */
    suspend fun inspect(): Snapshot = routing.bracket(daemonHostId) { inspectBracketed() }

    private suspend fun inspectBracketed(): Snapshot = withContext(Dispatchers.IO) {
        require(auth.password.isNotEmpty()) { "Deluge web password is required" }
        val (authenticated, cookie) = login()
        if (!authenticated) throw IOException("Deluge Web UI login refused")
        val hostList = rpc("web.get_hosts", JSONArray(), 2, cookie) as? JSONArray
            ?: throw IOException("Deluge did not return a host list")
        if (hostList.length() > 1000) throw IOException("Deluge host list exceeds safety limit")
        val matches = (0 until hostList.length()).count { index ->
            val host = hostList.opt(index) as? JSONArray
                ?: throw IOException("Malformed Deluge host list")
            host.length() >= 4 && host.opt(0) == daemonHostId
        }
        if (matches != 1) throw IOException("Deluge daemon ID is missing or ambiguous")
        val status = rpc("web.get_host_status", JSONArray().put(daemonHostId), 3, cookie)
            as? JSONArray ?: throw IOException("Deluge daemon status unavailable")
        if (status.length() < 3 || status.opt(0) != daemonHostId ||
            status.opt(1) !is String || (status.opt(1) as String).lowercase() !in
            setOf("online", "connected")) throw IOException("Selected Deluge daemon is unavailable")
        val version = status.opt(2) as? String
            ?: throw IOException("Deluge daemon version unavailable")
        if (version.length !in 1..128 || version.any { it.isISOControl() }) {
            throw IOException("Invalid Deluge daemon version")
        }

        // The only non-accessor selects a verified daemon in the Web UI's
        // shared slot. The cookie authenticates the request, not the slot.
        // When the slot is ALREADY bound to this exact daemon, never reconnect:
        // web.connect re-establishes the daemon connection, so every periodic
        // inventory read would break another Chunks session's read or write
        // that is mid-bracket on the same Web UI (#2109). DelugeClient already
        // follows this rule; the daemon's own method list proves capability.
        val alreadyBound = (status.opt(1) as String).equals("connected", ignoreCase = true)
        val supported = (if (alreadyBound) rpc("daemon.get_method_list", JSONArray(), 4, cookie)
            else rpc("web.connect", JSONArray().put(daemonHostId), 4, cookie)) as? JSONArray
            ?: throw IOException("Selected Deluge daemon refused connection")
        // Both return *daemon* methods (get_method_list may omit the "core."
        // prefix); web.update_ui itself is a WebUI-side aggregator and never
        // proves the selected daemon supports full-inventory reads. No
        // arbitrary nonempty method list is enough.
        if (supported.length() > 5000 || !(0 until supported.length()).any {
                val method = supported.opt(it) as? String
                // Only the daemon's own method list may omit the prefix.
                method == "core.get_torrents_status" || (alreadyBound && method == "get_torrents_status")
            }) {
            throw IOException("Selected Deluge daemon lacks inventory capability")
        }
        val bound = rpc("web.get_host_status", JSONArray().put(daemonHostId), 5, cookie) as? JSONArray
            ?: throw IOException("Deluge active daemon status unavailable")
        if (bound.opt(0) != daemonHostId ||
            !(bound.opt(1) as? String).equals("connected", ignoreCase = true)) {
            throw IOException("Deluge Web UI is not connected to the selected daemon")
        }
        // Only Chunks' own web.connect counts as where Chunks left the slot; an observation does not.
        if (!alreadyBound) routing.noteBound(daemonHostId)
        val keys = JSONArray(listOf(
            "name", "state", "total_size", "total_done",
            "download_payload_rate", "upload_payload_rate", "save_path"
        ))
        val update = rpc("web.update_ui", JSONArray().put(keys).put(JSONObject()), 6, cookie)
            as? JSONObject ?: throw IOException("Deluge inventory unavailable")
        val transfers = update.opt("torrents") as? JSONObject
            ?: throw IOException("Deluge inventory is not complete")
        val names = transfers.keys().asSequence().toList()
        if (names.size > 50_000) throw IOException("Deluge inventory exceeds safety limit")
        val seen = HashSet<String>()
        val torrents = names.map { rawHash ->
            val hash = rawHash.lowercase()
            if (!HASH.matches(hash) || !seen.add(hash)) {
                throw IOException("Ambiguous Deluge torrent identity")
            }
            val entry = transfers.opt(rawHash) as? JSONObject
                ?: throw IOException("Invalid Deluge inventory entry")
            Torrent(
                hash = hash,
                name = requiredText(entry, "name", 2048),
                state = requiredText(entry, "state", 128),
                totalSizeBytes = number(entry, "total_size"),
                completedBytes = number(entry, "total_done"),
                downloadBytesPerSecond = number(entry, "download_payload_rate"),
                uploadBytesPerSecond = number(entry, "upload_payload_rate"),
                reportedSavePath = optionalText(entry, "save_path", 4096)
            )
        }
        // The Web UI's daemon slot is shared and the JSON call carries no host ID. Confirm the
        // selected daemon was still the bound one after the read, or the rows may belong to another.
        val after = rpc("web.get_host_status", JSONArray().put(daemonHostId), 7, cookie) as? JSONArray
            ?: throw IOException("Deluge active daemon status unavailable")
        if (after.opt(0) != daemonHostId || !(after.opt(1) as? String).equals("connected", ignoreCase = true)) {
            throw IOException("Deluge Web UI stopped being connected to the selected daemon during the read")
        }
        Snapshot(daemonHostId, version, torrents)
    }

    /**
     * Read-only discovery of the daemon host IDs registered in this Web UI.
     * Selection remains explicit: callers may adopt an ID automatically only
     * when exactly one daemon is registered, never by picking among several.
     */
    suspend fun registeredDaemonIds(): List<String> = withContext(Dispatchers.IO) {
        require(auth.password.isNotEmpty()) { "Deluge web password is required" }
        val (authenticated, cookie) = login()
        if (!authenticated) throw IOException("Deluge Web UI login refused")
        val hostList = rpc("web.get_hosts", JSONArray(), 2, cookie) as? JSONArray
            ?: throw IOException("Deluge did not return a host list")
        if (hostList.length() > 1000) throw IOException("Deluge host list exceeds safety limit")
        (0 until hostList.length()).map { index ->
            val host = hostList.opt(index) as? JSONArray
                ?: throw IOException("Malformed Deluge host list")
            (host.opt(0) as? String)?.takeIf { DAEMON_ID.matches(it) }
                ?: throw IOException("Malformed Deluge host identity")
        }
    }

    /** Authenticated web session cookie is scoped to this endpoint, never global. */
    private suspend fun login(): Pair<Boolean, String> {
        val result = call("auth.login", JSONArray().put(auth.password), 1, null)
        if (result.status != 200) throw IOException("Deluge Web UI login refused (HTTP ${result.status})")
        val json = decode(result.body, 1)
        if (json.opt("result") != true) throw IOException("Deluge Web UI login refused (password rejected)")
        val sid = result.header("Set-Cookie").mapNotNull { value ->
            value.substringBefore(';').trim().takeIf { it.startsWith("_session_id=") }
                ?.removePrefix("_session_id=")
        }
        if (sid.size != 1 || !SESSION.matches(sid.single())) {
            throw IOException("Deluge Web UI session cookie unavailable")
        }
        return true to "_session_id=" + sid.single()
    }

    private suspend fun rpc(name: String, args: JSONArray, id: Int, cookie: String): Any? {
        val response = call(name, args, id, cookie)
        if (response.status != 200) {
            throw if (response.status in 100..599) RpcHttpStatusException(response.status)
            else IOException("Invalid Deluge HTTP response")
        }
        return decode(response.body, id, inventory = name == "web.update_ui").opt("result")
    }

    private fun decode(text: String, id: Int, inventory: Boolean = false): JSONObject {
        val obj = try {
            if (inventory) JSONObject(text) else ExactRpcJson.objectBody(text)
        } catch (_: Exception) {
            throw IOException("Malformed Deluge JSON-RPC reply")
        }
        if (exactWireInteger(obj.opt("id")) != id.toLong() ||
            obj.opt("error") != null && !obj.isNull("error") ||
            !obj.has("result")) throw IOException("Deluge RPC result failed validation")
        return obj
    }

    private suspend fun call(name: String, args: JSONArray, id: Int, cookie: String?): Response {
        val body = JSONObject().put("method", name).put("params", args)
            .put("id", id).toString().toByteArray(Charsets.UTF_8)
        val response = wire.exchange(Request(endpoint, body, cookie))
        if (response.body.length > MAX_BODY) throw IOException("Deluge reply too large")
        return response
    }

    private fun requiredText(row: JSONObject, key: String, max: Int): String =
        optionalText(row, key, max)?.takeIf { it.isNotBlank() }
            ?: throw IOException("Missing Deluge inventory text")

    private fun optionalText(row: JSONObject, key: String, max: Int): String? {
        if (!row.has(key) || row.isNull(key)) return null
        val value = row.opt(key) as? String
            ?: throw IOException("Invalid Deluge inventory text")
        if (value.length > max || value.any { it.isISOControl() })
            throw IOException("Invalid Deluge inventory text")
        return value
    }

    private fun number(row: JSONObject, key: String): Long? {
        if (!row.has(key) || row.isNull(key)) return null
        val value = row.opt(key) as? Number
            ?: throw IOException("Invalid Deluge inventory counter")
        return value.toString().toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("Invalid Deluge inventory counter")
    }

    internal companion object {
        const val MAX_BODY = 4_194_304
        val HASH = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
        val DAEMON_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        val SESSION = Regex("[A-Za-z0-9._~%-]{8,256}")
        val ESCAPED = Regex("(?i)%(?:25)*(?:2f|5c|0[0-9a-f]|1[0-9a-f]|7f)")
        val DOT = Regex("(?i)(?:^|/)(?:\\.|%(?:25)*2e){1,2}(?:/|$)")

        fun validateEndpoint(raw: String): URI {
            val uri = try { URI(raw) } catch (_: Exception) {
                throw IllegalArgumentException("Invalid Deluge JSON endpoint")
            }
            val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
            require(uri.scheme.equals("https", true) ||
                (uri.scheme.equals("http", true) && loopback)) {
                "Deluge requires HTTPS except on loopback"
            }
            val path = uri.rawPath.orEmpty()
            require(!uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null &&
                path.endsWith("/json") && path.length in 5..2048 &&
                !ESCAPED.containsMatchIn(path) && !DOT.containsMatchIn(path)) {
                "Invalid or ambiguous Deluge JSON endpoint"
            }
            return uri
        }
    }

    /**
     * No redirect, auth cookie only to the exact supplied endpoint.
     *
     * Direct (non-proxied) calls run on an own Socket/SSLSocket on a dedicated daemon thread while the
     * caller suspends. Cancellation and the deadlines CLOSE the real socket, so a blocked write or read ends
     * at once: cancel is measured in milliseconds, a write that makes no progress for 15 s (HttpURLConnection
     * has no write timeout) and a call older than 30 s are aborted by a watchdog. A connect in progress is
     * deliberately not aborted (bounded by the 8 s connect timeout); a cancel that wins during connect never
     * sends. [exchange] then WAITS, shielded from the cancellation, until the worker really ended, so a
     * routing-owner bracket never releases the endpoint lock while this call's I/O is alive. TLS uses the
     * platform default SSLSocketFactory with SNI and endpoint identification "HTTPS"; an https URL is never
     * spoken in clear and redirects are never followed. Requests through a configured system proxy keep the
     * previous HttpURLConnection path (the platform proxy logic is not reimplemented); only there the
     * 25 s ceiling remains, after which the lock is released anyway and the post-call checks are the safety net.
     *
     * LIMIT, stated plainly: once the body has been dispatched, cancellation does not undo it. After such a
     * cancel the lock no longer covers the server-side execution, which may still happen later. The call
     * surfaces only as CancellationException (never "refused" or "not written"), is uncertain by contract,
     * and nothing here or in the callers replays it.
     */
    internal object HttpWire : Wire {
        private val executor = java.util.concurrent.Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "chunks-deluge-wire").apply { isDaemon = true }
        }
        private val inFlightCount = java.util.concurrent.atomic.AtomicInteger()
        /** 8 s connect + 15 s read + margin. A blocked body write has no socket timeout of its own. */
        private const val WORKER_END_BOUND_MS = 25_000L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val STALL_MS = 15_000L
        private const val CALL_DEADLINE_MS = 30_000L
        private const val WRITE_CHUNK = 16 * 1024

        /** Worker threads whose I/O has not ended yet (observability for tests). */
        val inFlight: Int get() = inFlightCount.get()

        private class Call(val request: Request) {
            @Volatile var cancelled = false
            @Volatile var expired = false
            val result = kotlinx.coroutines.CompletableDeferred<Response>()
            val ended = kotlinx.coroutines.CompletableDeferred<Unit>()
            val startedNanos = System.nanoTime()
            /** Last write/read progress, for the write-stall deadline. */
            @Volatile var progressNanos = startedNanos
            private val gate = Any()
            private var abortable = false
            private var closeables: List<java.io.Closeable> = emptyList()
            /** Set only on the legacy (proxied) path. */
            @Volatile var legacy: HttpURLConnection? = null

            /** From here on [abort] may close the sockets; a connect in progress is never aborted. */
            fun armAbort(vararg sockets: java.io.Closeable) {
                synchronized(gate) {
                    closeables = sockets.toList()
                    abortable = true
                    if (cancelled || expired) closeables.forEach { runCatching { it.close() } }
                }
                if (cancelled || expired) throw IOException("Deluge RPC aborted before dispatch")
            }

            fun abort(byCancel: Boolean) {
                if (byCancel) cancelled = true else expired = true
                synchronized(gate) { if (abortable) closeables.forEach { runCatching { it.close() } } }
                legacy?.disconnect()
            }

            fun closeAll() { synchronized(gate) { closeables.forEach { runCatching { it.close() } } } }
        }

        private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "chunks-deluge-wire-watchdog").apply { isDaemon = true }
        }

        override suspend fun exchange(request: Request): Response {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val call = Call(request)
            inFlightCount.incrementAndGet()
            val deadline = watchdog.scheduleWithFixedDelay({
                val now = System.nanoTime()
                val total = (now - call.startedNanos) / 1_000_000
                val stalled = (now - call.progressNanos) / 1_000_000
                if (total > CALL_DEADLINE_MS || stalled > STALL_MS) call.abort(byCancel = false)
            }, 250, 250, java.util.concurrent.TimeUnit.MILLISECONDS)
            executor.execute {
                try {
                    call.result.complete(blockingExchange(call))
                } catch (failure: Throwable) {
                    call.result.completeExceptionally(failure)
                } finally {
                    deadline.cancel(false)
                    inFlightCount.decrementAndGet()
                    call.ended.complete(Unit)
                }
            }
            try {
                return call.result.await()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                call.abort(byCancel = true)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    // Bounded: only an uninterruptible connect/DNS can outlive the abort, and the connect is timed.
                    kotlinx.coroutines.withTimeoutOrNull(WORKER_END_BOUND_MS) { call.ended.await() }
                }
                throw cancel
            }
        }

        private fun blockingExchange(call: Call): Response {
            val uri = call.request.endpoint
            val viaProxy = runCatching {
                java.net.ProxySelector.getDefault()?.select(uri).orEmpty().any { it.type() != java.net.Proxy.Type.DIRECT }
            }.getOrDefault(true)
            return if (viaProxy) legacyExchange(call) else socketExchange(call)
        }

        /**
         * Direct (non-proxied) path on an own Socket/SSLSocket so that cancellation and deadlines close the
         * real socket: a blocked write or read ends at once, there is a write-stall timeout and a total-call
         * deadline. TLS: platform default SSLSocketFactory, SNI from the host, platform trust manager and
         * endpoint identification "HTTPS" (certificate must match the exact URL host). No redirect, no
         * downgrade (an https endpoint is never spoken in clear), HTTP/1.1 with Connection: close.
         */
        private fun socketExchange(call: Call): Response {
            val request = call.request
            val uri = request.endpoint
            val tls = uri.scheme.equals("https", true)
            val host = uri.host.removePrefix("[").removeSuffix("]")
            val port = if (uri.port >= 0) uri.port else if (tls) 443 else 80
            var plain: java.net.Socket? = null
            var secure: javax.net.ssl.SSLSocket? = null
            try {
                if (call.cancelled) throw IOException("Deluge RPC cancelled before dispatch")
                val addresses = java.net.InetAddress.getAllByName(host)
                var last: IOException? = null
                for (address in addresses) {
                    if (call.cancelled) throw IOException("Deluge RPC cancelled before dispatch")
                    val candidate = java.net.Socket()
                    try {
                        candidate.tcpNoDelay = true
                        candidate.connect(java.net.InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                        plain = candidate
                        break
                    } catch (failure: IOException) {
                        runCatching { candidate.close() }
                        last = failure
                    }
                }
                val socket = plain ?: throw (last ?: IOException("no address"))
                socket.soTimeout = READ_TIMEOUT_MS
                // A cancel that won while the connect was in progress must never send.
                call.armAbort(socket)
                var closeable: java.net.Socket = socket
                if (tls) {
                    val layered = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                        .createSocket(socket, host, port, true) as javax.net.ssl.SSLSocket
                    secure = layered
                    closeable = layered
                    val parameters = layered.sslParameters
                    parameters.endpointIdentificationAlgorithm = "HTTPS"
                    layered.sslParameters = parameters
                    call.armAbort(layered)
                    layered.startHandshake()
                    call.progressNanos = System.nanoTime()
                }
                val hostHeader = (if (host.contains(':')) "[$host]" else host) +
                    (if (uri.port >= 0) ":${uri.port}" else "")
                val target = uri.rawPath.orEmpty().ifEmpty { "/" }
                val head = StringBuilder()
                    .append("POST ").append(target).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(hostHeader).append("\r\n")
                    .append("Content-Type: application/json\r\n")
                    .append("Accept: application/json\r\n")
                    .append("Accept-Encoding: identity\r\n")
                    .append("Connection: close\r\n")
                    .append("Content-Length: ").append(request.body.size).append("\r\n")
                request.sessionCookie?.let {
                    require(it.none { c -> c == '\r' || c == '\n' }) { "bad cookie" }
                    head.append("Cookie: ").append(it).append("\r\n")
                }
                head.append("\r\n")
                if (call.cancelled) throw IOException("Deluge RPC cancelled before dispatch")
                val out = closeable.getOutputStream()
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                var offset = 0
                while (offset < request.body.size) {
                    if (call.cancelled || call.expired) throw IOException("Deluge RPC aborted")
                    val n = minOf(WRITE_CHUNK, request.body.size - offset)
                    out.write(request.body, offset, n)
                    offset += n
                    call.progressNanos = System.nanoTime()
                }
                out.flush()
                return readResponse(closeable.getInputStream(), call)
            } catch (_: IOException) {
                throw IOException("Deluge RPC transport failed")
            } finally {
                call.closeAll()
                runCatching { secure?.close() }
                runCatching { plain?.close() }
            }
        }

        private fun readLine(input: java.io.InputStream, limit: Int): String {
            val line = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("eof in header")
                if (b == '\n'.code) break
                if (b != '\r'.code) line.write(b)
                if (line.size() > limit) throw IOException("header line too long")
            }
            return line.toString(Charsets.ISO_8859_1.name())
        }

        private fun readResponse(input: java.io.InputStream, call: Call): Response {
            var status: Int
            var headerBytes = 0
            val headers = LinkedHashMap<String, MutableList<String>>()
            var contentLength = -1L
            var chunked = false
            while (true) {
                val statusLine = readLine(input, 8192)
                val parts = statusLine.split(' ', limit = 3)
                if (parts.size < 2 || !parts[0].startsWith("HTTP/1.")) throw IOException("bad status line")
                status = parts[1].toIntOrNull() ?: throw IOException("bad status")
                headers.clear(); contentLength = -1; chunked = false
                while (true) {
                    val line = readLine(input, 8192)
                    if (line.isEmpty()) break
                    headerBytes += line.length
                    if (headerBytes > 65_536) throw IOException("headers too large")
                    val colon = line.indexOf(':')
                    if (colon <= 0) throw IOException("bad header")
                    val name = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    when {
                        name.equals("Set-Cookie", true) -> headers.getOrPut(name) { mutableListOf() }.add(value)
                        name.equals("Content-Length", true) ->
                            contentLength = value.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("bad length")
                        name.equals("Transfer-Encoding", true) ->
                            if (value.equals("chunked", true)) chunked = true else throw IOException("unsupported encoding")
                        name.equals("Content-Encoding", true) ->
                            if (!value.equals("identity", true)) throw IOException("unsupported content encoding")
                    }
                }
                if (status != 100) break
            }
            call.progressNanos = System.nanoTime()
            if (status !in 200..299) return Response(status, "", headers)
            val body = java.io.ByteArrayOutputStream()
            fun take(count: Int) {
                val buffer = ByteArray(minOf(count, 8192))
                var left = count
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(left, buffer.size))
                    if (n < 0) throw IOException("truncated body")
                    body.write(buffer, 0, n); left -= n
                    if (body.size() > MAX_BODY) throw IOException("Deluge reply too large")
                    call.progressNanos = System.nanoTime()
                }
            }
            when {
                chunked -> while (true) {
                    val size = readLine(input, 64).substringBefore(';').trim().toIntOrNull(16)
                        ?.takeIf { it >= 0 } ?: throw IOException("bad chunk")
                    if (size == 0) break
                    if (size > MAX_BODY) throw IOException("Deluge reply too large")
                    take(size)
                    if (readLine(input, 8).isNotEmpty()) throw IOException("bad chunk end")
                }
                contentLength >= 0 -> {
                    if (contentLength > MAX_BODY) throw IOException("Deluge reply too large")
                    take(contentLength.toInt())
                }
                else -> {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        body.write(buffer, 0, n)
                        if (body.size() > MAX_BODY) throw IOException("Deluge reply too large")
                        call.progressNanos = System.nanoTime()
                    }
                }
            }
            return Response(status, body.toString(Charsets.UTF_8.name()), headers)
        }

        /** Proxied path (the platform proxy logic is not reimplemented): HttpURLConnection, 25 s ceiling. */
        private fun legacyExchange(call: Call): Response {
            val connection = call.request.endpoint.toURL().openConnection() as HttpURLConnection
            call.legacy = connection
            if (call.cancelled) throw IOException("Deluge RPC cancelled before dispatch")
            return legacyBlocking(call, connection)
        }

        private fun legacyBlocking(call: Call, connection: HttpURLConnection): Response {
            val request = call.request
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            request.sessionCookie?.let { connection.setRequestProperty("Cookie", it) }
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(request.body.size)
            try {
                if (call.cancelled) throw IOException("Deluge RPC cancelled before dispatch")
                connection.connect()
                // A cancel that won while the connect was in progress must never send.
                if (call.cancelled) throw IOException("Deluge RPC cancelled before dispatch")
                connection.outputStream.use { it.write(request.body) }
                val status = connection.responseCode
                val headers = connection.headerFields.entries
                    .filter { it.key?.equals("Set-Cookie", true) == true }
                    .associate { it.key!! to it.value.orEmpty() }
                val text = if (status !in 200..299) "" else
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val builder = StringBuilder()
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            builder.append(buffer, 0, count)
                            if (builder.length > MAX_BODY) throw IOException("Deluge reply too large")
                        }
                        builder.toString()
                    }
                return Response(status, text, headers)
            } catch (_: IOException) {
                throw IOException("Deluge RPC transport failed")
            } finally {
                connection.disconnect()
            }
        }
    }
}
