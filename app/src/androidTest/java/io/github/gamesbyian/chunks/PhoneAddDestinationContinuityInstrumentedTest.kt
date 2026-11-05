package io.github.gamesbyian.chunks

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses actual Android exclusive-file/write probes; Robolectric cannot grant this capability. */
@RunWith(AndroidJUnit4::class)
class PhoneAddDestinationContinuityInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun clickReady(text: String) {
        rule.waitUntil(20_000) {
            runCatching { rule.onNodeWithText(text).performScrollTo().assertIsEnabled() }.isSuccess
        }
        rule.onNodeWithText(text).performClick()
    }

    private fun awaitPlace() = rule.waitUntil(20_000) {
        rule.onAllNodesWithText("For this Add only. Saving a phone default is a separate action.")
            .fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun oneTimeDestinationSubfolderAndStoppedChoiceSurviveNavigationAndRecreation() {
        val context = rule.activity
        val prefs = context.getSharedPreferences("local-download-place", android.content.Context.MODE_PRIVATE)
        val oldKind = prefs.getString("default-kind", null)
        val oldRoot = prefs.getString("resolved-root", null)
        try {
            rule.onNodeWithContentDescription("Add torrent on this phone").performClick()
            rule.waitUntil(20_000) {
                rule.onAllNodesWithText("Change download location").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Change download location").performScrollTo().performClick()
            rule.onNodeWithText("Chunks app folder").performScrollTo().assertIsEnabled().performClick()
            awaitPlace()
            rule.onNodeWithText("Optional phone download subfolder").performScrollTo().performTextInput("chosen-once")
            rule.onNodeWithText("Start downloading after Add (recommended)").performScrollTo().performClick()
            rule.onNodeWithText("Or paste a magnet or torrent link").performScrollTo()
                .performTextInput("magnet:?xt=urn:btih:" + "b".repeat(40))
            rule.onNodeWithText("All environments…").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Back").performClick()
            awaitPlace()
            rule.onNodeWithText("Optional phone download subfolder").assertTextContains("chosen-once")
            rule.onNodeWithText("Start downloading after Add (recommended)").assertIsOff()
            org.junit.Assert.assertEquals("A one-time choice must not persist", oldKind, prefs.getString("default-kind", null))
            rule.activityRule.scenario.recreate()
            awaitPlace()
            rule.onNodeWithText("Optional phone download subfolder").assertTextContains("chosen-once")
            rule.onNodeWithText("Start downloading after Add (recommended)").assertIsOff()
            org.junit.Assert.assertEquals(oldKind, prefs.getString("default-kind", null))
            clickReady("Save this location as phone default")
            rule.waitUntil(20_000) {
                prefs.getString("default-kind", null) == LocalPlaceKind.APP_STORAGE.key &&
                    runCatching { rule.onNodeWithText("Use phone default for this Add").assertIsEnabled() }.isSuccess
            }
            rule.onNodeWithText("Phone default: Chunks app folder").assertExists()
            rule.activityRule.scenario.recreate()
            awaitPlace()
            rule.onNodeWithText("Phone default: Chunks app folder").assertExists()
            clickReady("Use phone default for this Add")
            rule.onNodeWithText("Your saved default for future phone downloads.").assertExists()
            clickReady("Use automatic location for new phone downloads")
            rule.waitUntil(20_000) { prefs.getString("default-kind", null) == null }
        } finally {
            prefs.edit().apply {
                if (oldKind == null) remove("default-kind") else putString("default-kind", oldKind)
                if (oldRoot == null) remove("resolved-root") else putString("resolved-root", oldRoot)
            }.commit()
        }
    }
}
