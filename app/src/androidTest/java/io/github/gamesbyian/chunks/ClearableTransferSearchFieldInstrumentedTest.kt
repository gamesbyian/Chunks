package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated native Compose behavior, not installed whole-app or actual system-font evidence. */
@RunWith(AndroidJUnit4::class)
class ClearableTransferSearchFieldInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun emptyQueryHasNoClearActionAndClearingRestoresEditingAtNarrowLargeText() {
        var query by mutableStateOf("")
        var direction = "Newest first"
        var environment = "phone"
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        ClearableTransferSearchField(
                            value = query,
                            onValueChange = { query = it },
                            label = "Search transfers"
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("Clear search").assertDoesNotExist()
        rule.onNode(hasSetTextAction()).performTextInput("Linux")
        rule.onNodeWithText("Clear search").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals("", query)
        rule.onNodeWithText("Clear search").assertDoesNotExist()
        assertEquals(
            true,
            rule.onNode(hasSetTextAction()).fetchSemanticsNode()
                .config[SemanticsProperties.Focused]
        )
        rule.onNode(hasSetTextAction()).performTextInput("Debian")
        assertEquals("Debian", query)
        assertEquals("Newest first", direction)
        assertEquals("phone", environment)
    }

    @Test fun populatedQueryCanClearAtOrdinaryWidthWithoutChangingExternalState() {
        var query by mutableStateOf("studio")
        var selected = setOf("transfer-A")
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(412.dp)) {
                    ClearableTransferSearchField(
                        value = query,
                        onValueChange = { query = it },
                        label = "Search names, aliases, folders or hashes"
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Clear search").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals("", query)
        assertEquals(setOf("transfer-A"), selected)
        rule.onNodeWithContentDescription("Clear search").assertDoesNotExist()
    }
}
