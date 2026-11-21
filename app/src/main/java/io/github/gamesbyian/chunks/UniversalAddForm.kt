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
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
    guidance: @Composable () -> Unit = {},
    artworkExpandedState: MutableState<Boolean>? = null,
    originalHandling: OriginalTorrentHandling? = null,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true
) {
    val artworkOffered = capabilities.artworkChoice && rememberAddArtworkChoiceOffered()
    // Owned outside all LazyColumn items. A temporary change of Add status
    // or lazy disposal must never collapse the checkbox the person is using.
    val artworkDisclosure = artworkExpandedState ?: rememberSaveable { mutableStateOf(false) }
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
            artwork?.let {
                AddArtworkChoice(it.checked, it.enabled, artworkOffered,
                    expandedState = artworkDisclosure, onAutomatic = it.onChange)
            }
        },
        section("options") {
            if (capabilities.originalSourcePolicy && originalHandling != null)
                OriginalTorrentHandlingSummary(originalHandling)
            options()
        },
        section("submit") {
            submit?.let {
                UniversalAddSubmitAction(it.onSubmit, it.enabled, unavailableReason = it.unavailableReason)
            }
            guidance()
        },
        section("status") {
            status()
            if (onBack != null) UniversalAddBackAction(onBack, enabled = backEnabled)
        }
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

@Composable
internal fun UniversalAddBackAction(onBack: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick = onBack, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(ChunksSemantics.ADD_BACK_TO_TRANSFERS)) {
        Text("Back to transfers")
    }
}

@Composable
internal fun UniversalAddNextAction(phase: Phase, enabled: Boolean = true, onClick: () -> Unit) {
    val label = when (phase) {
        Phase.VERIFIED -> "Start another Add"
        Phase.SESSION_CONFIRMED -> "Acknowledge session and start another Add"
        Phase.JOB_CONFIRMED_START_UNVERIFIED -> "Acknowledge registered job; Start unverified"
        Phase.REVIEWED -> "Review again"
        else -> return
    }
    UniversalAddContinuationAction(ChunksSemantics.ADD_ANOTHER, label, enabled, onClick)
}

internal data class UniversalAddOriginalPolicy(
    val handling: OriginalTorrentHandling,
    val deletable: Boolean
)

/** Read the same global policy for each reviewed Android document. */
@Composable
internal fun rememberUniversalAddOriginalPolicy(
    document: Uri?,
    reviewedSource: Any?
): UniversalAddOriginalPolicy? {
    val context = LocalContext.current
    // Replacing a source with a second review of the same URI must not carry a
    // previous delete capability or policy through the new read.
    return key(document, reviewedSource) {
        produceState<UniversalAddOriginalPolicy?>(null, context, document) {
            if (document != null) value = withContext(Dispatchers.IO) {
                val saved = OriginalTorrentHandlingStore(context).default
                val deletable = OriginalTorrentDocuments.supportsDelete(context, document)
                val effective = if (saved == OriginalTorrentHandling.DELETE && !deletable)
                    OriginalTorrentHandling.ARCHIVE else saved
                UniversalAddOriginalPolicy(effective, deletable)
            }
        }.value
    }
}
