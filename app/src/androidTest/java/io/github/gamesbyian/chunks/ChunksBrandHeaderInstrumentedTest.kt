package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The illustrative blocks must not create four meaningless TalkBack stops. */
@RunWith(AndroidJUnit4::class)
class ChunksBrandHeaderInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun brandAndTaglineAreOneAccessibleHeading() {
        rule.setContent {
            MaterialTheme { ChunksBrandHeader() }
        }
        rule.onNodeWithContentDescription("chunks. Your downloads have places to be.")
            .assertExists()
            .assertIsDisplayed()
        // The visual pieces still render as actual scalable native text.
        rule.onNodeWithText("chunks", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Your downloads have places to be.", useUnmergedTree = true)
            .assertExists()
    }
}
