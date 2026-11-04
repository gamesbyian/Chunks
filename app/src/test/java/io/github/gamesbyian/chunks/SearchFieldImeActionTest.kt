package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The keyboard's Search key closes the keyboard; the typed search is kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class SearchFieldImeActionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun searchKeyReleasesFocusAndKeepsTheQuery() {
        var value by mutableStateOf("")
        rule.setContent { ChunksTheme {
            // Real screens hold other focusable controls; with none, Compose
            // would hand initial focus straight back to the only field.
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.TextButton(onClick = {}) {
                    androidx.compose.material3.Text("Other control")
                }
                ClearableTransferSearchField(value, { value = it }, "Search transfers")
            }
        } }
        val field = rule.onNode(hasSetTextAction())
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Search))
        field.performClick()
        field.performTextInput("debian")
        field.assertIsFocused()
        field.performImeAction()
        rule.waitForIdle()
        field.assertIsNotFocused()
        assertEquals("debian", value)
    }
}
