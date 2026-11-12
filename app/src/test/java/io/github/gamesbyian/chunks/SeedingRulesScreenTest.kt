package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
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
    private val environments = listOf(SeedingEnvironment.local(), box)

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label, substring = true))

    @Test fun compactExplanationLeavesRuleScopeTruthfulAndDetailsOneTapAway() {
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                SeedingRulesScreen(environments) {}
            } }
        }
        rule.onNodeWithText(
            "Local device rules affect new transfers only. Server rules are saved for later and are not applied yet."
        ).assertExists()
        rule.onNodeWithText("On this device, rules apply to transfers added from now on", substring = true)
            .assertDoesNotExist()
        rule.onNodeWithText("What these rules do today").performClick()
        rule.onNodeWithText("On this device, rules apply to transfers added from now on", substring = true)
            .assertExists()
        rule.onNodeWithText("Hide rule explanation").performClick()
        rule.onNodeWithText("On this device, rules apply to transfers added from now on", substring = true)
            .assertDoesNotExist()
        rule.onNodeWithText("New rule").assertExists()
    }

    @Test fun createAScopedRuleWithExactUnitsAndItPersists() {
        rule.setContent { ChunksTheme { Surface(color = ChunksColors.Background) { SeedingRulesScreen(environments) {} } } }
        rule.onNodeWithText("What these rules do today").assertExists()
        rule.onNodeWithText("New rule").performClick()
        field("Tracker domains").performTextReplacement("https://tracker.example/announce")
        rule.onNodeWithText("is not a tracker domain", substring = true).assertExists()
        rule.onNodeWithText("Save rule").performScrollTo().assertIsNotEnabled()
        field("Tracker domains").performTextReplacement("iptorrents.com")
        field("Stop at ratio").performScrollTo().performTextReplacement("1")
        field("Upload limit").performScrollTo().performTextReplacement("24 kbps")
        rule.onNodeWithText("24 kbit/s (2.9 KiB/s)").assertExists()
        // Only the local device, not the server.
        rule.onNodeWithText(box.label).performScrollTo().performClick()
        rule.onRoot().captureRoboImage("build/chunks-visual/seeding-editor.png")
        rule.onNodeWithText("Save rule").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("1. Iptorrents · Ratio 1.00 · 24 kbit/s").assertExists()
        rule.onNodeWithText("Applies to: On this device").assertExists()
        rule.onNodeWithText("Local: matching future additions only").assertExists()
        rule.onNodeWithText("Servers: saved for later", substring = true).assertDoesNotExist()

        val stored = SeedingRuleStore(context).load().rules.single()
        assertEquals(setOf("iptorrents.com"), stored.trackerDomains)
        assertEquals(setOf(SeedingEnvironment.LOCAL_ID), stored.environments)
        assertEquals(3_000L, stored.policy.uploadLimit!!.bytesPerSecond)
        assertTrue("the server is known, so it is not a future environment", box.id in stored.knownEnvironments)
        assertFalse(stored.nameIsCustom)
        // Searching changes only the visible list, never its persisted policy.
        field("Find seeding rules").performTextReplacement("unrelated-query")
        rule.onNodeWithText("No rules match this search.", substring = true).assertExists()
        assertEquals(1, SeedingRuleStore(context).load().rules.size)
        field("Find seeding rules").performTextReplacement("IPTORRENTS")
        rule.onNodeWithText("1. Iptorrents · Ratio 1.00 · 24 kbit/s").assertExists()
        assertEquals(1, matchingSeedingRules(listOf(stored), "ipt", mapOf(box.id to box.label)).size)
        rule.onRoot().captureRoboImage("build/chunks-visual/seeding-list.png")
    }

    @Test fun savedDefaultExplainsItsScopeAndCanBeResetWithoutChangingOtherPolicies() {
        val store = SeedingRuleStore(context)
        assertTrue(store.save(SeedingRuleStore.Snapshot(
            emptyList(), mapOf(SeedingEnvironment.LOCAL_ID to io.github.gamesbyian.chunks.seeding.SeedingPolicy())
        )))
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) { SeedingRulesScreen(environments) {} } }
        }
        rule.onNodeWithText("Review defaults · 2 environments · 1 saved").assertExists()
        rule.onNodeWithText("Using your saved preference", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Review defaults", substring = true).performClick()
        rule.onNodeWithText("Using your saved preference", substring = true).assertExists()
        rule.onNodeWithText("Reset to automatic").performScrollTo().performClick()
        rule.onNodeWithText("Restore the automatic default for On this device?", substring = true).assertExists()
        rule.onNodeWithText("Keep default").performClick()
        assertTrue(store.load().defaults.containsKey(SeedingEnvironment.LOCAL_ID))
        rule.onNodeWithText("Reset to automatic").performScrollTo().performClick()
        rule.onNodeWithText("Confirm reset").performClick()
        assertFalse(store.load().defaults.containsKey(SeedingEnvironment.LOCAL_ID))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi", fontScale = 2.0f)
    fun searchingDefaultEnvironmentsIsReadOnlyAtSmallLargeTextSize() {
        val store = SeedingRuleStore(context)
        val saved = io.github.gamesbyian.chunks.seeding.SeedingPolicy()
        assertTrue(store.save(SeedingRuleStore.Snapshot(
            emptyList(), mapOf(SeedingEnvironment.LOCAL_ID to saved))))
        assertEquals(listOf(SeedingEnvironment.local()),
            matchingSeedingEnvironments(environments, "On THIS device"))
        assertEquals(environments,
            matchingSeedingEnvironments(environments, "  "))
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                SeedingRulesScreen(environments) {}
            } }
        }
        rule.onNodeWithText("Review defaults", substring = true).performClick()
        field("Find environment defaults").performScrollTo()
            .performTextReplacement("no-such-environment")
        rule.onNodeWithText("No environment defaults match this search", substring = true)
            .assertExists()
        assertTrue(store.load().defaults.containsKey(SeedingEnvironment.LOCAL_ID))
        field("Find environment defaults").performScrollTo().performTextReplacement("local")
        rule.onNodeWithText("Showing 1 of 2 environment defaults").assertExists()
        rule.onNodeWithText("Using your saved preference", substring = true).assertExists()
        assertEquals(1, store.load().defaults.size)
    }

    @Test fun searchRemainsClearableWhenAnEnvironmentDisappears() {
        val available = mutableStateOf(environments)
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                SeedingRulesScreen(available.value) {}
            } }
        }
        rule.onNodeWithText("Review defaults", substring = true).performClick()
        field("Find environment defaults").performScrollTo().performTextReplacement("no-such-environment")
        rule.runOnIdle { available.value = listOf(SeedingEnvironment.local()) }
        field("Find environment defaults").performScrollTo().performTextReplacement("")
        rule.onNodeWithText("Affects future local transfers", substring = true).assertExists()
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
