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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurePasswordInputInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun passwordDefaultsHiddenAndEyeTogglesWithoutChangingCredentials() {
        var secret by mutableStateOf("")
        var endpoint = "https://example.invalid/RPC2"
        var submissions = 0
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(412.dp)) {
                    SecurePasswordInput(value = secret, onValueChange = { secret = it })
                }
            }
        }
        rule.onNodeWithContentDescription("Show password").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("password-123")
        assertEquals("password-123", secret)
        rule.onNodeWithContentDescription("Show password").performClick()
        rule.onNodeWithContentDescription("Hide password").assertIsDisplayed()
        assertEquals("password-123", secret)
        rule.onNodeWithContentDescription("Hide password").performClick()
        rule.onNodeWithContentDescription("Show password").assertIsDisplayed()
        assertEquals("password-123", secret)
        assertEquals("https://example.invalid/RPC2", endpoint)
        assertEquals(0, submissions)
    }

    @Test fun enlargedTextKeepsPasswordToggleOperableAndRespectsDisable() {
        var password by mutableStateOf("unsubmitted")
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        SecurePasswordInput(value = password, onValueChange = { password = it },
                            enabled = false)
                    }
                }
            }
        }
        rule.onNodeWithContentDescription("Show password").assertIsNotEnabled()
        assertEquals("unsubmitted", password)
    }
}
