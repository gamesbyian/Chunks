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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.seeding.RateLimit
import io.github.gamesbyian.chunks.seeding.SeedingPolicy
import io.github.gamesbyian.chunks.seeding.SeedingRule
import io.github.gamesbyian.chunks.seeding.SeedingRuleStore
import java.math.BigDecimal

/** One environment a seeding rule can apply to: the phone, or one saved server profile. */
internal data class SeedingEnvironment(val id: String, val label: String, val server: Boolean) {
    companion object {
        const val PHONE_ID = "phone"
        fun phone() = SeedingEnvironment(PHONE_ID, PHONE_ENVIRONMENT_LABEL, server = false)
        fun server(endpoint: String, username: String) = SeedingEnvironment(
            "remote:" + RememberedRemoteProfileRoster.identityOf(endpoint, username),
            serverEnvironmentLabel(endpoint + "\u0000" + username), server = true)
    }
}

/**
 * Smart Seeding Rules list and editor (SMART-SEEDING-RULES). Rules are saved
 * for future use only: this version does not apply them to transfers, and the
 * screen says so. Every rule shows exactly which environments it applies to.
 */
@Composable
internal fun SeedingRulesScreen(environments: List<SeedingEnvironment>, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SeedingRuleStore(context) }
    var snapshot by remember { mutableStateOf(store.load()) }
    var editing by remember { mutableStateOf<SeedingRule?>(null) }
    var editingDefault by remember { mutableStateOf<SeedingEnvironment?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val labels = environments.associate { it.id to it.label }

    fun persist(next: SeedingRuleStore.Snapshot, replaceUnreadable: Boolean = false): Boolean {
        val ok = store.save(next, replaceUnreadable)
        if (ok) snapshot = next.copy(unreadable = false)
        message = if (ok) "Saved." else "The rules could not be saved. Nothing changed."
        return ok
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Seeding rules", style = MaterialTheme.typography.headlineSmall)
        ChunksBanner("What these rules do today",
            "On this phone, rules apply to transfers added from now on, while Chunks is running: upload limits " +
                "are set and confirmed, and a reached stop condition pauses the transfer once. Transfers you already " +
                "have are not changed, and editing a rule does not change transfers it was already applied to. " +
                "Server rules are saved but not applied yet. Nothing is ever removed or deleted.", ChunksTone.PAUSED)
        if (snapshot.unreadable) {
            ChunksBanner("Saved rules could not be read",
                "They are kept as they are. Start over only if you are sure; that replaces them.", ChunksTone.ERROR)
            OutlinedButton(onClick = { persist(SeedingRuleStore.Snapshot(emptyList(), emptyMap()), replaceUnreadable = true) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Start over with no rules") }
        }
        val rule = editing
        val defaultFor = editingDefault
        when {
            rule != null -> SeedingRuleEditor(rule, environments, onCancel = { editing = null }) { saved ->
                val others = snapshot.rules.filter { it.id != saved.id }
                val index = snapshot.rules.indexOfFirst { it.id == saved.id }
                val rules = if (index < 0) others + saved else others.toMutableList().apply { add(index, saved) }
                if (persist(snapshot.copy(rules = rules))) editing = null
            }
            defaultFor != null -> {
                Text("When no rule matches on ${defaultFor.label}", fontWeight = FontWeight.Bold)
                SeedingPolicyEditor(snapshot.defaults[defaultFor.id] ?: SeedingPolicy(), onCancel = { editingDefault = null }) { policy ->
                    if (persist(snapshot.copy(defaults = snapshot.defaults + (defaultFor.id to policy)))) editingDefault = null
                }
            }
            else -> {
                if (snapshot.rules.isEmpty()) Text("No rules yet. Every transfer uses its environment's default below.",
                    style = MaterialTheme.typography.bodyMedium)
                snapshot.rules.forEachIndexed { position, r ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${position + 1}. ${r.name}", fontWeight = FontWeight.Bold)
                            Text("Trackers: " + r.trackerDomains.sorted().joinToString(", ") +
                                when (r.privateOnly) { true -> " · private torrents only"; false -> " · public torrents only"; null -> "" },
                                style = MaterialTheme.typography.bodySmall)
                            Text(appliesTo(r, labels), style = MaterialTheme.typography.bodySmall)
                            Text(r.policy.describe(), style = MaterialTheme.typography.bodySmall)
                            if (pendingDelete == r.id) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { pendingDelete = null; persist(snapshot.copy(rules = snapshot.rules - r)) },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Delete rule") }
                                OutlinedButton(onClick = { pendingDelete = null },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Keep") }
                            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { editing = r; message = null },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Edit") }
                                OutlinedButton(onClick = { pendingDelete = r.id },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Delete…") }
                            }
                        }
                    }
                }
                ActionTile("New rule", ChunksColors.Green, {
                    message = null
                    editing = SeedingRule(java.util.UUID.randomUUID().toString(), SeedingRule.suggestedName(emptySet(), SeedingPolicy()),
                        false, emptySet(), policy = SeedingPolicy(), environments = environments.map { it.id }.toSet(),
                        knownEnvironments = environments.map { it.id }.toSet())
                }, Modifier.fillMaxWidth(), stacked = false)
                Text("Defaults", style = MaterialTheme.typography.titleMedium)
                environments.forEach { env ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        EnvironmentBadge(env.label, env.server, environment = env.id.removePrefix("remote:")
                            .takeIf { TransferListSortStore.validEnvironment(it) }
                            ?.let { io.github.gamesbyian.chunks.model.EnvironmentId(it) })
                        Text((snapshot.defaults[env.id] ?: SeedingPolicy()).describe(), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { editingDefault = env; message = null },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit default") }
                    }
                }
            }
        }
        message?.let { Text(it) }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back") }
    }
}

internal fun appliesTo(rule: SeedingRule, labels: Map<String, String>): String {
    val named = rule.environments.map { labels[it] ?: "a server not saved on this phone" }.sorted()
    return "Applies to: " + (if (named.isEmpty()) "no environment" else named.joinToString(", ")) +
        if (rule.includeFutureEnvironments) ", and servers added later" else ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeedingRuleEditor(
    initial: SeedingRule, environments: List<SeedingEnvironment>,
    onCancel: () -> Unit, onSave: (SeedingRule) -> Unit
) {
    var trackers by remember { mutableStateOf(initial.trackerDomains.sorted().joinToString(", ")) }
    var privateOnly by remember { mutableStateOf(initial.privateOnly) }
    var selected by remember { mutableStateOf(initial.environments) }
    var future by remember { mutableStateOf(initial.includeFutureEnvironments) }
    var name by remember { mutableStateOf(initial.name) }
    var nameIsCustom by remember { mutableStateOf(initial.nameIsCustom) }
    var policy by remember { mutableStateOf<SeedingPolicy?>(initial.policy) }
    val domains = trackers.split(',', ' ', '\n').map { it.trim().lowercase().trimEnd('.') }.filter { it.isNotEmpty() }.toSet()
    val domainError = domains.firstOrNull { d -> d.any { it == '/' || it == ':' || it == '?' || it == '@' } || '.' !in d }
        ?.let { "\"$it\" is not a tracker domain. Enter only the host name, such as tracker.example.org." }
    val suggested = policy?.let { SeedingRule.suggestedName(domains, it) }
    if (!nameIsCustom && suggested != null && suggested != name) name = suggested

    Text("When a torrent's tracker is", fontWeight = FontWeight.Bold)
    OutlinedTextField(trackers, { trackers = it }, label = { Text("Tracker domains (comma separated)") },
        modifier = Modifier.fillMaxWidth(), isError = domainError != null)
    Text(domainError ?: "Matches the domain and its subdomains, never look-alike names.", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(null to "Any torrent", true to "Private only", false to "Public only").forEach { (value, label) ->
            FilterChip(privateOnly == value, { privateOnly = value }, { Text(label) }, Modifier.heightIn(min = 48.dp))
        }
    }
    Text("Then", fontWeight = FontWeight.Bold)
    SeedingPolicyEditor(initial.policy, onChange = { policy = it }, onCancel = null, onSave = null)
    Text("Applies to", fontWeight = FontWeight.Bold)
    environments.forEach { env ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(env.id in selected, role = Role.Checkbox) {
            selected = if (it) selected + env.id else selected - env.id }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(env.id in selected, null); Text(env.label, Modifier.padding(start = 8.dp))
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(future, role = Role.Checkbox) { future = it },
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(future, null); Text("Also apply to servers added later", Modifier.padding(start = 8.dp))
    }
    OutlinedTextField(name, { name = it; nameIsCustom = true }, label = { Text("Rule name") }, modifier = Modifier.fillMaxWidth())
    if (nameIsCustom && suggested != null) OutlinedButton(onClick = { nameIsCustom = false; name = suggested },
        modifier = Modifier.heightIn(min = 48.dp)) { Text("Use suggested name: $suggested") }
    val current = policy
    val ready = current != null && domainError == null && domains.isNotEmpty() && selected.isNotEmpty() && name.isNotBlank()
    if (domains.isEmpty()) Text("Add at least one tracker domain.", style = MaterialTheme.typography.bodySmall)
    if (selected.isEmpty()) Text("Choose at least one environment.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionTile("Save rule", ChunksColors.Green, {
            if (current != null) onSave(initial.copy(name = name.trim(), nameIsCustom = nameIsCustom, trackerDomains = domains,
                privateOnly = privateOnly, policy = current, environments = selected, includeFutureEnvironments = future,
                knownEnvironments = initial.knownEnvironments + environments.map { it.id }))
        }, Modifier.weight(1f), enabled = ready, stacked = false)
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Cancel") }
    }
}

