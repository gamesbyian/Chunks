package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientFinishInOrderPort
import io.github.gamesbyian.chunks.rpc.ClientJobTransfer
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.ClientHashes
import io.github.gamesbyian.chunks.rpc.ClientOperationWriteBoundary
import io.github.gamesbyian.chunks.rpc.RemoteWriteOwnerFence
import io.github.gamesbyian.chunks.rpc.ClientRefusedException
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import io.github.gamesbyian.chunks.model.SmartRoutingAddSuggestion
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Another connected client a job may be copied or moved to. */
internal data class ProtocolMoveTarget(
    val id: String,
    val label: String,
    /** The target's exact Chunks display-alias scope; null when it is not privacy-visible. */
    val aliasScope: String? = null,
    /** Stable for the current authenticated connection, even across a label change. */
    val connectionWitness: Any = id,
    val ownerCurrent: () -> Boolean = { true },
    val createOperations: () -> ClientOperations
)

private data class VerifiedMoveCopy(
    val target: ProtocolMoveTarget,
    val transfer: ClientJobTransfer,
    val receipt: ClientJobTransfer.CopyResult.Copied
)

private data class FinishInOrderAction(val lease: AutoCloseable, val ownerCurrent: () -> Boolean)

/** Plain-language, credential-free outcome text shared by every protocol. */
internal object ProtocolOperationText {
    fun describe(action: String, outcome: ClientOperationOutcome, clientLabel: String): String = when (outcome) {
        is ClientOperationOutcome.Verified -> "$action: confirmed on $clientLabel."
        is ClientOperationOutcome.Pending -> if (outcome.registrationAbsent)
            "Transfer removed from $clientLabel. Downloaded-file deletion was requested but could not be independently verified. Check the files on the server."
        else "$action: requested, not yet confirmed (" +
            outcome.reason.lowercase() + "). Refresh to check."
        is ClientOperationOutcome.Uncertain -> "$action: the reply was lost, so the result is unknown. " +
            "Refresh before trying again; nothing was retried."
        is ClientOperationOutcome.Refused -> "$action: not changed. " + outcome.reason + "."
    }
}

/**
 * Details operations for one exact Transmission / qBittorrent / Deluge
 * transfer. Every command goes through the shared [ClientOperations]
 * workflow (exact precondition, one write, exact readback). Offered actions
 * come from the client's own session capabilities. An uncertain result
 * holds further commands until an explicit exact read reconciles the receipt.
 */
