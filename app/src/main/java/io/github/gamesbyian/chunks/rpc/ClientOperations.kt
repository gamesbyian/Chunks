package io.github.gamesbyian.chunks.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Result of one user-authorized operation on one exact client + hash.
 * Requested/acknowledged is never success: only [Verified] means a fresh
 * exact-hash readback observed the requested postcondition.
 */
internal sealed interface ClientOperationOutcome {
    val hash: String

    data class Verified(override val hash: String, val observed: ClientTorrent?) : ClientOperationOutcome
    /** Write acknowledged; readback has not (yet) shown the postcondition. */
    data class Pending(override val hash: String, val observed: ClientTorrent?, val reason: String,
        /** Independently observed job absence; consequential payload deletion remains unverified. */
        val registrationAbsent: Boolean = false) :
        ClientOperationOutcome
    /** Delivery unknown. Refresh read-only; never automatically repeat. */
    data class Uncertain(override val hash: String, val reason: String) : ClientOperationOutcome
    /** Nothing was written (precondition, capability or definite refusal). */
    data class Refused(override val hash: String, val reason: String) : ClientOperationOutcome
}

/**
 * Optional caller-owned mutation lease. Called only after shared preflight,
 * immediately before entering an adapter write. Adapter-specific additional
 * checks may still refuse; crossing this boundary means a write is possible,
 * not that the remote daemon received it.
 */
