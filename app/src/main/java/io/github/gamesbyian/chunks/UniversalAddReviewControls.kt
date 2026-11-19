package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * The common Add review surface: every client presents the selected Place under
 * this heading. The slot deliberately leaves filesystem/remote-folder discovery
 * and validation with the exact client's existing, tested picker.
 *
 * Changes to a Place still invalidate the client's review ticket; this layout
 * component does not own or mutate it.
 */
@Composable
internal fun UniversalAddSaveToSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Save to", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        content()
    }
}

/**
 * The single primary Add submit control across Local, rTorrent and protocol
 * clients. The caller's already-frozen [onClick] does the one admission/write;
 * this component cannot retry, infer success, or own engine state.
 */
@Composable
internal fun UniversalAddSubmitAction(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    label: String = "Add transfer",
    unavailableReason: String? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
            .testTag(ChunksSemantics.ADD_SUBMIT)
            .semantics {
                if (!enabled && !unavailableReason.isNullOrBlank())
                    stateDescription = "Unavailable: $unavailableReason"
            }
    ) {
        Text(label)
    }
}
