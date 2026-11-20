package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1838: the Add artwork option must preserve both disclosure and opt-out
 * across disposal of the lazy item's subtree. No backend Add is dispatched.
 */
@RunWith(AndroidJUnit4::class)
class AddArtworkDisclosureRetentionInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun secondAddRecompositionKeepsExpandedOptOutOutsideLazyItem() {
        rule.setContent {
            val showArtworkItem = remember { mutableStateOf(true) }
            val disclosure = rememberSaveable { mutableStateOf(false) }
            val automatic = remember { mutableStateOf(true) }
            Column {
                OutlinedButton(onClick = { showArtworkItem.value = !showArtworkItem.value },
                    modifier = Modifier.testTag("test.toggle.artwork.item")) {
                    Text("Toggle test item")
                }
                if (showArtworkItem.value) {
                    AddArtworkChoice(
                        automatic = automatic.value,
                        enabled = true,
                        offeredByParent = true,
                        expandedState = disclosure,
                        onAutomatic = { automatic.value = it }
                    )
                }
            }
        }
        val option = hasTestTag(ChunksSemantics.ADD_ARTWORK_CHOICE)
        rule.onNodeWithText("Artwork & metadata").performClick()
        rule.onNode(option).assertExists().performClick()
        assertEquals(ToggleableState.Off,
            rule.onNode(option).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])

        // A LazyColumn item can leave composition entirely, then return with
        // the same retained Add attempt. Never click the opt-out a second time.
        rule.onNode(hasTestTag("test.toggle.artwork.item")).performClick()
        rule.onNode(option).assertDoesNotExist()
        rule.onNode(hasTestTag("test.toggle.artwork.item")).performClick()
        rule.onNode(option).assertExists()
        assertEquals(ToggleableState.Off,
            rule.onNode(option).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
    }
}
