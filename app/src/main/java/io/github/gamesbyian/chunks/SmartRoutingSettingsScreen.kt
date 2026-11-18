package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.SmartRoutingAddSuggestion
import io.github.gamesbyian.chunks.model.SmartRoutingPolicyStore
import io.github.gamesbyian.chunks.model.SmartRoutingPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One configured exact environment a rule may name (Local or a saved client). */
internal data class SmartRoutingTargetChoice(val environmentId: String, val label: String)

/**
 * Options > Smart Routing. Rules map a content kind (classified offline from
 * the source's own file names) to ONE exact configured client. Rules only
 * propose a target in Add; nothing is added, moved or started from here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SmartRoutingSettingsScreen(
    targets: List<SmartRoutingTargetChoice>,
    onBack: () -> Unit,
    storeFactory: (android.content.Context) -> SmartRoutingPolicyStore = { SmartRoutingPolicyStore(it) }
) {
    val context = LocalContext.current
    val store = remember(context) { storeFactory(context) }
    val scope = rememberCoroutineScope()
    var read by remember { mutableStateOf<SmartRoutingPolicyStore.Read?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var draftKind by remember { mutableStateOf<SmartRoutingPreview.Kind?>(null) }
    var draftTarget by remember { mutableStateOf<String?>(null) }

    suspend fun reload() { read = withContext(Dispatchers.IO) { store.read() } }
    LaunchedEffect(store) { reload() }

    fun save(transform: (SmartRoutingPolicyStore.Policy) -> SmartRoutingPolicyStore.Policy) {
        val ready = read as? SmartRoutingPolicyStore.Read.Ready ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.save(ready.revision, transform(ready.policy)) }
            notice = when (result) {
                SmartRoutingPolicyStore.Save.SAVED -> null
                SmartRoutingPolicyStore.Save.STALE_REVISION -> "Rules changed elsewhere; showing the latest."
                else -> "Could not save Smart Routing rules."
            }
            reload()
        }
    }

    /** Priorities follow the displayed order: first rule wins. */
    fun reprioritized(rules: List<SmartRoutingPreview.Rule>) =
        rules.mapIndexed { i, r -> r.copy(priority = rules.size - i) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        Text("Smart Routing", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        Text("Suggest which client should receive a new transfer, from the kinds of files it contains. " +
            "Add always shows the suggestion first; you choose. Nothing is moved.",
            style = MaterialTheme.typography.bodyMedium)
        when (val current = read) {
            null -> Text("Loading…")
            SmartRoutingPolicyStore.Read.Unavailable ->
                Text("Smart Routing settings could not be read. They were left unchanged.",
                    color = ChunksColors.Yellow)
            is SmartRoutingPolicyStore.Read.Ready -> {
                val policy = current.policy
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = policy.enabled, role = Role.Switch,
                    onValueChange = { on -> save { it.copy(enabled = on) } }
                ).testTag("chunks.smart_routing.enabled"), verticalAlignment = Alignment.CenterVertically) {
                    Text("Suggest a client in Add", Modifier.weight(1f))
                    Switch(checked = policy.enabled, onCheckedChange = null)
                }
                val labels = targets.associate { it.environmentId to it.label }
                if (policy.rules.isEmpty()) Text("No rules yet. When nothing matches, Add asks.",
                    style = MaterialTheme.typography.bodySmall)
                policy.rules.forEachIndexed { index, rule ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${index + 1}. " + SmartRoutingAddSuggestion.kindLabel(rule.kind) + " → " +
                                (labels[rule.targetEnvironmentId] ?: "A client that is no longer set up"),
                                style = MaterialTheme.typography.titleSmall)
                            ResponsiveActionGroup { cell ->
                                OutlinedButton(onClick = { save { p ->
                                    val list = p.rules.toMutableList()
                                    if (index > 0) { list.add(index - 1, list.removeAt(index)) }
                                    p.copy(rules = reprioritized(list))
                                } }, enabled = index > 0, modifier = cell.heightIn(min = 48.dp)) { Text("Up") }
                                OutlinedButton(onClick = { save { p ->
                                    val list = p.rules.toMutableList()
                                    if (index < list.lastIndex) { list.add(index + 1, list.removeAt(index)) }
                                    p.copy(rules = reprioritized(list))
                                } }, enabled = index < policy.rules.lastIndex,
                                    modifier = cell.heightIn(min = 48.dp)) { Text("Down") }
                                OutlinedButton(onClick = { save { p ->
                                    p.copy(rules = reprioritized(p.rules.filterNot { it.id == rule.id }))
                                } }, modifier = cell.heightIn(min = 48.dp)) { Text("Remove") }
                            }
                        }
                    }
                }
                Text("New rule", style = MaterialTheme.typography.titleMedium)
                Text("Content", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmartRoutingPreview.Kind.entries.forEach { kind ->
                        FilterChip(selected = draftKind == kind, onClick = { draftKind = kind },
                            label = { Text(SmartRoutingAddSuggestion.kindLabel(kind)) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                Text("Send to", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    targets.forEach { target ->
                        FilterChip(selected = draftTarget == target.environmentId,
                            onClick = { draftTarget = target.environmentId },
                            label = { Text(target.label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                Button(onClick = {
                    val kind = draftKind ?: return@Button
                    val target = draftTarget ?: return@Button
                    save { p ->
                        val id = "rule-" + java.lang.Long.toString(System.nanoTime() and Long.MAX_VALUE, 36)
                        p.copy(rules = reprioritized(p.rules + SmartRoutingPreview.Rule(
                            id = id, priority = 0, kind = kind, minimumConfidence = 60,
                            targetEnvironmentId = target)))
                    }
                    draftKind = null; draftTarget = null
                }, enabled = draftKind != null && draftTarget != null && policy.rules.size < 500,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add rule") }
            }
        }
        notice?.let { Text(it, color = ChunksColors.Yellow) }
    }
}