/**
 * Ratio, time and upload cap, each independent. Units are shown exactly
 * (kbit/s and KiB/s differ); invalid input blocks saving with a reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeedingPolicyEditor(
    initial: SeedingPolicy,
    onChange: (SeedingPolicy?) -> Unit = {},
    onCancel: (() -> Unit)?,
    onSave: ((SeedingPolicy) -> Unit)?
) {
    var ratio by remember { mutableStateOf(initial.ratioGoal?.toPlainString().orEmpty()) }
    var maxDays by remember { mutableStateOf(initial.maxSeedSeconds?.let { (it / 86_400).toString() }.orEmpty()) }
    var minHours by remember { mutableStateOf(initial.minSeedSeconds?.let { (it / 3_600).toString() }.orEmpty()) }
    var upload by remember { mutableStateOf(initial.uploadLimit?.let {
        BigDecimal(it.bytesPerSecond).divide(it.unit.bytes).stripTrailingZeros().toPlainString() + " " + it.unit.label }.orEmpty()) }
    var combine by remember { mutableStateOf(initial.combine) }

    val ratioValue = ratio.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
    val ratioError = if (ratio.isNotBlank() && (ratioValue == null || ratioValue.signum() <= 0)) "Enter a ratio above zero, such as 1.00" else null
    val days = maxDays.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
    val daysError = if (maxDays.isNotBlank() && (days == null || days <= 0)) "Enter whole days above zero" else null
    val hours = minHours.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
    val hoursError = if (minHours.isNotBlank() && (hours == null || hours < 0)) "Enter whole hours" else null
    val parsedUpload = upload.trim().takeIf { it.isNotEmpty() }?.let { RateLimit.parse(it) }
    val uploadError = parsedUpload?.exceptionOrNull()?.message
    val policy = if (listOf(ratioError, daysError, hoursError, uploadError).all { it == null }) SeedingPolicy(
        ratioGoal = ratioValue, minSeedSeconds = hours?.times(3_600), maxSeedSeconds = days?.times(86_400),
        uploadLimit = parsedUpload?.getOrNull(), combine = combine) else null
    onChange(policy)

    OutlinedTextField(ratio, { ratio = it }, label = { Text("Stop at ratio (blank: no ratio limit)") },
        modifier = Modifier.fillMaxWidth(), isError = ratioError != null, supportingText = ratioError?.let { { Text(it) } })
    OutlinedTextField(maxDays, { maxDays = it }, label = { Text("Stop after days of seeding (blank: no time limit)") },
        modifier = Modifier.fillMaxWidth(), isError = daysError != null, supportingText = daysError?.let { { Text(it) } })
    OutlinedTextField(minHours, { minHours = it }, label = { Text("Seed at least, in hours (optional)") },
        modifier = Modifier.fillMaxWidth(), isError = hoursError != null, supportingText = hoursError?.let { { Text(it) } })
    OutlinedTextField(upload, { upload = it }, label = { Text("Upload limit per transfer (blank: none), e.g. 24 KiB/s") },
        modifier = Modifier.fillMaxWidth(), isError = uploadError != null,
        supportingText = { Text(uploadError ?: parsedUpload?.getOrNull()?.describe() ?: "kbit/s are bits; KiB/s and kB/s are bytes.") })
    if (ratioValue != null && days != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(combine == SeedingPolicy.Combine.ANY, { combine = SeedingPolicy.Combine.ANY },
            { Text("Stop at whichever comes first") }, Modifier.heightIn(min = 48.dp))
        FilterChip(combine == SeedingPolicy.Combine.ALL, { combine = SeedingPolicy.Combine.ALL },
            { Text("Stop only when both are reached") }, Modifier.heightIn(min = 48.dp))
    }
    policy?.let { p ->
        Text(p.describe(), style = MaterialTheme.typography.bodyMedium)
        p.warnings().forEach { ChunksBanner("Check this rule", it, ChunksTone.PAUSED) }
    }
    if (onSave != null && onCancel != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionTile("Save default", ChunksColors.Green, { policy?.let(onSave) }, Modifier.weight(1f), enabled = policy != null, stacked = false)
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Cancel") }
    }
}
