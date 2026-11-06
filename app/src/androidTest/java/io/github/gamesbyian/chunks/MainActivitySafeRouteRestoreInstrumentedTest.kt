package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToNode
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
        rule.onNodeWithText("On this phone (experimental)").assertExists()
        rule.onNodeWithText("All environments…").assertExists()

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("All environments…").assertExists()
        rule.onNodeWithText("Your downloads").assertDoesNotExist()

        rule.onNodeWithText("All environments…").performScrollTo().performClick()
        rule.onNodeWithText("All environments").assertExists()

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("All environments").assertExists()
        // The list remains navigable after recreation without an oversized intro panel.
        // Compact Combined browsing intentionally hides its search/filter panel
        // until explicitly requested; verify that disclosure survives recreation.
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(
            hasText("Refine transfers", substring = true))
        rule.onNode(hasText("Refine transfers", substring = true)).performClick()
        rule.onNodeWithText("Search names or hashes").assertExists()
        rule.onNodeWithText("Stop selected").assertDoesNotExist()
        rule.onNodeWithText("Start selected").assertDoesNotExist()
        // With no saved servers, the phone is the only configured Add target.
        rule.onNodeWithText("Other saved servers need their own connection before adding.")
            .assertDoesNotExist()
        // The launch surface survives Activity recreation. Back must return to
        // the local list, never send a phone-only user to remote server setup.
        rule.onNodeWithText("Back").performClick()
        rule.onNodeWithText("On this phone (experimental)").assertExists()
        rule.onNodeWithText("All environments…").assertExists()
        rule.onNodeWithText("Your downloads").assertDoesNotExist()

        // Enter phone from Combined, recreate the actual Activity on that
        // phone page, and press its own Back. Only the navigation return edge
        // may survive: no server login, selected operation or Add draft.
        rule.onNodeWithText("All environments…").performScrollTo().performClick()
        val phoneAdd = hasContentDescription("Add download on this phone")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(phoneAdd)
        rule.onNodeWithContentDescription("Add download on this phone").performClick()
        rule.onNodeWithText("On this phone (experimental)").assertExists()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("On this phone (experimental)").assertExists()
        rule.onNodeWithText("Back").performScrollTo().performClick()
        rule.onNodeWithText("All environments").assertExists()
        rule.onNodeWithText("Stop selected").assertDoesNotExist()
        rule.onNodeWithText("Start selected").assertDoesNotExist()
    }

    @Test fun createRouteDropsPendingReviewAndReturnsToDisconnectedEntry() {
        // The offline tools remain available from the disconnected server setup.
        rule.onNodeWithText("Back").performScrollTo().performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("More offline tools"))
        rule.onNodeWithText("More offline tools").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Create a torrent from phone files"))
        rule.onNodeWithText("Create a torrent from phone files").performClick()
        rule.onNodeWithText("Your downloads").assertDoesNotExist()

        // A restore may never manufacture a pending file-selection or create
        // command. The authenticated remote session remains absent.
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("Your downloads").assertExists()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("More offline tools"))
        rule.onNodeWithText("More offline tools").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Create a torrent from phone files"))
        rule.onNodeWithText("Create a torrent from phone files").assertExists()
        rule.onNodeWithText("Create torrent").assertDoesNotExist()
    }
}
