package io.github.gamesbyian.chunks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.EnvironmentHue
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-environment appearance preference, keyed by immutable identity.
 * These choices never authorize remote commands or mutate transfer metadata.
 */
@Composable
internal fun EnvironmentColourPreference(
    id: EnvironmentId,
    label: String,
    configuredIds: List<EnvironmentId> = listOf(id),
    /** Distinguishes identically named saved servers in TalkBack. */
    accessibilityLabel: String = label
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val revision = EnvironmentColourStore.revision
    val scope = rememberCoroutineScope()
    var palette by remember(id) {
        mutableStateOf(EnvironmentPalette.State(emptyMap(), emptyMap()))
    }
    var expanded by rememberSaveable(id.value) { mutableStateOf(false) }
    var saving by remember(id) { mutableStateOf(false) }
    var notice by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(context, id, configuredIds, revision) {
        try {
            palette = withContext(Dispatchers.IO) {
                EnvironmentColourStore(context).ensure(configuredIds)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            notice = "Colour preferences could not be read."
        }
    }
    val selected = palette.hueOf(id)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { expanded = !expanded },
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
                contentDescription = "Choose colour for " + accessibilityLabel
            }) {
            Text(label + " colour: " + (selected?.label ?: "Automatic") +
                if (expanded) " · Hide choices" else " · Change")
        }
        if (expanded) {
            Text("Display only. This never changes the connection or its transfers.",
                style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EnvironmentHue.entries.forEach { hue ->
                    FilterChip(selected = selected == hue, enabled = !saving,
                        onClick = {
                            saving = true
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    EnvironmentColourStore(context).choose(id, hue)
                                }
                                notice = if (saved) null else "Could not save this colour."
                                saving = false
                            }
                        },
                        label = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.layout.Box(Modifier.size(12.dp)
                                    .clip(CircleShape).background(Color(hue.argb)))
                                Text(hue.label)
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = accessibilityLabel + " colour " + hue.label
                        })
                }
            }
            if (id in palette.chosen) {
                OutlinedButton(onClick = {
                    saving = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            EnvironmentColourStore(context).restoreAutomatic(id)
                        }
                        notice = if (ok) null else "Could not restore automatic colour."
                        saving = false
                    }
                }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Restore automatic colour")
                }
            }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) }
    }
}
