package io.github.gamesbyian.chunks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientHashes
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One frozen Add attempt for a Transmission / qBittorrent / Deluge profile.
 * Held by the caller above navigation; an attempted Add is never replayed.
 */
internal class ProtocolAddAttempt {
    var attemptedHash by mutableStateOf<String?>(null)
        private set
    var outcome by mutableStateOf<ClientOperationOutcome?>(null)
        internal set
    var profileId: String? = null
        private set

    val busy: Boolean get() = attemptedHash != null && outcome == null

    /** Claims the single submission; false when another attempt owns it. */
    fun claim(profile: String, hash: String): Boolean {
        if (busy) return false
        attemptedHash = hash
        profileId = profile
        outcome = null
        return true
    }

    fun clearSettled() {
        if (!busy) { attemptedHash = null; outcome = null; profileId = null }
    }
}

/**
 * Source-first review for a remote protocol client: the same draft magnet or
 * reviewed .torrent as every other Add, the client's own default folder (or
 * an exact absolute folder), and the shared Start choice. Submission is one
 * [ClientOperations.add]: exact-absence precondition, ONE write, exact
 * readback of registration, folder and stopped/started state.
 */
@Composable
internal fun ProtocolAddScreen(
    profileId: String,
    clientLabel: String,
    createOperations: () -> ClientOperations,
    destinationPicker: @Composable () -> Unit,
    sourceDraft: MutableState<String>,
    reviewedTorrent: MutableState<PendingLocalAdd?>,
    startState: MutableState<Boolean>,
    attempt: ProtocolAddAttempt,
    attemptScope: CoroutineScope,
    onViewTransfer: (String) -> Unit
) {
    val context = LocalContext.current
    val operations = remember(profileId, createOperations) { createOperations() }
    var session by remember(operations) { mutableStateOf<ClientSessionInfo?>(null) }
    var sessionError by remember(operations) { mutableStateOf<String?>(null) }
    var customFolder by remember(profileId) { mutableStateOf("") }
    var useCustom by remember(profileId) { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(operations) {
        try {
            session = operations.session()
        } catch (cancel: CancellationException) { throw cancel } catch (failure: Exception) {
            sessionError = RemoteProtocolConnect.explain(failure)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        attemptScope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // Bounded read without API 33-only InputStream calls (minSdk 29).
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (out.size() <= TorrentMetainfoReader.MAX_BYTES) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                        val bytes = out.toByteArray()
                        require(bytes.size <= TorrentMetainfoReader.MAX_BYTES) { "This .torrent is too large" }
                        PendingLocalAdd(TorrentMetainfoReader.preview(bytes), bytes)
                    } ?: error("Could not open the document")
                }
            }
            read.onSuccess { reviewedTorrent.value = it; sourceDraft.value = ""; fileError = null }
                .onFailure { fileError = "Not a usable .torrent: " + (it.message?.take(120) ?: "unreadable") }
        }
    }

    val draft = sourceDraft.value.trim()
    val torrent = reviewedTorrent.value
    val source: ClientAddSource? = remember(draft, torrent) {
        when {
            draft.startsWith("magnet:?", ignoreCase = true) ->
                runCatching { ClientAddSource.Magnet(draft) }.getOrNull()
            torrent != null -> runCatching { ClientAddSource.TorrentFile(torrent.bytes) }.getOrNull()
            else -> null
        }
    }
    val sourceInvalid = draft.isNotEmpty() && source == null
    val folder = if (useCustom) customFolder.trim() else null
    val folderValid = folder == null || runCatching { ClientHashes.requireDirectory(folder) }.isSuccess
    val caps = session?.capabilities.orEmpty()
    val supported = source != null && when (source) {
        is ClientAddSource.Magnet -> ClientCapability.ADD_MAGNET in caps
        is ClientAddSource.TorrentFile -> ClientCapability.ADD_TORRENT_FILE in caps
    } && (startState.value || ClientCapability.ADD_STOPPED in caps)
    // Files to download: the same shared component and reviewed-layout rule as
    // every Add; skips belong only to the exact reviewed .torrent identity.
    val layout = remember(torrent) {
        torrent?.let { runCatching { AddFileChoices.declared(it.preview) }.getOrNull() }
    }.takeIf { draft.isEmpty() }
    var skipped by remember(torrent?.preview?.infoHashV1) { mutableStateOf<Set<Int>>(emptySet()) }
    var fileChoiceOpen by remember(torrent?.preview?.infoHashV1) { mutableStateOf(false) }
    val canChooseFiles = ClientCapability.FILE_PRIORITIES in caps && ClientCapability.ADD_STOPPED in caps
    val chosenSkips = if (layout != null && canChooseFiles) skipped.filter { i -> layout.any { it.index == i } }.toSet()
        else emptySet()
    val noFileChosen = layout != null && layout.none { it.index !in chosenSkips }
    val mine = attempt.profileId == null || attempt.profileId == profileId
    val canSubmit = session != null && source != null && supported && folderValid && !attempt.busy && mine &&
        !noFileChosen

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Add a transfer", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        // 1. Source first.
        OutlinedTextField(
            value = sourceDraft.value,
            onValueChange = { sourceDraft.value = it; if (it.isNotBlank()) reviewedTorrent.value = null },
            label = { Text("Magnet link") },
            isError = sourceInvalid,
            enabled = !attempt.busy,
            modifier = Modifier.fillMaxWidth().testTag(ChunksSemantics.ADD_SOURCE_FIELD)
        )
        OutlinedButton(onClick = { picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) },
            enabled = !attempt.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Open .torrent")
        }
        torrent?.takeIf { draft.isEmpty() }?.let {
            Text("Using " + it.preview.name + " · " + it.preview.fileCount.coerceAtLeast(1) + " file(s)",
                style = MaterialTheme.typography.bodyMedium)
        }
        fileError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (sourceInvalid) Text("Use a magnet link with one v1 info hash.", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (layout != null && canChooseFiles) AddFileChoice(
            files = layout, skipped = chosenSkips, expanded = fileChoiceOpen, enabled = !attempt.busy,
            onExpanded = { fileChoiceOpen = it }, onSkippedChange = { skipped = it })
        else if (layout != null && session != null) Text("$clientLabel cannot choose files at Add; " +
            "every file will download.", style = MaterialTheme.typography.bodySmall)
        // 2. Exact target (the shared picker).
        destinationPicker()
        sessionError?.let { Text("$clientLabel is unavailable: $it", color = ChunksColors.Yellow) }
        // 3. Save to: client default, or one exact folder.
        Text("Save to", style = MaterialTheme.typography.titleMedium)
        Text(if (useCustom) "A folder you enter on $clientLabel"
            else "$clientLabel's default folder" + (session?.defaultDownloadDirectory?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.bodyMedium)
        if (ClientCapability.ADD_TO_DIRECTORY in caps) OutlinedButton(onClick = { useCustom = !useCustom },
            enabled = !attempt.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (useCustom) "Use the default folder" else "Change folder")
        }
        if (useCustom) OutlinedTextField(
            value = customFolder, onValueChange = { customFolder = it },
            label = { Text("Absolute folder on $clientLabel") },
            isError = !folderValid, singleLine = true, enabled = !attempt.busy,
            modifier = Modifier.fillMaxWidth()
        )
        // 4. Start.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
            value = startState.value, enabled = !attempt.busy, role = Role.Checkbox,
            onValueChange = { startState.value = it }), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = startState.value, onCheckedChange = null)
            Text("Start when added", modifier = Modifier.padding(start = 8.dp))
        }
        if (source != null && session != null && !supported) Text(
            "$clientLabel cannot add this kind of source" + if (!startState.value) " stopped." else ".",
            style = MaterialTheme.typography.bodySmall, color = ChunksColors.Yellow)
        Button(
            onClick = {
                val frozen = source ?: return@Button
                // Frozen at the click: reviewed index → path for exactly these bytes.
                val skips = if (frozen is ClientAddSource.TorrentFile) layout.orEmpty()
                    .filter { it.index in chosenSkips }.associate { it.index to it.path } else emptyMap()
                val request = ClientAddRequest(frozen, folder, startState.value, skips)
                if (!attempt.claim(profileId, frozen.infoHash)) return@Button
                attemptScope.launch {
                    attempt.outcome = try { operations.add(request) } catch (cancel: CancellationException) {
                        ClientOperationOutcome.Uncertain(frozen.infoHash, "cancelled after submission")
                    } catch (_: Exception) {
                        ClientOperationOutcome.Uncertain(frozen.infoHash, "unexpected failure")
                    }
                }
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ChunksSemantics.ADD_SUBMIT)
        ) { Text(if (attempt.busy) "Adding to $clientLabel…" else "Add to $clientLabel") }
        val outcome = attempt.outcome.takeIf { attempt.profileId == profileId }
        outcome?.let { result ->
            Text(ProtocolOperationText.describe("Add", result, clientLabel),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(ChunksSemantics.ADD_REMOTE_OPERATION_STATUS))
            if (result is ClientOperationOutcome.Verified || result is ClientOperationOutcome.Pending) {
                OutlinedButton(onClick = { onViewTransfer(result.hash) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("View transfer") }
            }
            OutlinedButton(onClick = {
                attempt.clearSettled()
                if (result is ClientOperationOutcome.Verified) { sourceDraft.value = ""; reviewedTorrent.value = null }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add another") }
        }
    }
}
