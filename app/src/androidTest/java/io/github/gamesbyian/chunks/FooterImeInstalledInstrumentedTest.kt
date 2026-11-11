package io.github.gamesbyian.chunks

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
 * Installed first-run Welcome → canonical Transfers on a real emulator soft
 * keyboard. The footer yields while IME is shown and returns when Search closes
 * it; typed catalog search survives Activity recreation. Navigation/view state
 * only; no torrent admission or server connection is attempted.
 */
@RunWith(AndroidJUnit4::class)
class FooterImeInstalledInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    // A genuine fresh install shows the Welcome, which deliberately has no catalog search
    // (#1512). The enduring requirement is the footer through a real soft keyboard and a typed
    // draft surviving recreation, so both are exercised on the Add source field, reachable from
    // the Welcome by the stable footer id. (Reconciled 2026-11-11; see TEST-ARCHITECTURE section 5.)
    private val field = hasTestTag(ChunksSemantics.ADD_SOURCE_FIELD)

    private fun footerShown() =
        rule.onAllNodes(hasTestTag(ChunksSemantics.FOOTER_OPTIONS)).fetchSemanticsNodes().isNotEmpty()

    private fun imeShown(): Boolean {
        var shown = false
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            shown = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return shown
    }

    private fun openAddFromWelcome() {
        rule.waitUntil(20_000) { footerShown() }
        rule.onNode(hasTestTag(ChunksSemantics.FOOTER_ADD)).performClick()
        rule.waitUntil(20_000) { rule.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun footerRemainsAvailableThroughSoftKeyboardAndAfterSearchKey() {
        openAddFromWelcome()
        rule.waitUntil(10_000) { footerShown() }
        val input = rule.onNode(field)
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
        input.performScrollTo().performTouchInput { click() }
        val keyboardAppeared = runCatching { rule.waitUntil(15_000) { imeShown() } }.isSuccess
        assumeTrue("This emulator did not show a soft keyboard; no IME evidence possible", keyboardAppeared)
        rule.waitUntil(10_000) { footerShown() }
        input.performTextInput("ubuntu")
        input.performImeAction()
        rule.waitUntil(15_000) { !imeShown() && footerShown() }
        rule.onNode(hasTestTag(ChunksSemantics.ADD_SOURCE_FIELD) and hasText("ubuntu", substring = true)).assertExists()
    }

    @Test fun catalogSearchSurvivesActivityRecreation() {
        openAddFromWelcome()
        rule.onNode(field).performScrollTo().performTextInput("debian")
        rule.activityRule.scenario.recreate()
        // Route restore may land on the canonical root; the typed draft must survive either way.
        rule.waitUntil(20_000) { footerShown() }
        if (rule.onAllNodes(field).fetchSemanticsNodes().isEmpty()) openAddFromWelcome()
        rule.waitUntil(10_000) {
            rule.onAllNodes(field and hasText("debian", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
