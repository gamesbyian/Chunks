package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.seeding.SeedingRuleStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SeedingRulesScreenTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val box = SeedingEnvironment.server("https://box.example/rutorrent2/plugins/httprpc/action.php", "ian")
    private val environments = listOf(SeedingEnvironment.phone(), box)

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label, substring = true))

    @Test fun createAScopedRuleWithExactUnitsAndItPersists() {
        rule.setContent { ChunksTheme { Surface(color = ChunksColors.Background) { SeedingRulesScreen(environments) {} } } }
        rule.onNodeWithText("Saved, not applied yet").assertExists()
        rule.onNodeWithText("New rule").performClick()
        field("Tracker domains").performTextReplacement("https://tracker.example/announce")
        rule.onNodeWithText("is not a tracker domain", substring = true).assertExists()
        rule.onNodeWithText("Save rule").performScrollTo().assertIsNotEnabled()
        field("Tracker domains").performTextReplacement("iptorrents.com")
        field("Stop at ratio").performScrollTo().performTextReplacement("1")
        field("Upload limit").performScrollTo().performTextReplacement("24 kbps")
        rule.onNodeWithText("24 kbit/s (2.9 KiB/s)").assertExists()
        // Only the phone, not the server.
        rule.onNodeWithText(box.label).performScrollTo().performClick()
        rule.onRoot().captureRoboImage("build/chunks-visual/seeding-editor.png")
        rule.onNodeWithText("Save rule").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("1. Iptorrents · Ratio 1.00 · 24 kbit/s").assertExists()
        rule.onNodeWithText("Applies to: On this phone").assertExists()

        val stored = SeedingRuleStore(context).load().rules.single()
        assertEquals(setOf("iptorrents.com"), stored.trackerDomains)
        assertEquals(setOf(SeedingEnvironment.PHONE_ID), stored.environments)
        assertEquals(3_000L, stored.policy.uploadLimit!!.bytesPerSecond)
        assertTrue("the server is known, so it is not a future environment", box.id in stored.knownEnvironments)
        assertFalse(stored.nameIsCustom)
        rule.onRoot().captureRoboImage("build/chunks-visual/seeding-list.png")
    }

    @Test fun ambiguousUnitsBlockSavingAndDeleteNeedsConfirmation() {
        rule.setContent { ChunksTheme { Surface(color = ChunksColors.Background) { SeedingRulesScreen(environments) {} } } }
        rule.onNodeWithText("New rule").performClick()
        field("Tracker domains").performTextReplacement("example.org")
        field("Upload limit").performScrollTo().performTextReplacement("24k")
        rule.onNodeWithText("is ambiguous", substring = true).assertExists()
        rule.onNodeWithText("Save rule").performScrollTo().assertIsNotEnabled()
        field("Upload limit").performScrollTo().performTextReplacement("")
        rule.onNodeWithText("Save rule").performScrollTo().performClick()
        rule.onNodeWithText("Delete…").performScrollTo().performClick()
        rule.onNodeWithText("Keep").performClick()
        assertEquals(1, SeedingRuleStore(context).load().rules.size)
        rule.onNodeWithText("Delete…").performScrollTo().performClick()
        rule.onNodeWithText("Delete rule").performClick()
        assertTrue(SeedingRuleStore(context).load().rules.isEmpty())
    }
}
