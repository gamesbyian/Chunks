package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Reusable, controlled list-search input. Does not store, restore or mutate a
 * query itself: phone/server/combined owners retain their existing independent
 * search, filter, scope and selection state. The trailing clear action is absent
 * for an empty query and restores editing focus after clearing a nonempty one.
 *
 * Wire into owning list screens only after their current P0/navigation changes
 * have settled; this component has no engine or remote side effects.
 */
@Composable
internal fun ClearableTransferSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val fieldFocus = remember { FocusRequester() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        singleLine = true,
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(
                    onClick = {
                        onValueChange("")
                        // A user can immediately type a different search without
                        // tapping the field again. Request focus only on a click.
                        fieldFocus.requestFocus()
                    },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = "Clear search" }
                ) {
                    Text("×", style = MaterialTheme.typography.headlineSmall)
                }
            }
        } else null,
        modifier = modifier.fillMaxWidth().focusRequester(fieldFocus)
    )
}
