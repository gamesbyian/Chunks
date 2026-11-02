package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Controlled search input: caller retains query, environment subset, sort,
 * selection and persistence. On compact or 200% type, the clear action is a
 * fully visible text button below the field; no trailing icon can get clipped
 * by narrow text field layout. Ordinary widths use one inline icon.
 * No engine, server or transfer side effects.
 */
@Composable
internal fun ClearableTransferSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val fieldFocus = remember { FocusRequester() }
    val clear: () -> Unit = {
        onValueChange("")
        // Only re-focus after an explicit clear gesture, not recomposition.
        fieldFocus.requestFocus()
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < 380.dp || LocalDensity.current.fontScale >= 1.5f
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Put this above the focused input: IME resize/pan can otherwise
            // cover a second row below the field at narrow accessibility sizes.
            if (compact && value.isNotEmpty()) {
                TextButton(
                    onClick = clear,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("Clear search")
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                trailingIcon = if (!compact && value.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = clear,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { contentDescription = "Clear search" }
                        ) {
                            Text("×", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
            )
        }
    }
}