@Composable
internal fun ProtocolClientOperationsPanel(
    hash: String,
    clientLabel: String,
    /** Stable authenticated client identity, never a display label. */
    sourceClientId: String,
    observedStarted: Boolean?,
    /** The list row is current, complete and privacy-visible. */
    current: Boolean,
    /** Exact connection generation, or the process-owned Local singleton. */
    sourceGeneration: String,
    createOperations: () -> ClientOperations,
    onChanged: () -> Unit,
    /** Other connected clients this job may be copied/moved to (job only). */
    moveTargets: List<ProtocolMoveTarget> = emptyList(),
    /** Authorized destination connections can outlive a transient inventory refresh. */
    moveConnections: Map<String, Any> = moveTargets.associate { it.id to it.connectionWitness },
    /** The rTorrent Details surface already owns its verified lifecycle/file controls. */
    moveOnly: Boolean = false,
    /** Smart Routing's advisory Move suggestion (target id to "why"); never acts by itself. */
    suggestedTarget: Pair<String, String>? = null,
    routingMetadataPending: Boolean? = null,
    /**
     * The same advisory rule evaluation with the job's own file paths, read once
     * (read-only) when Copy/Move is opened; replaces the name-only [suggestedTarget].
     */
    suggestForFiles: ((List<String>) -> Pair<String, String>?)? = null,
    /** The source's exact Chunks display-alias scope; null when none applies. */
    sourceAliasScope: String? = null,
    /** Privacy-visible, live exact client; the job itself may have disappeared. */
    canReconcile: Boolean = current,
    receiptOwner: ProtocolOperationReceiptOwner = ProtocolOperationReceipts.owner,
    isOwnerCurrent: () -> Boolean = { true }
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val operations = remember(hash, sourceClientId, sourceGeneration, createOperations) { createOperations() }
    val receiptKey = remember(hash, sourceClientId) {
        runCatching { ClientHashes.requireHash(hash) }.getOrNull()
            ?.let { ProtocolOperationReceiptOwner.Key(sourceClientId, it) }
    }
    val inFlightReceipt = remember(receiptOwner, receiptKey, sourceGeneration) {
        java.util.concurrent.atomic.AtomicReference<ProtocolOperationReceiptOwner.Ticket?>()
    }
    DisposableEffect(inFlightReceipt) {
        onDispose {
            // Prevent a detached preflight from writing. A possible dispatch
            // stays in flight until its coroutine actually unwinds.
            inFlightReceipt.getAndSet(null)?.let(receiptOwner::abandonPreflight)
        }
    }
    val retained = receiptKey?.let(receiptOwner::receipt)
    var session by remember(operations) { mutableStateOf<ClientSessionInfo?>(null) }
    var sessionError by remember(operations) { mutableStateOf<String?>(null) }
    var busy by remember(hash, sourceClientId, sourceGeneration) { mutableStateOf(false) }
    var notice by remember(hash, sourceClientId, sourceGeneration) { mutableStateOf<String?>(null) }
    val latched = retained?.blocksWrites == true
    var confirmRemove by remember(hash, sourceClientId, sourceGeneration) { mutableStateOf(false) }
    var deleteData by remember(hash, sourceClientId, sourceGeneration) { mutableStateOf(false) }
    var files by remember(hash, sourceClientId, sourceGeneration) { mutableStateOf<List<ClientFile>?>(null) }
    var showMove by remember(hash, sourceClientId, sourceGeneration) { mutableStateOf(false) }
    // Retain source evidence for this authenticated job. Destination eligibility
    // can change while the panel is open, so derive the advice from current inputs.
    var routingFileNames by remember(operations, hash, routingMetadataPending) {
        mutableStateOf<List<String>?>(null)
    }
    val inspectRoutingFiles = current && showMove && suggestForFiles != null && routingMetadataPending != true
    LaunchedEffect(operations, hash, routingMetadataPending, inspectRoutingFiles) {
        if (!inspectRoutingFiles || routingFileNames != null) return@LaunchedEffect
        val names = try { filesOf(operations, hash).map { it.path } }
            catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        if (!names.isNullOrEmpty()) routingFileNames = names
    }
    val proposedMove = if (!current || !showMove || routingMetadataPending == true) null
        else routingFileNames?.let { suggestForFiles?.invoke(it) }
            ?: if (routingFileNames == null) suggestedTarget else null
    val proposedDestination = moveTargets.singleOrNull { it.id == proposedMove?.first }
    val destinationId = proposedDestination?.id
    val destinationConnection = proposedDestination?.connectionWitness
    val startOnDestination = observedStarted == true
    var verifiedMoveDestination by remember(destinationId, destinationConnection, startOnDestination) {
        mutableStateOf(false)
    }
    LaunchedEffect(destinationId, destinationConnection, startOnDestination) {
        val destination = proposedDestination ?: return@LaunchedEffect
        val destinationSession = readRoutingTarget { destination.createOperations().session(refresh = true) }
        // Copy prefers original metainfo and can fall back to a public magnet.
        // Check both formats without exporting private source data for an advisory.
        val file = SmartRoutingAddSuggestion.candidateFor(destination.id, destination.label, destinationSession,
            magnetSource = false, startNow = startOnDestination)
        val magnet = SmartRoutingAddSuggestion.candidateFor(destination.id, destination.label, destinationSession,
            magnetSource = true, startNow = startOnDestination)
        verifiedMoveDestination = file.live && file.placeKnown && file.acceptsAdd && magnet.acceptsAdd
    }
    val routingSuggestion = proposedMove?.takeIf { verifiedMoveDestination }
    var copied by remember(operations) { mutableStateOf<VerifiedMoveCopy?>(null) }
    val currentConnections by rememberUpdatedState(moveConnections)
    val currentOwner by rememberUpdatedState(operations.takeIf { current })
    val currentReadOwner by rememberUpdatedState(operations.takeIf { canReconcile })
    val completedCopy = copied
    val finishableCopy = completedCopy?.takeIf { copy ->
        moveConnections[copy.target.id] == copy.target.connectionWitness
    }
    if (completedCopy != null && finishableCopy == null) SideEffect {
        if (copied === completedCopy) {
            copied = null
            notice = "Job copied to ${completedCopy.target.label}. The destination connection changed. The source job was kept."
        }
    }
    var draft by remember(hash, sourceClientId, sourceGeneration) { mutableStateOf<Map<Int, ClientFilePriority>>(emptyMap()) }
    // Change folder: the client's own set-location, verified by exact readback.
    var relocating by remember(hash, sourceClientId, sourceGeneration) { mutableStateOf(false) }
    var relocateTo by remember(hash, sourceClientId, sourceGeneration) { mutableStateOf("") }
    var moveFiles by remember(hash, sourceClientId, sourceGeneration) { mutableStateOf(false) }
    var currentFolder by remember(hash, sourceClientId, sourceGeneration) { mutableStateOf<String?>(null) }

    // Move-only (the owning Details screen already has the lifecycle controls)
    // reads the client session lazily, on the first Copy/Move tap: opening
    // Details must not cost an extra server round trip on every view.
    var sessionWanted by remember(hash, sourceClientId, sourceGeneration) { mutableStateOf(!moveOnly) }
    LaunchedEffect(operations, sessionWanted) {
        if (!sessionWanted || session != null) return@LaunchedEffect
        try {
            session = operations.session()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            sessionError = "Controls unavailable: " + RemoteProtocolConnect.explain(failure)
        }
    }

    fun run(action: String, kind: ProtocolOperationKind, block: suspend () -> ClientOperationOutcome) {
        if (busy || latched || !current || !isOwnerCurrent()) return
        val key = receiptKey ?: return
        val ticket = receiptOwner.begin(key, sourceGeneration, kind)
        if (ticket == null) {
            notice = "A previous request needs checking before another command."
            return
        }
        inFlightReceipt.set(ticket)
        val policyEpoch = TransferPrivacyPolicyStore.revision
        val lifecycleEpoch = TransferPrivacyRuntime.lifecycleRevision
        val credentialEpoch = RemoteCredentialLock.capture()
        val ownerCurrent = {
            (moveOnly || currentOwner === operations) && isOwnerCurrent() &&
                RemoteCredentialLock.current(credentialEpoch) &&
                policyEpoch == TransferPrivacyPolicyStore.revision &&
                lifecycleEpoch == TransferPrivacyRuntime.lifecycleRevision
        }
        busy = true
        notice = "$action…"
        // Start the structured body before returning from the click, so even
        // cancellation before its first suspension settles the acquired lease.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val outcome = try {
                    withContext(RemoteWriteOwnerFence(ownerCurrent) + ClientOperationWriteBoundary {
                        if (!ownerCurrent() || !receiptOwner.possibleWrite(ticket))
                            throw ClientRefusedException("The selected connection changed; nothing was written")
                    }) { block() }
                } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { ClientOperationOutcome.Uncertain(hash, "unexpected failure") }
                receiptOwner.complete(ticket, outcome)
                notice = ProtocolOperationText.describe(action, outcome, clientLabel)
                if (isOwnerCurrent() && (moveOnly || currentOwner === operations || currentReadOwner === operations))
                    onChanged()
            } finally {
                receiptOwner.cancel(ticket)
                inFlightReceipt.compareAndSet(ticket, null)
                busy = false
            }
        }
    }

    fun reconcile() {
        val receipt = retained ?: return
        if (busy || !canReconcile || !receipt.canReconcile || !isOwnerCurrent()) return
        val policyEpoch = TransferPrivacyPolicyStore.revision
        val lifecycleEpoch = TransferPrivacyRuntime.lifecycleRevision
        val credentialEpoch = RemoteCredentialLock.capture()
        busy = true
        scope.launch {
            try {
                val row = operations.client.torrent(hash)
                if (currentReadOwner !== operations || !isOwnerCurrent() ||
                    !RemoteCredentialLock.current(credentialEpoch) ||
                    policyEpoch != TransferPrivacyPolicyStore.revision ||
                    lifecycleEpoch != TransferPrivacyRuntime.lifecycleRevision) return@launch
                if (row != null && !row.hash.equals(hash, ignoreCase = true))
                    throw java.io.IOException("Wrong transfer identity")
                if (receiptOwner.reconcile(receipt.ticket, row != null)) {
                    notice = null
                    onChanged()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                notice = "Could not read this transfer. The previous request remains unresolved."
            } finally {
                busy = false
            }
        }
    }

    fun captureCopyOwner(target: ProtocolMoveTarget): () -> Boolean {
        val policyEpoch = TransferPrivacyPolicyStore.revision
        val lifecycleEpoch = TransferPrivacyRuntime.lifecycleRevision
        val credentialEpoch = RemoteCredentialLock.capture()
        return {
            scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true &&
                currentOwner === operations && isOwnerCurrent() && target.ownerCurrent() &&
                currentConnections[target.id] == target.connectionWitness &&
                RemoteCredentialLock.current(credentialEpoch) &&
                policyEpoch == TransferPrivacyPolicyStore.revision &&
                lifecycleEpoch == TransferPrivacyRuntime.lifecycleRevision
        }
    }

    fun reconcileCopyTarget(target: ProtocolMoveTarget, receipt: ProtocolOperationReceiptOwner.Receipt) {
        val ownerCurrent = captureCopyOwner(target)
        if (busy || !receipt.canReconcile || !ownerCurrent()) return
        busy = true
        scope.launch {
            try {
                val row = target.createOperations().client.torrent(hash)
                if (!ownerCurrent()) return@launch
                if (row != null && !row.hash.equals(hash, ignoreCase = true))
                    throw java.io.IOException("Wrong transfer identity")
                if (receiptOwner.reconcile(receipt.ticket, row != null)) onChanged()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                notice = "Could not check the destination job. Its previous Copy result remains unresolved."
            } finally {
                busy = false
            }
        }
    }

    val caps = session?.capabilities.orEmpty()
    val enabled = current && !busy && !latched && session != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(ChunksSemantics.PROTOCOL_OPERATIONS)) {
        Text(if (moveOnly) "Copy or move from $clientLabel" else "Controls on $clientLabel",
            style = MaterialTheme.typography.titleSmall)
        sessionError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ChunksColors.Yellow) }
        if (!current) Text("The transfer list is out of date or locked. Refresh before changing this transfer.",
            style = MaterialTheme.typography.bodySmall)
        if (!moveOnly) ResponsiveActionGroup(maxColumns = 2) { cell ->
            if (ClientCapability.START_STOP in caps) {
                val start = observedStarted != true
                OutlinedButton(
                    onClick = { run(if (start) "Start" else "Stop", if (start) ProtocolOperationKind.START else ProtocolOperationKind.STOP) { operations.setStarted(hash, start) } },
                    enabled = enabled && observedStarted != null,
                    modifier = cell.heightIn(min = 48.dp).testTag(ChunksSemantics.PROTOCOL_START_STOP)
                ) { Text(if (start) "Start" else "Stop") }
            }
            if (ClientCapability.RECHECK in caps) OutlinedButton(
                onClick = { run("Recheck", ProtocolOperationKind.RECHECK) { operations.recheck(hash) } },
                enabled = enabled, modifier = cell.heightIn(min = 48.dp)
            ) { Text("Recheck") }
            if (ClientCapability.FILE_PRIORITIES in caps) OutlinedButton(
                onClick = {
                    if (files != null) { files = null; draft = emptyMap() } else if (!busy) {
                        busy = true
                        scope.launch {
                            files = try {
                                // Files are an explicit, bounded read of the exact hash.
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    filesOf(operations, hash)
                                }
                            } catch (cancel: CancellationException) { throw cancel } catch (failure: Exception) {
                                notice = "Files: " + RemoteProtocolConnect.explain(failure); null
                            }
                            draft = files.orEmpty().associate { it.index to it.priority }
                            busy = false
                        }
                    }
                },
                enabled = current && !busy, modifier = cell.heightIn(min = 48.dp)
            ) { Text(if (files == null) "Files" else "Hide files") }
            if (ClientCapability.SET_LOCATION in caps) OutlinedButton(
                onClick = {
                    relocating = !relocating
                    moveFiles = false
                    if (relocating) scope.launch {
                        // The exact job's own reported folder, read just now.
                        currentFolder = try { operations.client.torrent(hash)?.downloadDirectory }
                            catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                    }
                },
                enabled = enabled, modifier = cell.heightIn(min = 48.dp)
                    .testTag(ChunksSemantics.PROTOCOL_SET_LOCATION)
            ) { Text(if (relocating) "Cancel" else "Change folder…") }
            if (ClientCapability.REMOVE in caps) OutlinedButton(
                onClick = { confirmRemove = !confirmRemove; deleteData = false },
                enabled = enabled, modifier = cell.heightIn(min = 48.dp)
                    .testTag(ChunksSemantics.PROTOCOL_REMOVE)
            ) { Text(if (confirmRemove) "Cancel remove" else "Remove…") }
        }
        if (moveTargets.isNotEmpty() && (ClientCapability.EXPORT_JOB in caps || (moveOnly && session == null))) {
            OutlinedButton(onClick = { showMove = !showMove; sessionWanted = true },
                enabled = current && !busy && !latched && (session != null || moveOnly),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag(ChunksSemantics.PROTOCOL_MOVE)) {
                Text(if (showMove) "Hide copy or move" else "Copy or move job to another client…")
            }
        }
        if (showMove) {
            Text("Copies only the torrent job. The other client downloads its files itself; " +
                "nothing on $clientLabel is changed unless you then remove it here.",
                style = MaterialTheme.typography.bodySmall)
            routingSuggestion?.let { (id, why) ->
                moveTargets.firstOrNull { it.id == id }?.let { target ->
                    Text("Smart Routing suggests ${target.label} ($why). Nothing moves until you copy and finish.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(ChunksSemantics.PROTOCOL_MOVE_SUGGESTION))
                }
            }
            val copyHash = runCatching { ClientHashes.requireHash(hash) }.getOrNull()
            if (copyHash == null) {
                Text("Copying this job requires a v1 transfer identity.", style = MaterialTheme.typography.bodySmall)
            }
            moveTargets.sortedByDescending { it.id == routingSuggestion?.first }.forEach { target ->
                val targetKey = copyHash?.let { ProtocolOperationReceiptOwner.Key(target.id, it) }
                val targetReceipt = targetKey?.let(receiptOwner::receipt)
                OutlinedButton(onClick = {
                    val key = targetKey ?: return@OutlinedButton
                    val ownerCurrent = captureCopyOwner(target)
                    if (busy || latched || !ownerCurrent()) return@OutlinedButton
                    val generation = RemoteCredentialLock.capture().toString()
                    val ticket = receiptOwner.begin(key, generation, ProtocolOperationKind.COPY_TARGET)
                        ?: return@OutlinedButton
                    inFlightReceipt.set(ticket)
                    busy = true
                    copied = null
                    notice = "Copying job to ${target.label}…"
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            val transfer = try {
                                val dst = target.createOperations()
                                ClientJobTransfer(operations, operations.client, dst, dst.client,
                                    sourceClientId = sourceClientId, targetClientId = target.id)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                null
                            }
                            val result = try {
                                if (transfer == null) {
                                    ClientJobTransfer.CopyResult.Refused(hash, "Could not open the destination")
                                } else withContext(
                                    RemoteWriteOwnerFence(ownerCurrent) + ClientOperationWriteBoundary {
                                        if (!ownerCurrent() || !receiptOwner.possibleWrite(ticket))
                                            throw ClientRefusedException("The selected connection changed")
                                    }
                                ) {
                                    transfer.copy(hash, targetDirectory = null, startOnTarget = observedStarted == true,
                                        stillAuthorized = ownerCurrent)
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                ClientJobTransfer.CopyResult.NotCopied(hash,
                                    ClientOperationOutcome.Uncertain(hash, "The destination result could not be confirmed"))
                            }
                            val outcome = when (result) {
                                is ClientJobTransfer.CopyResult.Copied -> ClientOperationOutcome.Verified(hash, null)
                                is ClientJobTransfer.CopyResult.NotCopied -> result.outcome
                                is ClientJobTransfer.CopyResult.Refused -> ClientOperationOutcome.Refused(hash, result.reason)
                            }
                            receiptOwner.complete(ticket, outcome)
                            when (result) {
                                is ClientJobTransfer.CopyResult.Copied -> {
                                    copied = VerifiedMoveCopy(target, requireNotNull(transfer), result)
                                    notice = "Job copied to ${target.label} (confirmed). Its files download there separately."
                                    // Carry the device-local name only for the still-current pair.
                                    val from = sourceAliasScope
                                    val to = target.aliasScope
                                    if (from != null && to != null) {
                                        val carried = withContext(Dispatchers.IO) {
                                            if (!ownerCurrent()) return@withContext TransferAliasStore.MoveAliasCarry.FAILED
                                            runCatching { TransferAliasStore(context).carryVerifiedMoveAlias(from, to, hash) }
                                                .getOrDefault(TransferAliasStore.MoveAliasCarry.FAILED)
                                        }
                                        if (carried == TransferAliasStore.MoveAliasCarry.FAILED)
                                            notice = notice + " Its Chunks display name was not copied."
                                    }
                                }
                                else -> notice = ProtocolOperationText.describe("Copy to ${target.label}", outcome, target.label)
                            }
                            if (ownerCurrent()) onChanged()
                        } finally {
                            receiptOwner.cancel(ticket)
                            inFlightReceipt.compareAndSet(ticket, null)
                            busy = false
                        }
                    }
                }, enabled = enabled && targetKey != null && targetReceipt?.blocksWrites != true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag(ChunksSemantics.PROTOCOL_MOVE + "." + target.id)) {
                    Text("Copy job to ${target.label}")
                }
                if (targetReceipt != null) {
                    Text(targetReceipt.description(), style = MaterialTheme.typography.bodySmall)
                    if (targetReceipt.canReconcile) OutlinedButton(
                        onClick = { reconcileCopyTarget(target, targetReceipt) },
                        enabled = current && !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Check job on ${target.label}") }
                }
            }
            if (finishableCopy != null) Button(onClick = {
                run("Remove from $clientLabel after moving to ${finishableCopy.target.label}", ProtocolOperationKind.RETIRE_SOURCE) {
                    finishableCopy.transfer.retireSource(hash, finishableCopy.receipt) {
                        (moveOnly || currentOwner === operations) && isOwnerCurrent() && finishableCopy.target.ownerCurrent() &&
                            currentConnections[finishableCopy.target.id] == finishableCopy.target.connectionWitness
                    }
                }
                copied = null
            }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE)) {
                Text("Finish move: remove from $clientLabel (keep files)")
            }
        }
        val shown = files
        if (!moveOnly && shown != null) {
            val levels = session?.filePriorities.orEmpty()
            shown.sortedBy { it.index }.forEach { file ->
                val chosen = draft[file.index] ?: file.priority
                OutlinedButton(
                    onClick = {
                        val order = ClientFilePriority.entries.filter { it == ClientFilePriority.SKIP || it in levels }
                        draft = draft + (file.index to order[(order.indexOf(chosen) + 1) % order.size])
                    },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = file.path.substringAfterLast('/') + ", priority " +
                            chosen.name.lowercase() + ". Tap to change"
                    }
                ) { Text(file.path.substringAfterLast('/') + " · " + priorityLabel(chosen)) }
            }
            val changed = draft.filter { (index, value) -> shown.firstOrNull { it.index == index }?.priority != value }
            Button(
                onClick = {
                    run("File priorities", ProtocolOperationKind.FILE_PRIORITIES) {
                        operations.setReviewedFilePriorities(hash, shown, changed)
                    }
                    files = null
                },
                enabled = enabled && changed.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text("Apply ${changed.size} priority change" + if (changed.size == 1) "" else "s") }
            // Finish in Order through the same shared workflow: one guarded,
            // verified priorities write per step, never retried.
            val levelsNow = session?.filePriorities
            if (levelsNow != null && ClientCapability.FILE_PRIORITIES in caps &&
                shown.none { it.completedBytes == null }) {
                val asLocal = remember(shown) { shown.map(ClientFinishInOrderPort::asPlannerFile) }
                val action = remember(operations, receiptOwner) {
                    java.util.concurrent.atomic.AtomicReference<FinishInOrderAction?>()
                }
                ServerFinishInOrderPanel(
                    hash = hash, scope = FinishInOrderScopes.protocol(sourceClientId), asLocal = asLocal,
                    stale = !current || busy || latched,
                    intro = if (ClientFilePriority.LOW in levelsNow)
                        "On $clientLabel the current file gets High, the next stays Normal and later ones get Low. " +
                            "It moves on while Chunks is open and connected to this client."
                    else "On $clientLabel the current file gets High and the rest stay Normal. " +
                        "It moves on while Chunks is open and connected to this client.",
                    isActive = { index -> shown.firstOrNull { it.index == index }?.priority == ClientFilePriority.HIGH },
                    twoBands = ClientFilePriority.LOW !in levelsNow,
                    step = { store ->
                        action.get()?.let { active ->
                            withContext(RemoteWriteOwnerFence(active.ownerCurrent)) {
                                stepClientFinishInOrder(operations, levelsNow, store, hash, active.ownerCurrent)
                            }
                        }
                    },
                    onMutationStarted = {
                        if (busy || latched || !current || !isOwnerCurrent()) false
                        else {
                            val lease = receiptOwner.reservePolicy(sourceClientId, hash, ProtocolOperationKind.FINISH_IN_ORDER)
                            if (lease == null) false
                            else {
                                val policyEpoch = TransferPrivacyPolicyStore.revision
                                val lifecycleEpoch = TransferPrivacyRuntime.lifecycleRevision
                                val credentialEpoch = RemoteCredentialLock.capture()
                                action.set(FinishInOrderAction(lease) {
                                    currentOwner === operations && isOwnerCurrent() &&
                                        RemoteCredentialLock.current(credentialEpoch) &&
                                        policyEpoch == TransferPrivacyPolicyStore.revision &&
                                        lifecycleEpoch == TransferPrivacyRuntime.lifecycleRevision
                                })
                                busy = true
                                true
                            }
                        }
                    },
                    mutationCurrent = { action.get()?.ownerCurrent?.invoke() == true },
                    onSettled = {
                        action.getAndSet(null)?.lease?.close()
                        busy = false
                        files = null
                        onChanged()
                    })
            }
        }
        if (!moveOnly && relocating) {
            val target = relocateTo.trim()
            val valid = runCatching { io.github.gamesbyian.chunks.rpc.ClientHashes.requireDirectory(target) }.isSuccess &&
                !io.github.gamesbyian.chunks.rpc.ClientHashes.sameDirectory(target, currentFolder)
            Text("Current folder on $clientLabel: " + (currentFolder ?: "not reported"),
                style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.OutlinedTextField(
                value = relocateTo, onValueChange = { relocateTo = it }, singleLine = true,
                label = { Text("New absolute folder on $clientLabel") }, enabled = !busy,
                isError = target.isNotEmpty() && !valid,
                modifier = Modifier.fillMaxWidth().testTag(ChunksSemantics.PROTOCOL_SET_LOCATION_FOLDER))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = moveFiles, enabled = !busy, role = Role.Checkbox, onValueChange = { moveFiles = it })
                .testTag(ChunksSemantics.PROTOCOL_SET_LOCATION_MOVE_FILES),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = moveFiles, onCheckedChange = null)
                Text("Move the downloaded files there too", modifier = Modifier.padding(start = 8.dp))
            }
            Text(if (moveFiles) "$clientLabel itself moves this transfer's files into the new folder. " +
                    "Nothing is deleted by Chunks; check the result before removing anything."
                else "$clientLabel only looks for this transfer's files in the new folder; no files are moved. " +
                    "Use Recheck afterwards if the files are already there.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val dir = target
                relocating = false
                run(if (moveFiles) "Move files to the new folder" else "Change folder",
                    if (moveFiles) ProtocolOperationKind.MOVE_FILES else ProtocolOperationKind.SET_LOCATION) {
                    operations.setLocation(hash, dir, moveFiles)
                }
            }, enabled = enabled && valid, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag(ChunksSemantics.PROTOCOL_SET_LOCATION_CONFIRM)) {
                Text(if (moveFiles) "Move files to this folder" else "Use this folder")
            }
        }
        if (!moveOnly && confirmRemove) {
            Text("Remove this transfer from $clientLabel? By default its downloaded files stay on the server.",
                style = MaterialTheme.typography.bodySmall)
            if (ClientCapability.REMOVE_WITH_DATA in caps) Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = deleteData, enabled = enabled, role = Role.Checkbox,
                    onValueChange = { deleteData = it }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = deleteData, onCheckedChange = null)
                Text("Also delete its downloaded files on the server", modifier = Modifier.padding(start = 8.dp))
            }
            Button(
                onClick = {
                    val withData = deleteData
                    confirmRemove = false
                    run(if (withData) "Remove and delete files" else "Remove",
                        if (withData) ProtocolOperationKind.REMOVE_DATA else ProtocolOperationKind.REMOVE) { operations.remove(hash, withData) }
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag(ChunksSemantics.PROTOCOL_REMOVE_CONFIRM)
            ) { Text(if (deleteData) "Remove and delete files" else "Remove, keep files") }
        }
        val operationStatus = if (retained?.phase == ProtocolOperationReceiptOwner.Phase.POLICY_RUNNING)
            retained.description() else notice ?: retained?.description()
        operationStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(ChunksSemantics.PROTOCOL_OPERATION_STATUS))
        }
        if (retained?.canReconcile == true) {
            Text("Commands are paused until a read-only check.",
                style = MaterialTheme.typography.bodySmall, color = ChunksColors.Yellow)
            OutlinedButton(onClick = ::reconcile, enabled = !busy && canReconcile,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Check this transfer without changing it")
            }
        }
    }
}

private fun priorityLabel(p: ClientFilePriority) = when (p) {
    ClientFilePriority.SKIP -> "Skip"
    ClientFilePriority.LOW -> "Low"
    ClientFilePriority.NORMAL -> "Normal"
    ClientFilePriority.HIGH -> "High"
}

private suspend fun filesOf(operations: ClientOperations, hash: String): List<ClientFile> =
    operations.files(hash)
