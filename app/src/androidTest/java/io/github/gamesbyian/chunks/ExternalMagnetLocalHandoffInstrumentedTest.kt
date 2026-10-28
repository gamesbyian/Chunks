package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed MainActivity intent journey without remote credentials or a
 * torrent Add. A real Android VIEW of a magnet must reach local Add review.
 */
@RunWith(AndroidJUnit4::class)
class ExternalMagnetLocalHandoffInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)

    private fun openMagnet() {
        rule.runOnUiThread {
            // ActivityScenario must retain ownership of its launched Activity.
            // Starting a second MainActivity can strand the first PAUSED at
            // teardown. Instrumentation dispatches the OS lifecycle callback
            // to the same instance without replacing the test's Activity.
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .callActivityOnNewIntent(
                    rule.activity,
                    Intent(Intent.ACTION_VIEW, Uri.parse(magnet))
                )
        }
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("On this phone (experimental)")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(magnet).assertExists()
    }

    @Test fun androidMagnetOpensLocalReviewWithoutRequiringRtorrentLoginOrStartingIt() {
        openMagnet()
        rule.onNodeWithText("Start downloading after Add (recommended)").assertExists()
        // An inbound VIEW is only a text prefill; starting still requires
        // pressing Add. Android may freely reopen the same magnet afterward.
        rule.onNodeWithText("Back").performClick()
        openMagnet()
        assertTrue(rule.onAllNodesWithText(magnet).fetchSemanticsNodes().isNotEmpty())
    }
}
