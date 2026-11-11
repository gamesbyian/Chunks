package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed presentation gate: editing the destination does not submit, clear
 * the private source, change Start, or treat saved credentials as authenticated.
 * Full native/rTorrent Add requires separate exact-head peer/piece receipts.
 */
@RunWith(AndroidJUnit4::class)
class UniversalAddDestinationPickerInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun switchingTargetPreservesSourceAndStartWithoutSubmission() {
        val source = mutableStateOf("magnet:?xt=urn:btih:" + "a".repeat(40))
        val start = mutableStateOf(false)
        val remote = mutableStateOf(false)
        val submissions = mutableStateOf(0)
        rule.setContent {
            MaterialTheme {
                UniversalAddDestinationPicker(
                    addingRemotely = remote.value,
                    authenticatedRemoteLabel = "rTorrent instance 2",
                    savedOtherProfiles = listOf(
                        UniversalAddSavedDestination("offline-3", "rTorrent instance 3")),
                    enabled = true,
                    onPhone = { remote.value = false },
                    onAuthenticatedRemote = { remote.value = true },
                    onConnectSaved = { })
            }
        }
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_DEVICE).assertIsSelected()
        rule.onNodeWithText("rTorrent instance 2").performClick().assertIsSelected()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_DEVICE).performClick().assertIsSelected()
        rule.onNodeWithText("Other saved connections (1)").performClick()
        rule.onNodeWithText("Connect to rTorrent instance 3").assertExists()
        rule.runOnIdle {
            assertEquals("magnet:?xt=urn:btih:" + "a".repeat(40), source.value)
            assertEquals(false, start.value)
            assertEquals(0, submissions.value)
        }
    }

    @Test fun pendingShareRequiresExplicitKeepOrReplace() {
        val waiting = mutableStateOf(true)
        val replacements = mutableStateOf(0)
        rule.setContent {
            MaterialTheme {
                UniversalAddDestinationPicker(
                    addingRemotely = false, authenticatedRemoteLabel = null,
                    savedOtherProfiles = emptyList(), enabled = true,
                    incomingSourceWaiting = waiting.value,
                    onAcceptIncoming = { replacements.value++; waiting.value = false },
                    onKeepDraft = { waiting.value = false },
                    onPhone = {}, onAuthenticatedRemote = {}, onConnectSaved = {})
            }
        }
        rule.onNodeWithText("Keep current draft").performClick()
        rule.runOnIdle { assertEquals(0, replacements.value) }
        rule.onNodeWithText("Use new source").assertDoesNotExist()
    }
}
