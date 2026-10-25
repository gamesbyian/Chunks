package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.AliasReviewPlanner
import io.github.gamesbyian.chunks.model.AliasReviewWrite
import io.github.gamesbyian.chunks.rpc.RemoteTransfer

/**
 * Explicit, bounded queue-wide name review. Only local preferences are changed.
 * User selections/drafts live above the LazyColumn so scrolling cannot apply
 * unexpected names or lose the review state.
 */
@Composable
internal fun AliasBatchReviewScreen(
    transfers: List<RemoteTransfer>,
    aliasStore: TransferAliasStore,
    aliasScope: String,
    aliasRevision: Int,
    onAliasesChanged: () -> Unit,
    onBack: () -> Unit
) {
    val proposals = remember(transfers, aliasScope, aliasRevision) {
        AliasReviewPlanner.discover(transfers, { aliasStore.get(aliasScope, it.hash) != null })
    }
    val selected = remember(aliasScope) { mutableStateMapOf<String, Boolean>() }
    val edits = remember(aliasScope) { mutableStateMapOf<String, String>() }
    var awaitingApproval by remember(aliasScope) { mutableStateOf<List<AliasReviewWrite>>(emptyList()) }
    var undoAvailable by remember(aliasScope) { mutableStateOf<List<AliasReviewWrite>>(emptyList()) }
    var feedback by remember(aliasScope) { mutableStateOf<String?>(null) }

    val selectedCount = proposals.count { selected[it.hash] == true }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Clean up queue names", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Text("Review filename-only proposals in this server view. Display names stay on this device. No torrent metadata, remote filename or seeding data changes.")
            OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Back to transfers")
            }
        }
        if (undoAvailable.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Saved " + undoAvailable.size + " local display names.",
                            fontWeight = FontWeight.Bold)
                        Text("Undo is available while none of those names has since been edited.")
                        OutlinedButton(
                            onClick = {
                                if (aliasStore.undoReviewedBatch(aliasScope, undoAvailable)) {
                                    undoAvailable = emptyList()
                                    onAliasesChanged()
                                    feedback = "Restored original queue labels for that batch."
                                } else {
                                    undoAvailable = emptyList()
                                    feedback = "Some names have changed; nothing was undone. Review them individually."
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Undo last batch") }
                    }
                }
            }
        }
        if (feedback != null) item {
            Text(feedback.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        }
        if (awaitingApproval.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Confirm " + awaitingApproval.size + " display names",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge)
                        Text("Only local Chunks labels will change. No RPC requests or filesystem changes.")
                        OutlinedButton(onClick = { awaitingApproval = emptyList() },
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Back to editing")
                        }
                        Button(onClick = {
                            val review = awaitingApproval
                            val saved = runCatching {
                                aliasStore.saveReviewedBatch(aliasScope, review)
                            }.getOrDefault(false)
                            if (saved) {
                                undoAvailable = review
                                awaitingApproval = emptyList()
                                selected.clear()
                                edits.clear()
                                onAliasesChanged()
                                feedback = "Names saved on this device."
                            } else {
                                awaitingApproval = emptyList()
                                feedback = "No names were changed. One or more already have aliases or local saving failed. Refresh the review."
                            }
                        }, modifier = Modifier.heightIn(min = 52.dp)) {
                            Text("Apply these local display names")
                        }
                    }
                }
            }
            items(awaitingApproval, key = { "confirm-" + it.hash }) { write ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Original: " + write.originalName,
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Display as: " + write.alias, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            item {
                Text(proposals.size.toString() + " suggestions ready for review" +
                    if (proposals.size == AliasReviewPlanner.MAX_REVIEW_ITEMS)
                        " (first 100 in this server view)" else "")
                Text("Existing custom names and filenames without a safe suggestion are skipped. Nothing is selected or saved automatically.",
                    style = MaterialTheme.typography.bodySmall)
                if (proposals.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            proposals.forEach { selected[it.hash] = true }
                            feedback = null
                        }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Select shown")
                        }
                        OutlinedButton(onClick = {
                            selected.clear()
                            feedback = null
                        }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Clear")
                        }
                    }
                    Button(
                        enabled = selectedCount > 0,
                        onClick = {
                            val names = proposals.filter { selected[it.hash] == true }
                                .associate { it.hash to (edits[it.hash] ?: it.suggestedName) }
                            try {
                                awaitingApproval = AliasReviewPlanner.prepare(proposals, names)
                                feedback = null
                            } catch (failure: IllegalArgumentException) {
                                feedback = failure.message ?: "Fix invalid names before continuing."
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) { Text("Review " + selectedCount + " selected names") }
                }
            }
            if (proposals.isEmpty()) item {
                Text("No eligible names in this server view. Existing custom labels will never be overwritten.")
            }
            items(proposals, key = { it.hash }) { item ->
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(
                                checked = selected[item.hash] == true,
                                onCheckedChange = { selected[item.hash] = it; feedback = null }
                            )
                            Column {
                                Text(item.originalName, fontWeight = FontWeight.Bold,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text("Proposed: " + (edits[item.hash] ?: item.suggestedName),
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(item.explanation,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (selected[item.hash] == true) {
                            for (suggestion in item.alternatives) {
                                OutlinedButton(
                                    onClick = { edits[item.hash] = suggestion.name },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) { Text(suggestion.name, maxLines = 2) }
                            }
                            OutlinedTextField(
                                value = edits[item.hash] ?: item.suggestedName,
                                onValueChange = { edits[item.hash] = it },
                                label = { Text("Display name for " + item.originalName) },
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
