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
 * The single application-level workflow shared by every remote protocol.
 * Protocol differences live only behind [RemoteTorrentClient] and its
 * [ClientCapability] set; this object never branches on backend kind.
 */
internal class RemoteClientOperations(
    /** The exact client this workflow drives (shared with Move). */
    val client: RemoteTorrentClient,
    private val readbackAttempts: Int = 12,
    private val readbackDelayMillis: Long = 750
) {
    private var cachedSession: ClientSessionInfo? = null

    suspend fun session(refresh: Boolean = false): ClientSessionInfo =
        cachedSession?.takeUnless { refresh } ?: client.session().also { cachedSession = it }

    /** Explicit read of one exact transfer's files (no mutation). */
    suspend fun files(hash: String): List<ClientFile> = client.files(ClientHashes.requireHash(hash))

    suspend fun add(request: ClientAddRequest): ClientOperationOutcome {
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
        return readback(hash) { observed ->
            when {
                observed == null -> "Not yet registered"
                request.directory != null &&
                    !ClientHashes.sameDirectory(observed.downloadDirectory, request.directory) ->
                    "Registered in a different directory"
                !request.startNow && observed.runState.started == true -> "Registered but running"
                // Clients may hash-check existing payload first; only the
                // settled stopped state satisfies a stopped Add.
                !request.startNow && observed.runState != ClientRunState.STOPPED -> "Checking existing data"
                else -> null
            }
        }
    }

    suspend fun setStarted(hash: String, started: Boolean): ClientOperationOutcome =
        mutateExisting(hash, ClientCapability.START_STOP, { client.setStarted(hash, started) }) { observed ->
            when {
                observed == null -> "Transfer disappeared"
                started && observed.runState == ClientRunState.STOPPED -> "Still stopped"
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
