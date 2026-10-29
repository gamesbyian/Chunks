package io.github.gamesbyian.chunks

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class RemoteCapabilityAnnouncementsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun probeResultAndErrorReplaceOldTextWithinPersistentPoliteRegion() {
        val lines = mutableStateOf<List<String>?>(null)
        rule.setContent {
            ChunksTheme { RemoteCapabilityResultAnnouncements(lines.value) }
        }

        val liveRegion = SemanticsMatcher.expectValue(
            SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        rule.onNode(liveRegion).assertExists()
        rule.onNodeWithText("Advertised, not yet verified: Start and stop").assertDoesNotExist()

        rule.runOnIdle {
            lines.value = listOf("Advertised, not yet verified: Start and stop")
        }
        rule.onNode(liveRegion).assertExists()
        rule.onNodeWithText("Advertised, not yet verified: Start and stop").assertExists()

        // Starting the next probe must not leave its old result presented as current.
        rule.runOnIdle { lines.value = null }
        rule.onNode(liveRegion).assertExists()
        rule.onNodeWithText("Advertised, not yet verified: Start and stop").assertDoesNotExist()

        rule.runOnIdle {
            lines.value = listOf("The server did not answer the read-only capability check.")
        }
        rule.onNode(liveRegion).assertExists()
        rule.onNodeWithText("The server did not answer the read-only capability check.").assertExists()
    }
}
