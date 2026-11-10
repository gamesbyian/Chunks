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
        val before = LocalEngineHolder.current()?.completeIdentityInventory().orEmpty()
        rule.onNodeWithText("This device").assertExists()
        rule.onNodeWithText("+ Add").assertExists()
        rule.onNodeWithContentDescription("Connections").assertExists()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("This device").assertExists()
        // Combined is read-only even if this device is the only environment.
        rule.onNodeWithText("All environments").performClick()
        rule.onNodeWithText("All environments").assertExists()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("All environments").assertExists()
        // Footer Transfers no longer falls into a distinct legacy phone list.
        // It always returns to the canonical scope-filtered catalog.
        rule.onNodeWithText("Transfers").performClick()
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
        rule.onNodeWithText("This device").assertExists()
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("More offline tools"))
        rule.onNodeWithText("More offline tools").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Create a torrent from phone files"))
        rule.onNodeWithText("Create a torrent from phone files").assertExists()
        rule.onNodeWithText("Create torrent").assertDoesNotExist()
    }
}
