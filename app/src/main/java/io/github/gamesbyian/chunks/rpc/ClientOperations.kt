package io.github.gamesbyian.chunks.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Result of one user-authorized operation on one exact client + hash.
 * Requested/acknowledged is never success: only [Verified] means a fresh
 * exact-hash readback observed the requested postcondition.
 */
internal sealed interface ClientOperationOutcome {
    val hash: String

    data class Verified(override val hash: String, val observed: ClientTorrent?) : ClientOperationOutcome
    /** Write acknowledged; readback has not (yet) shown the postcondition. */
    data class Pending(override val hash: String, val observed: ClientTorrent?, val reason: String) :
        ClientOperationOutcome
    /** Delivery unknown. Refresh read-only; never automatically repeat. */
    data class Uncertain(override val hash: String, val reason: String) : ClientOperationOutcome
    /** Nothing was written (precondition, capability or definite refusal). */
    data class Refused(override val hash: String, val reason: String) : ClientOperationOutcome
}

/**
 * The single application-level workflow shared by every client, Local included.
 * Protocol differences live only behind [TorrentClient] and its
 * [ClientCapability] set; this object never branches on backend kind.
 */
internal class ClientOperations(
    /** The exact client this workflow drives (shared with Move). */
    val client: TorrentClient,
    private val readbackAttempts: Int = 12,
    private val readbackDelayMillis: Long = 750,
    /**
     * Extra exact reads a stopped Add must STAY stopped for. Some clients
     * (qBittorrent with a magnet) first report paused, then start fetching
     * metadata: a fleeting paused observation cannot prove a stopped Add.
     */
    private val stoppedSettleReads: Int = 4
) {
    private var cachedSession: ClientSessionInfo? = null

    suspend fun session(refresh: Boolean = false): ClientSessionInfo =
        cachedSession?.takeUnless { refresh } ?: client.session().also { cachedSession = it }

    /** Explicit read of one exact transfer's files (no mutation). */
    suspend fun files(hash: String): List<ClientFile> = client.files(ClientHashes.requireHash(hash))

    suspend fun add(request: ClientAddRequest): ClientOperationOutcome =
        if (request.skippedFiles.isEmpty()) addOnce(request) else addWithFileChoices(request)

    /**
     * Reviewed file skips for every engine, one gated step at a time:
     * stopped Add (verified) → the registered job's own layout must match the
     * reviewed index AND path → skips (verified) → the requested Start. A
     * failure after registration leaves the job stopped and is reported, never
     * replayed; Start is never sent unless the skips were verified.
     */
    private suspend fun addWithFileChoices(request: ClientAddRequest): ClientOperationOutcome {
        val hash = request.source.infoHash
        val caps = try { session().capabilities } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return ClientOperationOutcome.Refused(hash, "Client session unavailable") }
        listOf(ClientCapability.ADD_STOPPED, ClientCapability.FILE_PRIORITIES).firstOrNull { it !in caps }?.let {
            return ClientOperationOutcome.Refused(hash, "Client does not support ${it.name} (needed for file choices)")
        }
        if (ClientCapability.ADD_WITH_FILE_CHOICES in caps) {
            // The client applies the skips inside its own Add; verify them here.
            val added = addOnce(request)
            if (added is ClientOperationOutcome.Refused || added is ClientOperationOutcome.Uncertain) return added
            return if (skipsObserved(hash, request.skippedFiles)) added
            else ClientOperationOutcome.Pending(hash, (added as? ClientOperationOutcome.Verified)?.observed,
                "Added; the skipped files were not confirmed")
        }
        val added = addOnce(request.copy(startNow = false, skippedFiles = emptyMap()))
        if (added !is ClientOperationOutcome.Verified) return added
        val files = try { client.files(hash) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        val matched = files?.let { ClientFileLayout.match(it, request.skippedFiles) }
            ?: return ClientOperationOutcome.Pending(hash, added.observed,
                "Added stopped; its file list did not match the review, so no files were skipped and it was not started")
        val skipped = setFilePriorities(hash, matched.associateWith { ClientFilePriority.SKIP })
        if (skipped !is ClientOperationOutcome.Verified) return when (skipped) {
            is ClientOperationOutcome.Uncertain -> skipped
            else -> ClientOperationOutcome.Pending(hash, added.observed,
                "Added stopped; the skipped files were not confirmed, so it was not started")
        }
        if (!request.startNow) return ClientOperationOutcome.Verified(hash, added.observed)
        return when (val started = setStarted(hash, true)) {
            is ClientOperationOutcome.Refused ->
                ClientOperationOutcome.Pending(hash, added.observed, "Added stopped with file choices; Start was refused")
            else -> started
        }
    }

    private suspend fun skipsObserved(hash: String, reviewed: Map<Int, String>): Boolean {
        repeat(readbackAttempts) { attempt ->
            if (attempt > 0) delay(readbackDelayMillis)
            val files = try { client.files(hash) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null } ?: return@repeat
            val matched = ClientFileLayout.match(files, reviewed) ?: return false
            val byIndex = files.associateBy { it.index }
            if (matched.all { byIndex[it]?.priority == ClientFilePriority.SKIP }) return true
        }
        return false
    }

    private suspend fun addOnce(request: ClientAddRequest): ClientOperationOutcome {
        val hash = request.source.infoHash
        val caps = try { session().capabilities } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return ClientOperationOutcome.Refused(hash, "Client session unavailable") }
        val needed = buildList {
            add(if (request.source is ClientAddSource.Magnet) ClientCapability.ADD_MAGNET
                else ClientCapability.ADD_TORRENT_FILE)
            if (!request.startNow) add(ClientCapability.ADD_STOPPED)
            if (request.directory != null) add(ClientCapability.ADD_TO_DIRECTORY)
        }
        needed.firstOrNull { it !in caps }?.let {
            return ClientOperationOutcome.Refused(hash, "Client does not support ${it.name}")
        }
        when (val pre = exactRead(hash)) {
            is Read.Failed -> return ClientOperationOutcome.Refused(hash, "Could not verify the client first")
            is Read.Present -> return ClientOperationOutcome.Refused(hash, "Already in this client")
            Read.Absent -> Unit
        }
        write(hash) { client.add(request) }?.let { return it }
        val outcome = readback(hash) { observed ->
            when {
                observed == null -> "Not yet registered"
                request.directory != null &&
                    !ClientHashes.sameDirectory(observed.downloadDirectory, request.directory) ->
                    "Registered in a different directory"
                !request.startNow && observed.runState.started == true -> "Registered but running"
                // Clients may hash-check existing payload first; only the
                // settled stopped state satisfies a stopped Add.
                !request.startNow && observed.runState != ClientRunState.STOPPED -> "Checking existing data"
                // Add acknowledgement proves registration, not that an
                // explicitly requested Start has taken effect. QUEUED and
                // CHECKING may never advance without another authorization.
                request.startNow && observed.runState.started != true ->
                    "Registered but Start not yet observed"
                else -> null
            }
        }
        if (request.startNow || outcome !is ClientOperationOutcome.Verified) return outcome
        repeat(stoppedSettleReads) {
            delay(readbackDelayMillis)
            when (val read = exactRead(hash)) {
                is Read.Present -> if (read.torrent.runState != ClientRunState.STOPPED)
                    return ClientOperationOutcome.Pending(hash, read.torrent,
                        "Registered stopped, then the client changed it to ${read.torrent.runState.name.lowercase()}")
                Read.Absent -> return ClientOperationOutcome.Pending(hash, null, "Registered, then no longer listed")
                is Read.Failed -> Unit
            }
        }
        return outcome
    }

    suspend fun setStarted(hash: String, started: Boolean): ClientOperationOutcome =
        mutateExisting(hash, ClientCapability.START_STOP, { client.setStarted(hash, started) }) { observed ->
            when {
                observed == null -> "Transfer disappeared"
                started && observed.runState.started != true -> "Start not yet observed"
                !started && observed.runState != ClientRunState.STOPPED -> "Still running"
                else -> null
            }
        }

    suspend fun remove(hash: String, deleteData: Boolean): ClientOperationOutcome =
        mutateExisting(hash,
            if (deleteData) ClientCapability.REMOVE_WITH_DATA else ClientCapability.REMOVE,
            { client.remove(hash, deleteData) }) { observed ->
            if (observed == null) null else "Still registered"
        }

    suspend fun setLocation(hash: String, directory: String, moveData: Boolean): ClientOperationOutcome {
        ClientHashes.requireDirectory(directory)
        return mutateExisting(hash, ClientCapability.SET_LOCATION,
            { client.setLocation(hash, directory, moveData) }) { observed ->
            when {
                observed == null -> "Transfer disappeared"
                !ClientHashes.sameDirectory(observed.downloadDirectory, directory) -> "Directory not yet changed"
                else -> null
            }
        }
    }

    suspend fun recheck(hash: String): ClientOperationOutcome =
        mutateExisting(hash, ClientCapability.RECHECK, { client.recheck(hash) }) { observed ->
            // A fast recheck may finish before any read: presence is the only
            // honest postcondition; callers must not claim verified pieces.
            if (observed == null) "Transfer disappeared" else null
        }

    /**
     * Priorities are verified against a fresh file list, after mapping each
     * request to the level this client actually stores (qBittorrent has no
     * LOW, for example), so readback compares like with like.
     */
    suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>): ClientOperationOutcome {
        val exact = ClientHashes.requireHash(hash)
        if (priorities.isEmpty()) return ClientOperationOutcome.Refused(exact, "No file priorities requested")
        val info = try { session() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return ClientOperationOutcome.Refused(exact, "Client session unavailable") }
        if (ClientCapability.FILE_PRIORITIES !in info.capabilities)
            return ClientOperationOutcome.Refused(exact, "Client does not support file priorities")
        val requested = priorities.mapValues { info.storable(it.value) }
        val before = try { client.files(exact) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return ClientOperationOutcome.Refused(exact, "Could not read the file list first") }
        val known = before.map { it.index }.toSet()
        if (!known.containsAll(requested.keys))
            return ClientOperationOutcome.Refused(exact, "File list changed; refresh first")
        write(exact) { client.setFilePriorities(exact, requested) }?.let { return it }
        repeat(readbackAttempts) { attempt ->
            if (attempt > 0) delay(readbackDelayMillis)
            val files = try { client.files(exact) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            if (files != null) {
                val observed = files.associate { it.index to it.priority }
                val ok = requested.all { (index, wanted) -> observed[index] == wanted }
                if (ok) return ClientOperationOutcome.Verified(exact, null)
            }
        }
        return ClientOperationOutcome.Pending(exact, null, "File priorities not yet observed")
    }

    private suspend fun mutateExisting(
        hash: String,
        capability: ClientCapability,
        send: suspend () -> Unit,
        unmet: (ClientTorrent?) -> String?
    ): ClientOperationOutcome {
        val exact = ClientHashes.requireHash(hash)
        val caps = try { session().capabilities } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return ClientOperationOutcome.Refused(exact, "Client session unavailable") }
        if (capability !in caps)
            return ClientOperationOutcome.Refused(exact, "Client does not support ${capability.name}")
        when (exactRead(exact)) {
            is Read.Failed -> return ClientOperationOutcome.Refused(exact, "Could not verify the transfer first")
            Read.Absent -> return ClientOperationOutcome.Refused(exact, "Transfer is not in this client")
            is Read.Present -> Unit
        }
        write(exact) { send() }?.let { return it }
        return readback(exact, unmet)
    }

    /** One write; returns a terminal outcome on failure, null when acknowledged. */
    private suspend fun write(hash: String, send: suspend () -> Unit): ClientOperationOutcome? = try {
        send()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: ClientPostWritePendingException) {
        // The client acknowledged a consequential write. A successful session
        // read cannot prove asynchronous filesystem cleanup; never replay it.
        ClientOperationOutcome.Pending(hash, null, e.message ?: "Effect still pending")
    } catch (e: ClientRefusedException) {
        ClientOperationOutcome.Refused(hash, e.message ?: "Refused by the client")
    } catch (e: Exception) {
        ClientOperationOutcome.Uncertain(hash, "The client's reply was lost; refresh before trying again")
    }

    private suspend fun readback(hash: String, unmet: (ClientTorrent?) -> String?): ClientOperationOutcome {
        var lastReason = "Not yet observed"
        var lastObserved: ClientTorrent? = null
        var anyRead = false
        repeat(readbackAttempts) { attempt ->
            if (attempt > 0) delay(readbackDelayMillis)
            when (val read = exactRead(hash)) {
                is Read.Failed -> Unit
                Read.Absent -> {
                    anyRead = true
                    lastObserved = null
                    val reason = unmet(null) ?: return ClientOperationOutcome.Verified(hash, null)
                    lastReason = reason
                }
                is Read.Present -> {
                    anyRead = true
                    lastObserved = read.torrent
                    val reason = unmet(read.torrent) ?: return ClientOperationOutcome.Verified(hash, read.torrent)
                    lastReason = reason
                }
            }
        }
        return if (anyRead) ClientOperationOutcome.Pending(hash, lastObserved, lastReason)
        else ClientOperationOutcome.Uncertain(hash, "Could not read the client after the request")
    }

    private sealed interface Read {
        data class Present(val torrent: ClientTorrent) : Read
        data object Absent : Read
        data class Failed(val error: Exception) : Read
    }

    private suspend fun exactRead(hash: String): Read = try {
        val observed = client.torrent(hash)
        if (observed == null) Read.Absent
        else if (!observed.hash.equals(hash, ignoreCase = true)) Read.Failed(IllegalStateException("Wrong identity"))
        else Read.Present(observed)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Read.Failed(e)
    }
}

/**
 * Matches reviewed metainfo paths to a client's own file list. Clients differ
 * only in whether they prefix the torrent's top-level folder; the index must
 * agree AND the paths must be equal after that prefix. Null = no faithful match.
 */
internal object ClientFileLayout {
    fun match(clientFiles: List<ClientFile>, reviewed: Map<Int, String>): List<Int>? {
        val byIndex = clientFiles.associateBy { it.index }
        return reviewed.map { (index, path) ->
            val actual = byIndex[index]?.path?.trim('/') ?: return null
            val wanted = path.trim('/')
            if (actual == wanted || actual.endsWith("/$wanted") || wanted.endsWith("/$actual")) index else return null
        }
    }
}
