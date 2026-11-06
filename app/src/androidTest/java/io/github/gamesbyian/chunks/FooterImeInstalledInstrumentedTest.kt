package io.github.gamesbyian.chunks

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed phone-first app on a real emulator soft keyboard: the persistent
 * footer yields while the IME is shown and returns when the keyboard's Search
 * key closes it, with the typed search kept. Then the phone search survives
 * Activity recreation. Navigation and view state only; nothing is added.
 */
@RunWith(AndroidJUnit4::class)
class FooterImeInstalledInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val searchLabel = "Search local names, aliases, folders or hashes"

    private fun footerShown() =
        rule.onAllNodesWithContentDescription("Options on this phone").fetchSemanticsNodes().isNotEmpty()

    private fun imeShown(): Boolean {
        var shown = false
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            shown = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return shown
    }

    private fun waitForPhone() = rule.waitUntil(20_000) {
        rule.onAllNodesWithText("On this phone (experimental)").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun footerYieldsToTheSoftKeyboardAndReturnsAfterSearchKey() {
        waitForPhone()
        rule.waitUntil(10_000) { footerShown() }
        val field = rule.onNode(hasSetTextAction() and hasText(searchLabel))
        // Accessibility actions can reach an unfocused Activity. A real tap
        // after window focus is the ordinary foreground keyboard journey.
        rule.waitUntil(15_000) { rule.activity.hasWindowFocus() }
        field.performScrollTo().performTouchInput { click() }
        val keyboardAppeared = runCatching { rule.waitUntil(15_000) { imeShown() } }.isSuccess
        assumeTrue("This emulator did not show a soft keyboard; no IME evidence possible", keyboardAppeared)
        rule.waitUntil(10_000) { !footerShown() }
        field.performTextInput("ubuntu")
        field.performImeAction()
        rule.waitUntil(15_000) { !imeShown() && footerShown() }
        rule.onNode(hasSetTextAction() and hasText("ubuntu")).assertExists()
    }

    @Test fun phoneSearchSurvivesActivityRecreation() {
        waitForPhone()
        rule.onNode(hasSetTextAction() and hasText(searchLabel)).performScrollTo().performTextInput("debian")
        rule.activityRule.scenario.recreate()
        waitForPhone()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("debian", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasSetTextAction() and hasText("debian")).assertExists()
    }
}
