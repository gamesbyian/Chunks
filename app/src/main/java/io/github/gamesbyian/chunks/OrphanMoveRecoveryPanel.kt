package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.TransferMove
import io.github.gamesbyian.chunks.model.TransferMove.Direction
import io.github.gamesbyian.chunks.model.TransferMove.Observation
import io.github.gamesbyian.chunks.model.TransferMove.Stage
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Display-only selection; never infers source job absence from a filtered list.
 *
 * A removed job no longer has a transfer card with Move controls. Its
 * attempted/uncertain durable journal still belongs to one exact rTorrent
 * endpoint+account. Other accounts may have the same hash.
 */
internal object OrphanMoveRecovery {
    fun candidates(
        records: List<TransferMove.Record>,
        direction: Direction,
        serverScopeDigest: String,
        visibleSourceHashes: Collection<String>
    ): List<TransferMove.Record> {
        if (!Regex("[0-9a-f]{64}").matches(serverScopeDigest)) return emptyList()
        val visible = visibleSourceHashes.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        return records.filter {
            it.direction == direction &&
                it.serverScopeDigest == serverScopeDigest &&
                it.stage == Stage.SOURCE_REMOVAL_UNCERTAIN &&
                it.infoHash !in visible
        }.sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.infoHash }))
    }

    /** A first absent observation permits only a second, explicit read-only
     * check. Final local journal retirement requires the same exact record
     * after the second independent absent observation. */
    fun mayClear(
        expected: TransferMove.Record,
        savedNow: TransferMove.Record?,
        selectedScopeDigest: String,
        sourceNow: Observation
    ): Boolean = expected.stage == Stage.SOURCE_REMOVAL_UNCERTAIN &&
        selectedScopeDigest == expected.serverScopeDigest &&
        savedNow == expected && sourceNow == Observation.Absent
}

/**
 * Recovery for a missing original transfer card. No Add, Stop, Start,
 * Remove, file operation or automatic retry is reachable from this panel.
 * Clearing a locally stored reminder never claims the destination job exists.
 */
@Composable
internal fun OrphanMoveRecoveryPanel(
    direction: Direction,
    serverAliasScope: String,
    visibleSourceHashes: Collection<String>,
    enabled: Boolean,
    revision: Int,
    observeSource: suspend (String) -> Observation,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { TransferMoveStore(context) }
    val scopeDigest = remember(serverAliasScope) { TransferMoveStore.scopeDigest(serverAliasScope) }
    val currentScope by rememberUpdatedState(scopeDigest)
    val currentlyEnabled by rememberUpdatedState(enabled)
    val entries = remember(direction, scopeDigest, revision, visibleSourceHashes) {
        OrphanMoveRecovery.candidates(
            store.all(direction), direction, scopeDigest, visibleSourceHashes
        )
    }
    if (entries.isEmpty()) return
    val coroutineScope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Unresolved Move records", style = MaterialTheme.typography.titleMedium)
        Text(
            "An original torrent job may have disappeared while Chunks was checking its removal. " +
                "Recheck the original account without sending a torrent command. Downloaded files stay where they are.",
            style = MaterialTheme.typography.bodySmall
        )
        entries.forEach { record ->
            var checking by remember(record, scopeDigest) { mutableStateOf(false) }
            var absentConfirmed by remember(record, scopeDigest) { mutableStateOf(false) }
            var message by remember(record, scopeDigest) { mutableStateOf<String?>(null) }
            fun stillBound(): Boolean = currentScope == record.serverScopeDigest &&
                currentlyEnabled && store.get(direction, record.infoHash, currentScope) == record

            Text(record.name.take(80), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Original job: ${record.infoHash.take(12)}… on ${record.serverLabel}. " +
                    "Earlier removal outcome is uncertain.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = {
                    if (checking || !stillBound()) return@OutlinedButton
                    checking = true
                    absentConfirmed = false
                    coroutineScope.launch {
                        try {
                            val observed = withContext(Dispatchers.IO) {
                                observeSource(record.infoHash)
                            }
                            if (!stillBound()) return@launch
                            message = when (observed) {
                                Observation.Absent -> {
                                    absentConfirmed = true
                                    "The original job was independently observed absent. " +
                                        "A separate confirmation can now clear only this local Move reminder."
                                }
                                Observation.Present ->
                                    "The original job still exists. No command was sent. " +
                                        "Open its transfer card to review the Move."
                                Observation.Unknown ->
                                    "The original account could not confirm this job's status. " +
                                        "The Move reminder was kept; reconnect and check again."
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            if (stillBound())
                                message = "The original job could not be checked. The reminder was kept."
                        } finally {
                            checking = false
                        }
                    }
                },
                enabled = enabled && !checking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text("Check original job (read-only)") }
            if (absentConfirmed) {
                Text(
                    "Clear the local reminder for this exact account and torrent? " +
                        "This will not remove any job, change downloaded files, or prove the destination is still present.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        if (checking || !absentConfirmed || !stillBound()) return@Button
                        checking = true
                        absentConfirmed = false
                        coroutineScope.launch {
                            try {
                                // The earlier absence can go stale after a
                                // same-hash re-add. Re-read immediately before
                                // any local journal mutation.
                                val sourceNow = withContext(Dispatchers.IO) {
                                    observeSource(record.infoHash)
                                }
                                if (!stillBound()) return@launch
                                val cleared = if (OrphanMoveRecovery.mayClear(
                                        record, store.get(direction, record.infoHash, currentScope),
                                        currentScope, sourceNow
                                    )) withContext(Dispatchers.IO) { store.remove(record) }
                                    else false
                                if (!stillBound() && !cleared) {
                                    message = "The server account or saved Move changed. Nothing was cleared."
                                    return@launch
                                }
                                if (cleared) {
                                    message = "Only the resolved local Move reminder was cleared."
                                    onChanged()
                                } else {
                                    message = "The source job is present or unverified, or the journal changed. " +
                                        "The Move reminder was kept. No torrent command was sent."
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                if (stillBound())
                                    message = "The journal could not be cleared. Nothing will be retried."
                            } finally {
                                checking = false
                            }
                        }
                    },
                    enabled = enabled && !checking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Clear only this Move reminder") }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
