package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishInOrderAddChoiceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun defaultRowAppearsOnlyOnceChosenAndUntickingThisAddKeepsTheDefault() {
        val saved = ArrayList<Boolean>()
        rule.setContent {
            var defaultOn by remember { mutableStateOf(false) }
            var checked by remember { mutableStateOf(defaultOn) }
            FinishInOrderAddChoice("Finish in Order", checked, enabled = true, onChecked = { checked = it },
                defaultOn = defaultOn, onDefault = { saved += it; defaultOn = it })
        }
        val defaultLabel = "Tick this for new multi-file Adds"
        rule.onNodeWithText(defaultLabel, substring = true).assertDoesNotExist()
        rule.onNodeWithText("Finish in Order").performClick()
        rule.onNodeWithText(defaultLabel, substring = true).assertIsOff().performClick()
        rule.onNodeWithText(defaultLabel, substring = true).assertIsOn()
        // Unticking this Add leaves the saved default visible and on.
        rule.onNodeWithText("Finish in Order").performClick()
        rule.onNodeWithText("Finish in Order").assertIsOff()
        rule.onNodeWithText(defaultLabel, substring = true).assertIsOn()
        assertEquals(listOf(true), saved)
    }
}
