package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.SmartRoutingPolicyStore
import io.github.gamesbyian.chunks.model.SmartRoutingPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SmartRoutingSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val wood = "b".repeat(64)

    @Before fun clearIsolatedRoutingPolicy() {
        assertTrue(context.getSharedPreferences("chunks-smart-routing-policy", Context.MODE_PRIVATE)
            .edit().clear().commit())
    }

    @Test fun editRulePersistsSameIdentityPriorityAndConfidence() {
        val store = SmartRoutingPolicyStore(context)
        val initial = SmartRoutingPreview.Rule("tv-rule", 11, SmartRoutingPreview.Kind.TV, 92, "local")
        assertEquals(SmartRoutingPolicyStore.Save.SAVED, store.save(0L,
            SmartRoutingPolicyStore.Policy(enabled = true, rules = listOf(initial))))
        compose.setContent {
            ChunksTheme {
                SmartRoutingSettingsScreen(
                    targets = listOf(SmartRoutingTargetChoice("local", "Local"),
                        SmartRoutingTargetChoice(wood, "Wood")),
                    onBack = {}
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("1. TV episode → Local").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.onNodeWithText("Audiobook").performScrollTo().performClick()
        compose.onNodeWithText("Wood").performScrollTo().performClick()
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            (SmartRoutingPolicyStore(context).read() as? SmartRoutingPolicyStore.Read.Ready)
                ?.revision == 2L
        }
        val persisted = (SmartRoutingPolicyStore(context).read() as SmartRoutingPolicyStore.Read.Ready)
            .policy.rules.single()
        assertEquals(initial.id, persisted.id)
        assertEquals(initial.priority, persisted.priority)
        assertEquals(initial.minimumConfidence, persisted.minimumConfidence)
        assertEquals(SmartRoutingPreview.Kind.AUDIOBOOK, persisted.kind)
        assertEquals(wood, persisted.targetEnvironmentId)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("1. Audiobook → Wood").fetchSemanticsNodes().isNotEmpty()
        }
        // Removing a rule while editing it must not leave a stale Save action.
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.onNodeWithText("Remove").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("New rule").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Save changes").assertDoesNotExist()
        compose.onNodeWithText("New rule").assertExists()
        assertTrue((SmartRoutingPolicyStore(context).read() as SmartRoutingPolicyStore.Read.Ready)
            .policy.rules.isEmpty())
    }

    @Test fun tryANameExplainsTheRuleWithoutSavingAnything() {
        val store = SmartRoutingPolicyStore(context)
        assertEquals(SmartRoutingPolicyStore.Save.SAVED, store.save(0L, SmartRoutingPolicyStore.Policy(
            enabled = true, rules = listOf(SmartRoutingPreview.Rule("books", 1, SmartRoutingPreview.Kind.BOOK, 60, wood)))))
        compose.setContent {
            ChunksTheme {
                SmartRoutingSettingsScreen(
                    targets = listOf(SmartRoutingTargetChoice("local", "Local"), SmartRoutingTargetChoice(wood, "Wood")),
                    onBack = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Try a name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chunks.smart_routing.try").performScrollTo()
            .performTextReplacement("Collection\nCollection/One.epub\nCollection/Two.epub")
        compose.onNodeWithTag("chunks.smart_routing.try_result").performScrollTo()
            .assertTextContains("Rule 1 suggests Wood", substring = true)
        compose.onNodeWithTag("chunks.smart_routing.try").performTextReplacement("Show.S01E02.mkv")
        compose.onNodeWithTag("chunks.smart_routing.try_result")
            .assertTextContains("No rule is sure enough, so Add asks", substring = true)
        assertEquals("explaining never saves", 1L,
            (SmartRoutingPolicyStore(context).read() as SmartRoutingPolicyStore.Read.Ready).revision)
    }
}