internal class ClientOperationWriteBoundary(private val check: () -> Unit) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ClientOperationWriteBoundary>
    fun beforeWrite() = check()
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

    suspend fun add(
        request: ClientAddRequest,
        reviewedDefaultDirectory: String? = null,
        beforeAdd: suspend () -> String? = { null }
    ): ClientOperationOutcome {
        val expectedDirectory = request.directory ?: reviewedDefaultDirectory
        return if (request.hasFileChoices) addWithFileChoices(request, expectedDirectory, beforeAdd)
        else addOnce(request, expectedDirectory, beforeAdd)
    }

    /**
     * Reviewed file choices (skips and Low/High) for every engine, one gated
     * step at a time: stopped Add (verified) → the registered job's own layout
     * must match the reviewed index AND path → ONE priorities write (verified)
     * → the requested Start. A level the client cannot store is refused before
     * any write, never silently mapped. A failure after registration leaves the
     * job stopped and is reported, never replayed; Start is never sent unless
     * the choices were verified.
     */
    private suspend fun addWithFileChoices(
        request: ClientAddRequest,
        expectedDirectory: String?,
        beforeAdd: suspend () -> String?
    ): ClientOperationOutcome {
        val hash = request.source.infoHash
        val source = request.source as? ClientAddSource.TorrentFile
            ?: return ClientOperationOutcome.Refused(hash, "File choices need reviewed metainfo")
        val reviewed = request.reviewedChoices
        val paths = reviewed.mapValues { (_, choice) -> choice.path }
        if (!ClientFileLayout.reviewMatches(source.preview, paths))
            return ClientOperationOutcome.Refused(hash, "File choices no longer match the reviewed metainfo; nothing was added")
        val session = try { session() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return ClientOperationOutcome.Refused(hash, "Client session unavailable") }
        val caps = session.capabilities
        listOf(ClientCapability.ADD_STOPPED, ClientCapability.FILE_PRIORITIES).firstOrNull { it !in caps }?.let {
            return ClientOperationOutcome.Refused(hash, "Client does not support ${it.name} (needed for file choices)")
        }
        request.filePriorities.values.map { it.priority }.firstOrNull { it !in session.filePriorities }?.let {
            return ClientOperationOutcome.Refused(hash,
                "This client cannot store ${it.name.lowercase()} file priority; nothing was added")
        }
        if (ClientCapability.ADD_WITH_FILE_CHOICES in caps) {
            // The client applies the choices inside its own Add; verify them here.
            val added = addOnce(request, expectedDirectory, beforeAdd)
            if (added is ClientOperationOutcome.Refused || added is ClientOperationOutcome.Uncertain) return added
            return if (choicesObserved(hash, source.preview, reviewed)) added
            else ClientOperationOutcome.Pending(hash, (added as? ClientOperationOutcome.Verified)?.observed,
                "Added; the file choices were not confirmed")
        }
        val added = addOnce(request.copy(startNow = false, skippedFiles = emptyMap(), filePriorities = emptyMap()),
            expectedDirectory, beforeAdd)
        if (added !is ClientOperationOutcome.Verified) return added
        val files = try { client.files(hash) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        files?.let { ClientFileLayout.match(it, source.preview, paths) }
            ?: return ClientOperationOutcome.Pending(hash, added.observed,
                "Added stopped; its file list did not match the review, so no file choices were applied and it was not started")
        val applied = setFilePrioritiesChecked(hash, reviewed.mapValues { (_, choice) -> choice.priority },
            layoutValid = { ClientFileLayout.match(it, source.preview, paths) != null })
        if (applied !is ClientOperationOutcome.Verified) return when (applied) {
            is ClientOperationOutcome.Uncertain -> applied
            else -> ClientOperationOutcome.Pending(hash, added.observed,
                "Added stopped; the file choices were not confirmed, so it was not started")
        }
        if (!request.startNow) return ClientOperationOutcome.Verified(hash, added.observed)
        return when (val started = setStarted(hash, true, expectedDirectory)) {
            is ClientOperationOutcome.Refused ->
                ClientOperationOutcome.Pending(hash, added.observed, "Added stopped with file choices; Start was refused")
            else -> started
        }
    }

    private suspend fun choicesObserved(hash: String, preview: TorrentMetainfoPreview,
        reviewed: Map<Int, ReviewedFilePriority>): Boolean {
        repeat(readbackAttempts) { attempt ->
            if (attempt > 0) delay(readbackDelayMillis)
            val files = try { client.files(hash) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null } ?: return@repeat
            ClientFileLayout.match(files, preview, reviewed.mapValues { (_, choice) -> choice.path }) ?: return false
            val byIndex = files.associateBy { it.index }
            if (reviewed.all { (index, choice) -> byIndex[index]?.priority == choice.priority }) return true
        }
        return false
    }

    private suspend fun addOnce(
        request: ClientAddRequest,
        expectedDirectory: String?,
        beforeAdd: suspend () -> String?
    ): ClientOperationOutcome {
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
        beforeAdd()?.let { return ClientOperationOutcome.Refused(hash, it) }
        write(hash) { client.add(request) }?.let { return it }
        val outcome = readback(hash) { observed ->
            when {
                observed == null -> "Not yet registered"
                expectedDirectory != null && !ClientHashes.sameDirectory(observed.downloadDirectory, expectedDirectory) ->
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
                is Read.Present -> {
                    val observed = read.torrent
                    if (expectedDirectory != null && !ClientHashes.sameDirectory(observed.downloadDirectory, expectedDirectory))
                        return ClientOperationOutcome.Pending(hash, observed, "Registered in a different directory")
                    if (observed.runState != ClientRunState.STOPPED)
                        return ClientOperationOutcome.Pending(hash, observed,
                            "Registered stopped, then the client changed it to ${observed.runState.name.lowercase()}")
                }
                Read.Absent -> return ClientOperationOutcome.Pending(hash, null, "Registered, then no longer listed")
                is Read.Failed -> Unit
            }
        }
        return outcome
    }

    suspend fun setStarted(hash: String, started: Boolean, expectedDirectory: String? = null): ClientOperationOutcome =
        mutateExisting(hash, ClientCapability.START_STOP, { client.setStarted(hash, started) }, expectedDirectory) { observed ->
            when {
                observed == null -> "Transfer disappeared"
                expectedDirectory != null && !ClientHashes.sameDirectory(observed.downloadDirectory, expectedDirectory) ->
                    "Registered in a different directory"
                started && observed.runState.started != true -> "Start not yet observed"
                !started && observed.runState != ClientRunState.STOPPED -> "Still running"
                else -> null
            }
        }

    suspend fun remove(hash: String, deleteData: Boolean): ClientOperationOutcome =
        removeChecked(hash, deleteData) { client.remove(hash, deleteData) }

    /** Delete payload only while the exact job still points at the person's reviewed folder. */
    suspend fun removeReviewedPayload(hash: String, reviewedDirectory: String): ClientOperationOutcome =
        removeChecked(hash, deleteData = true, expectedDirectory = ClientHashes.requireDirectory(reviewedDirectory),
            sharedPayloadGuard = !client.provesNoSharedPayloadBeforeDelete) {
            client.remove(hash, deleteData = true)
        }

    suspend fun removeJob(hash: String, beforeWrite: () -> Unit): ClientOperationOutcome {
        val context = currentCoroutineContext()
        var dispatched = false
        val outcome = removeChecked(hash, deleteData = false, dispatchInAdapter = true) {
            client.removeJob(hash) {
                context.ensureActive()
                context[RemoteWriteOwnerFence]?.ensureCurrent()
                beforeWrite()
                // A protocol challenge can require a second checked dispatch
                // after the first request was explicitly rejected.
                if (!dispatched) {
                    context[ClientOperationWriteBoundary]?.beforeWrite()
                    dispatched = true
                }
            }
        }
        return if (!dispatched && outcome is ClientOperationOutcome.Uncertain)
            ClientOperationOutcome.Refused(outcome.hash, "Could not verify the source before removing its job")
        else outcome
    }

    private suspend fun removeChecked(
        hash: String, deleteData: Boolean, dispatchInAdapter: Boolean = false,
        expectedDirectory: String? = null, sharedPayloadGuard: Boolean = false, send: suspend () -> Unit
    ): ClientOperationOutcome {
        val result = mutateExisting(hash, if (deleteData) ClientCapability.REMOVE_WITH_DATA else ClientCapability.REMOVE,
            send, expectedDirectory = expectedDirectory, dispatchInAdapter = dispatchInAdapter,
            sharedPayloadGuard = sharedPayloadGuard
        ) { observed ->
            if (observed == null) null else "Still registered"
        }
        // Registration absence proves job removal, not filesystem cleanup.
        // Local's adapter independently verifies the exact payload before a
        // normal return. An ordinary remote ACK has no such authority.
        return if (deleteData && result is ClientOperationOutcome.Verified &&
            !client.verifiesPayloadDeletionOnReturn)
            ClientOperationOutcome.Pending(result.hash, null,
                "Downloaded-file deletion is not independently verified", registrationAbsent = true)
        else result
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
    suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>): ClientOperationOutcome =
        setFilePrioritiesChecked(hash, priorities, layoutValid = { true })

    /** A Details edit remains bound to the roster and touched priorities shown in its review. */
    suspend fun setReviewedFilePriorities(
        hash: String, reviewed: List<ClientFile>, priorities: Map<Int, ClientFilePriority>
    ): ClientOperationOutcome {
        val expected = reviewed.associateBy { it.index }
        return setFilePrioritiesChecked(hash, priorities,
            layoutValid = { ClientFileLayout.sameRoster(reviewed, it) },
            precondition = { rows ->
                rows.all { it.index !in priorities || it.priority == expected.getValue(it.index).priority }
            })
    }

    internal suspend fun setFilePrioritiesChecked(
        hash: String,
        priorities: Map<Int, ClientFilePriority>,
        layoutValid: (List<ClientFile>) -> Boolean,
        precondition: (List<ClientFile>) -> Boolean = { true }
    ): ClientOperationOutcome {
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
        if (!ClientFileLayout.sameRoster(before, before) || !known.containsAll(requested.keys) ||
            !layoutValid(before) || !precondition(before))
            return ClientOperationOutcome.Refused(exact, "File list changed; refresh first")
        write(exact) {
            client.setFilePriorities(exact, requested) { rows ->
                ClientFileLayout.sameRoster(before, rows) && layoutValid(rows) && precondition(rows)
            }
        }?.let { return it }
        repeat(readbackAttempts) { attempt ->
            if (attempt > 0) delay(readbackDelayMillis)
            val files = try { client.files(exact) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            if (files != null && ClientFileLayout.sameRoster(before, files) && layoutValid(files)) {
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
        expectedDirectory: String? = null,
        dispatchInAdapter: Boolean = false,
        /** Prove read-only, after the exact pre-read and before any write, that no sibling job shares this payload. */
        sharedPayloadGuard: Boolean = false,
        unmet: (ClientTorrent?) -> String?
    ): ClientOperationOutcome {
        val exact = ClientHashes.requireHash(hash)
        val caps = try { session().capabilities } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return ClientOperationOutcome.Refused(exact, "Client session unavailable") }
        if (capability !in caps)
            return ClientOperationOutcome.Refused(exact, "Client does not support ${capability.name}")
        when (val pre = exactRead(exact)) {
            is Read.Failed -> return ClientOperationOutcome.Refused(exact, "Could not verify the transfer first")
            Read.Absent -> return ClientOperationOutcome.Refused(exact, "Transfer is not in this client")
            is Read.Present -> {
                if (expectedDirectory != null &&
                    !ClientHashes.sameDirectory(pre.torrent.downloadDirectory, expectedDirectory))
                    return ClientOperationOutcome.Refused(exact, "Transfer is no longer in the reviewed folder")
                if (sharedPayloadGuard)
                    ClientPayloadOverlap.refusal(client, pre.torrent)?.let { return ClientOperationOutcome.Refused(exact, it) }
            }
        }
        write(exact, dispatchInAdapter, send)?.let { return it }
        return readback(exact, unmet)
    }

    /** One write; returns a terminal outcome on failure, null when acknowledged. */
    private suspend fun write(
        hash: String, dispatchInAdapter: Boolean = false, send: suspend () -> Unit
    ): ClientOperationOutcome? = try {
        val context = currentCoroutineContext()
        context.ensureActive()
        RemoteWriteOwnerFence.check()
        if (!dispatchInAdapter) context[ClientOperationWriteBoundary]?.beforeWrite()
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
 * Exact v1 file layout, anchored to immutable reviewed metainfo. Clients may
 * omit the ONE declared torrent root, but never arbitrary suffix components,
 * and may omit BEP 47 padding (Local does). No duplicate, unexpected or
 * missing payload indices; all observed byte lengths must match.
 */
internal object ClientFileLayout {
    fun sameRoster(expected: List<ClientFile>, observed: List<ClientFile>): Boolean {
        if (expected.size != observed.size) return false
        val byIndex = expected.associateBy { it.index }
        if (byIndex.size != expected.size || observed.map { it.index }.toSet().size != observed.size)
            return false
        return observed.all { row ->
            val reviewed = byIndex[row.index]
            reviewed != null && row.index >= 0 && row.path.isNotBlank() && row.sizeBytes >= 0 &&
                reviewed.path == row.path && reviewed.sizeBytes == row.sizeBytes
        }
    }

    private fun declared(preview: TorrentMetainfoPreview): List<TorrentMetainfoFile> =
        preview.files.ifEmpty { listOf(TorrentMetainfoFile(preview.name, preview.sizeBytes, false)) }

    private fun pathFor(preview: TorrentMetainfoPreview, file: TorrentMetainfoFile, rooted: Boolean): String =
        if (rooted || preview.files.isEmpty()) file.path else file.path.removePrefix(preview.name + "/")

    fun reviewMatches(preview: TorrentMetainfoPreview, reviewed: Map<Int, String>): Boolean {
        val expected = declared(preview)
        return reviewed.all { (index, path) ->
            val file = expected.getOrNull(index) ?: return@all false
            !file.padding && (path == file.path || path == pathFor(preview, file, rooted = false))
        }
    }

    fun match(clientFiles: List<ClientFile>, preview: TorrentMetainfoPreview,
        reviewed: Map<Int, String>): List<Int>? {
        if (!reviewMatches(preview, reviewed)) return null
        val expected = declared(preview)
        val byIndex = clientFiles.associateBy { it.index }
        if (byIndex.size != clientFiles.size || expected.indices.any { !expected[it].padding && it !in byIndex })
            return null
        // One coherent root convention for the whole observed list. Do not
        // accept a mixture of rooted and relative rows or a renamed path.
        val matches = listOf(true, false).any { rooted ->
            clientFiles.all { row ->
                val file = expected.getOrNull(row.index) ?: return@all false
                row.path == pathFor(preview, file, rooted) && row.sizeBytes == file.sizeBytes
            }
        }
        return if (matches) reviewed.keys.toList() else null
    }
}
