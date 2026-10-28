package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.FileRole
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Mode
import io.github.gamesbyian.chunks.engine.FinishInOrderRecord
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bounded so the review stays usable at 200% text; longer orders page. */
private const val REVIEW_ROWS = 12

/**
 * Pure presentation of the review list so its behaviour is testable without
 * storage or an engine. Reordering only edits this draft; nothing is applied
 * until the user confirms.
 */
@Composable
internal fun FinishInOrderReview(
    files: List<LocalTransferFile>,
    initialOrder: List<Int>,
    source: FinishInOrderPlanner.OrderSource?,
    busy: Boolean,
    onConfirm: (Mode, List<Int>) -> Unit,
    onCancel: () -> Unit
) {
    val byIndex = remember(files) { files.associateBy { it.index } }
    var order by remember(initialOrder) { mutableStateOf(initialOrder) }
    var mode by remember { mutableStateOf(Mode.BALANCED) }
    var page by remember(initialOrder) { mutableStateOf(0) }
    val pages = (order.size + REVIEW_ROWS - 1) / REVIEW_ROWS

    Text(when (source) {
        FinishInOrderPlanner.OrderSource.EPISODES -> "Order detected from episode numbers. Check it before starting."
        FinishInOrderPlanner.OrderSource.FILENAMES -> "Order taken from numbered file names. Check it before starting."
        null -> "Arrange the files in the order you want them finished."
    }, style = MaterialTheme.typography.bodySmall)
    Text("Chunks gives the first unfinished file High priority and moves to the next " +
        "only after every piece of it passes its hash check. Which pieces arrive depends " +
        "on what peers have, so the order is a preference, not a guarantee. Skipped " +
        "files stay skipped, and any priority you change by hand later is left alone.",
        style = MaterialTheme.typography.bodySmall)
    Column(Modifier.selectableGroup()) {
        listOf(Mode.BALANCED to "Balanced: next file at Normal, the rest Low",
            Mode.FINISH_FIRST to "Finish first: everything after the current file Low")
            .forEach { (choice, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = mode == choice, role = Role.RadioButton,
                        enabled = !busy, onClick = { mode = choice }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == choice, onClick = null)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
    }
    if (pages > 1) Text("Order page ${page + 1} of $pages (${order.size} files)",
        style = MaterialTheme.typography.bodySmall)
    order.withIndex().drop(page * REVIEW_ROWS).take(REVIEW_ROWS).forEach { (position, index) ->
        val name = byIndex[index]?.path?.substringAfterLast('/') ?: "File $index"
        Text("${position + 1}. $name", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                order = order.toMutableList().apply { add(position - 1, removeAt(position)) }
            }, enabled = !busy && position > 0,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .semantics { contentDescription = "Move $name earlier" }) { Text("Earlier") }
            OutlinedButton(onClick = {
                order = order.toMutableList().apply { add(position + 1, removeAt(position)) }
            }, enabled = !busy && position < order.lastIndex,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .semantics { contentDescription = "Move $name later" }) { Text("Later") }
        }
    }
    if (pages > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { page-- }, enabled = page > 0,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Previous files") }
        OutlinedButton(onClick = { page++ }, enabled = page < pages - 1,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Next files") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onConfirm(mode, order) }, enabled = !busy && order.size >= 2,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Start Finish in Order") }
        OutlinedButton(onClick = onCancel,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
    }
}

/** Human status for one stored policy and its latest plan. */
internal fun finishInOrderStatus(
    record: FinishInOrderRecord,
    plan: FinishInOrderPlanner.Decision.Plan?,
    names: Map<Int, String>
): String = buildString {
    append(if (record.policy.mode == Mode.BALANCED) "Finish in Order (Balanced) is on. "
        else "Finish in Order (Finish first) is on. ")
    when {
        record.suspendedReason != null -> append("Paused: ").append(record.suspendedReason)
        record.finished || plan?.finished == true ->
            append("Every file in the order is complete and hash-verified.")
        plan?.active != null -> {
            append("Now finishing: ").append(names[plan.active] ?: "file ${plan.active}").append('.')
            if (plan.activeBlocked) append(" Some of its pieces are not available from " +
                "connected peers, so the next available file is also High until they appear.")
        }
        else -> append("Waiting for the first verified status.")
    }
    if (record.pinned.isNotEmpty()) append(" ${record.pinned.size} file(s) keep the priority " +
        "you set by hand and are not managed.")
}

/**
 * Finish in Order for one phone transfer: review/enable, live status, resume
 * after an uncertain change, and turn off. Turning off leaves the current
 * native priorities exactly as they are.
 */
