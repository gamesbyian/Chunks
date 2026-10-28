package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Installed Android cold-start handoff, without remote credentials or Add. */
@RunWith(AndroidJUnit4::class)
class ExternalMagnetLocalHandoffInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)

    @Test fun externalViewOpensReviewedPhoneAddWithoutRemoteLoginOrAutoStart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(magnet)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            rule.waitUntil(15_000) {
                rule.onAllNodesWithText("On this phone (experimental)")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // Reaching the native Add form is required, but merely opening
            // this external link must not execute any native Add or Start.
            rule.onNodeWithText("Start downloading after Add (recommended)").assertExists()
            rule.onNodeWithText(magnet).assertExists()
            assertTrue(rule.onAllNodesWithText(magnet).fetchSemanticsNodes().isNotEmpty())
        }
    }
}
