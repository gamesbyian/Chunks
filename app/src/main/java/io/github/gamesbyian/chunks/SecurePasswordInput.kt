package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Only the owning login screen holds the credential; this composable owns
 * presentation state (hidden by default) and never persists or logs secrets.
 * Revealing text does not alter the value, endpoint, authenticated identity,
 * callback, cursor text or storage preference.
 */
@Composable
internal fun SecurePasswordInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = "Password",
    /**
     * Optional keyboard Go action (for example Connect, a read-only check).
     * Null keeps the keyboard's ordinary Done key.
     */
    onGo: (() -> Unit)? = null
) {
    var visible by remember { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = if (onGo != null) androidx.compose.ui.text.input.ImeAction.Go
                else androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onGo?.invoke() }),
        trailingIcon = {
            IconButton(
                onClick = {
                    visible = !visible
                    inputFocus.requestFocus()
                },
                enabled = enabled,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics {
                        contentDescription = if (visible) "Hide password" else "Show password"
                        stateDescription = if (visible) "Password visible" else "Password hidden"
                    }
            ) {
                Icon(
                    painter = painterResource(if (visible)
                        R.drawable.chunks_ic_visibility_off else R.drawable.chunks_ic_visibility),
                    contentDescription = null
                )
            }
        },
        modifier = modifier.fillMaxWidth().focusRequester(inputFocus)
    )
}
