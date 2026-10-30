package io.github.gamesbyian.chunks

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual installed MainActivity + SavedStateRegistry recreation, without a
 * server, an Add draft, a torrent, credentials or any native transfer mutation.
 * This is Activity recreation; process *kill* and re-launch require a separate
 * physical-device witness.
 *
 * Invocation:
 * connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=
 *   io.github.gamesbyian.chunks.MainActivitySafeRouteRestoreInstrumentedTest
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySafeRouteRestoreInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun disconnectedPhoneAndCombinedRoutesSurviveActivityRecreationWithoutCommands() {
        // The phone surface is reachable without authenticating any rTorrent
        // server and is wrapped by its own separate transfer privacy gate.
        rule.onNodeWithText("Download on this phone (experimental)")
            .performScrollTo().performClick()
        rule.onNodeWithText("All environments…").assertExists()

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("All environments…").assertExists()
        rule.onNodeWithText("Hello, server.").assertDoesNotExist()

        rule.onNodeWithText("All environments…").performScrollTo().performClick()
        rule.onNodeWithText("All environments").assertExists()

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("All environments").assertExists()
        rule.onNodeWithText("Browse every environment together.", substring = true).assertExists()
        rule.onNodeWithText("Stop selected").assertDoesNotExist()
        rule.onNodeWithText("Start selected").assertDoesNotExist()
    }

    @Test fun createRouteDropsPendingReviewAndReturnsToDisconnectedEntry() {
        rule.onNodeWithText("Create a torrent from phone files")
            .performScrollTo().performClick()
        rule.onNodeWithText("Hello, server.").assertDoesNotExist()

        // A restore may never manufacture a pending file-selection or create
        // command. The authenticated remote session remains absent.
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("Hello, server.").assertExists()
        rule.onNodeWithText("Create a torrent from phone files").assertExists()
        rule.onNodeWithText("Create torrent").assertDoesNotExist()
    }
}
