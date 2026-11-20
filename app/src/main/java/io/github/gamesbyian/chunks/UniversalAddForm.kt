package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal data class UniversalAddToggle(
    val checked: Boolean,
    val enabled: Boolean,
    val onChange: (Boolean) -> Unit
)

internal data class UniversalAddAction(
    val enabled: Boolean,
    val onSubmit: () -> Unit,
    val unavailableReason: String? = null
)

/**
 * Shared ordering and primary action for all Add destinations. Client content
 * supplies the reviewed source and client-specific inputs and evidence. The
 * form never creates, executes or resets an attempt.
 */
@Composable
internal fun UniversalAddForm(
    capabilities: UniversalAddClientCapabilities,
    source: @Composable () -> Unit,
    destination: @Composable () -> Unit,
    saveTo: @Composable ColumnScope.() -> Unit,
    start: UniversalAddToggle?,
    files: @Composable (UniversalAddClientCapabilities) -> Unit,
    artwork: UniversalAddToggle?,
    options: @Composable () -> Unit,
    submit: UniversalAddAction?,
    status: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    lazyContentPadding: PaddingValues? = null,
    guidance: @Composable () -> Unit = {}
) {
    val artworkOffered = capabilities.artworkChoice && rememberAddArtworkChoiceOffered()
    fun section(id: String, content: @Composable () -> Unit) = id to content
    val sections = listOf(
        section("title") {
            Text("Add a transfer", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
        },
        section("source", source),
        section("destination", destination),
        section("save-to") { UniversalAddSaveToSection(content = saveTo) },
        section("start") {
            start?.let { UniversalAddStartChoice(it.checked, it.enabled, it.onChange) }
        },
        section("files") { files(capabilities) },
        section("artwork") {
            artwork?.let { AddArtworkChoice(it.checked, it.enabled, artworkOffered, it.onChange) }
        },
        section("options", options),
        section("submit") {
            submit?.let {
                UniversalAddSubmitAction(it.onSubmit, it.enabled, unavailableReason = it.unavailableReason)
            }
            guidance()
        },
        section("status", status)
    )
    if (lazyContentPadding != null) {
        LazyColumn(modifier, contentPadding = lazyContentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sections.forEach { (id, content) ->
                item(key = id) { UniversalAddFormSection(content) }
            }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sections.forEach { (id, content) -> key(id) { UniversalAddFormSection(content) } }
        }
    }
}

@Composable
private fun UniversalAddFormSection(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
internal fun UniversalAddContinuationAction(
    tag: String,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        Text(label)
    }
}