@Composable
internal fun FinishInOrderPanel(
    infoHash: String,
    files: List<LocalTransferFile>,
    busy: Boolean,
    onPrioritiesChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { FinishInOrderStore(context) }
    var record by remember(infoHash) { mutableStateOf<FinishInOrderRecord?>(null) }
    var plan by remember(infoHash) { mutableStateOf<FinishInOrderPlanner.Decision.Plan?>(null) }
    var reviewing by remember(infoHash) { mutableStateOf(false) }
    var working by remember(infoHash) { mutableStateOf(false) }
    var message by remember(infoHash) { mutableStateOf<String?>(null) }
    val names = remember(files) { files.associate { it.index to it.path.substringAfterLast('/') } }

    // Observation only: refresh the displayed plan while the panel is visible.
    LaunchedEffect(infoHash) {
        while (true) {
            val (stored, observed) = withContext(Dispatchers.IO) {
                val stored = store.get(infoHash)
                stored to stored?.let {
                    try { LocalEngineHolder.current()?.observeFinishInOrder(infoHash) }
                    catch (_: Exception) { null }
                }
            }
            record = stored
            plan = if (stored != null && observed != null) {
                FinishInOrderPlanner.plan(stored.policy, observed, stored.lastApplied, stored.pinned)
                    as? FinishInOrderPlanner.Decision.Plan
            } else null
            delay(5_000L)
        }
    }

    fun act(block: suspend () -> String?) {
        working = true
        scope.launch {
            message = try { block() } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                "Finish in Order could not confirm this change. Refresh the files before trying again."
            }
            record = withContext(Dispatchers.IO) { store.get(infoHash) }
            working = false
            onPrioritiesChanged()
        }
    }

    fun describe(step: FinishInOrderRunner.Step?): String? = when (step) {
        null, FinishInOrderRunner.Step.NotObservable ->
            "The phone engine is not reporting this transfer yet; Finish in Order will start when it does."
        is FinishInOrderRunner.Step.Applied -> "File priorities updated and confirmed by the engine."
        is FinishInOrderRunner.Step.Idle -> null
        is FinishInOrderRunner.Step.Reconciled -> "The last interrupted change was checked; nothing was repeated."
        is FinishInOrderRunner.Step.Suspended -> step.record.suspendedReason
        is FinishInOrderRunner.Step.StorageFailed ->
            "Chunks could not save its change journal, so no priority was changed."
    }

    val current = record
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (current == null) {
            if (!reviewing) {
                OutlinedButton(onClick = { reviewing = true }, enabled = !busy && !working,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Finish in Order…")
                }
            } else {
                val suggestion = remember(files) { FinishInOrderPlanner.suggest(files) }
                val fallback = remember(files) {
                    files.filter { it.wanted && it.sizeBytes > 0 }
                        .sortedWith { a, b -> FinishInOrderPlanner.naturalCompare(a.path, b.path) }
                        .map { it.index }
                }
                val initial = suggestion?.order ?: fallback
                if (initial.size < 2) {
                    Text("Finish in Order needs at least two selected files.",
                        style = MaterialTheme.typography.bodySmall)
                } else FinishInOrderReview(files, initial, suggestion?.source, busy || working,
                    onConfirm = { mode, order ->
                        reviewing = false
                        act {
                            withContext(Dispatchers.IO) {
                                val policy = FinishInOrderPlanner.policyFor(mode, files, order)
                                if (!FinishInOrderController.exclusive { store.save(infoHash, FinishInOrderRecord(policy)) })
                                    "Chunks could not save Finish in Order. Nothing changed."
                                else describe(FinishInOrderController.stepNow(context, infoHash))
                            }
                        }
                    },
                    onCancel = { reviewing = false })
            }
        } else {
            Text(finishInOrderStatus(current, plan, names), style = MaterialTheme.typography.bodySmall)
            plan?.roles?.entries?.filter { it.value == FileRole.ACTIVE || it.value == FileRole.FALLBACK }
                ?.forEach { (index, role) ->
                    Text((if (role == FileRole.ACTIVE) "High: " else "High (fallback): ") +
                        (names[index] ?: "file $index"), style = MaterialTheme.typography.bodySmall)
                }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.suspendedReason != null) {
                    Button(onClick = {
                        act {
                            withContext(Dispatchers.IO) {
                                // Resume re-observes first; a kept journal is
                                // reconciled from readback, never replayed.
                                if (!FinishInOrderController.exclusive {
                                        store.get(infoHash)?.let { fresh ->
                                            store.save(infoHash, fresh.copy(suspendedReason = null))
                                        } == true
                                    })
                                    "Chunks could not save the change. Nothing changed."
                                else describe(FinishInOrderController.stepNow(context, infoHash))
                            }
                        }
                    }, enabled = !busy && !working,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Resume") }
                }
                OutlinedButton(onClick = {
                    act {
                        withContext(Dispatchers.IO) {
                            if (FinishInOrderController.exclusive { store.remove(infoHash) }) "Finish in Order is off. Current file " +
                                "priorities were left exactly as they are."
                            else "Chunks could not turn Finish in Order off. Try again."
                        }
                    }
                }, enabled = !working,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Turn off") }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
