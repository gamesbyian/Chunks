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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onLast
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
        val before = LocalEngineHolder.current()?.completeIdentityInventory().orEmpty()
        rule.onNodeWithText("Welcome to Chunks").assertExists()
        rule.onNodeWithText("This device").assertDoesNotExist()
        rule.onNodeWithText("+ Add").assertExists()
        rule.onNodeWithContentDescription("Connections").assertExists()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("Welcome to Chunks").assertExists()
        // A genuinely empty first-run view has no scope chip to tap.
        // The persistent Transfers footer reaches the existing canonical
        // catalog without an authenticated session or any job mutation.
        rule.onNodeWithText("All environments").assertDoesNotExist()
        rule.onAllNodes(hasText("Transfers") and hasClickAction()).onLast().performClick()
        rule.onNodeWithText("All environments").assertExists()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("All environments").assertExists()
        // Root navigation remains canonical across another recreation.
        rule.onAllNodes(hasText("Transfers") and hasClickAction()).onLast().performClick()
        rule.onNodeWithText("All environments").assertExists()
        // A local Add shell can survive rotation, never its source or approval.
        rule.onNodeWithText("+ Add").performClick()
        rule.onNodeWithText("Add a download on this device").assertExists()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("Add a download on this device").assertExists()
        rule.onNodeWithText("Or paste a magnet or torrent link").assertExists()
        rule.onNodeWithText("Start selected").assertDoesNotExist()
        org.junit.Assert.assertEquals("Navigation cannot create torrent jobs", before,
            LocalEngineHolder.current()?.completeIdentityInventory().orEmpty())
    }

    @Test fun createRouteDropsPendingReviewAndReturnsToDisconnectedEntry() {
        // The offline tools remain available from the disconnected server setup.
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("More offline tools"))
        rule.onNodeWithText("More offline tools").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Create a torrent from phone files"))
        rule.onNodeWithText("Create a torrent from phone files").performClick()
        rule.onNodeWithText("Your downloads").assertDoesNotExist()

        // A restore may never manufacture a pending file-selection or create
        // command. The authenticated remote session remains absent.
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("Welcome to Chunks").assertExists()
        rule.onNodeWithText("This device").assertDoesNotExist()
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("More offline tools"))
        rule.onNodeWithText("More offline tools").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Create a torrent from phone files"))
        rule.onNodeWithText("Create a torrent from phone files").assertExists()
        rule.onNodeWithText("Create torrent").assertDoesNotExist()
    }
}
