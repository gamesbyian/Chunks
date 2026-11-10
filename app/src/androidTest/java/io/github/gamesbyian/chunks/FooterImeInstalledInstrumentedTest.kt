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
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed canonical Transfers on a real emulator: an explicitly selected
 * device-only empty filter keeps search visible (unlike the fresh Welcome).
 * The persistent footer remains usable with IME; search survives Activity
 * recreation. Navigation and view preferences only, no torrent is added.
 */
@RunWith(AndroidJUnit4::class)
class FooterImeInstalledInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val searchLabel = "Search names or hashes"

    private fun footerShown() =
        rule.onAllNodesWithContentDescription("Options").fetchSemanticsNodes().isNotEmpty()

    private fun imeShown(): Boolean {
        var shown = false
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            shown = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return shown
    }

    private fun enterFilteredCanonicalTransfers() {
        // Seed only a VIEW preference, never a torrent. An explicitly chosen
        // device scope must retain management/search even when its list is empty.
        rule.runOnIdle {
            CombinedViewPreferences(rule.activity).save(
                io.github.gamesbyian.chunks.model.UnifiedTransferQuery(
                    scope = io.github.gamesbyian.chunks.model.EnvironmentViewScope.Only(
                        setOf(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.PHONE))))
        }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText(searchLabel).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun footerRemainsAvailableThroughSoftKeyboardAndAfterSearchKey() {
        enterFilteredCanonicalTransfers()
        rule.waitUntil(10_000) { footerShown() }
        val field = rule.onNode(hasSetTextAction() and hasText(searchLabel))
        // Accessibility actions can reach an unfocused Activity. A real tap
        // after window focus is the ordinary foreground keyboard journey.
        // Two independent installed runs showed Android's *external* Pixel
        // Launcher ANR stealing focus from an otherwise responsive Chunks screen.
        // Dismiss only that exact system dialog's Wait button once, then demand
        // real Chunks focus. Never dismiss an app ANR, permission or Add dialog,
        // and never treat a dismissal as evidence that the IME journey passed.
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var externalLauncherWaitClicked = false
        var nextProbeAt = 0L
        rule.waitUntil(15_000) {
            if (rule.activity.hasWindowFocus()) true else {
                val now = SystemClock.elapsedRealtime()
                if (!externalLauncherWaitClicked && now >= nextProbeAt) {
                    nextProbeAt = now + 500L
                    val launcher = device.findObject(By.text("Pixel Launcher isn't responding"))
                    val wait = if (launcher != null) device.findObject(By.text("Wait")) else null
                    if (wait != null) {
                        externalLauncherWaitClicked = runCatching { wait.click(); true }.getOrDefault(false)
                    }
                }
                false
            }
        }
        field.performScrollTo().performTouchInput { click() }
        val keyboardAppeared = runCatching { rule.waitUntil(15_000) { imeShown() } }.isSuccess
        assumeTrue("This emulator did not show a soft keyboard; no IME evidence possible", keyboardAppeared)
        rule.waitUntil(10_000) { footerShown() }
        field.performTextInput("ubuntu")
        field.performImeAction()
        rule.waitUntil(15_000) { !imeShown() && footerShown() }
        rule.onNode(hasSetTextAction() and hasText("ubuntu")).assertExists()
    }

    @Test fun phoneSearchSurvivesActivityRecreation() {
        enterFilteredCanonicalTransfers()
        rule.onNode(hasSetTextAction() and hasText(searchLabel)).performScrollTo().performTextInput("debian")
        rule.activityRule.scenario.recreate()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText(searchLabel).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("debian", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasSetTextAction() and hasText("debian")).assertExists()
    }
}
