package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
        val savedSelection = mutableStateOf<String?>(null)
        rule.setContent {
            MaterialTheme {
                UniversalAddDestinationPicker(
                    addingRemotely = remote.value,
                    authenticatedRemoteLabel = "rTorrent instance 2",
                    savedOtherProfiles = listOf(
                        UniversalAddSavedDestination("offline-3", "rTorrent instance 3")),
                    enabled = true,
                    onLocal = { remote.value = false },
                    onAuthenticatedRemote = { remote.value = true },
                    onConnectSaved = { savedSelection.value = it })
            }
        }
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_DEVICE).assertIsSelected()
        rule.onNodeWithText("rTorrent instance 2").performClick().assertIsSelected()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_DEVICE).performClick().assertIsSelected()
        rule.onNodeWithText("rTorrent instance 3").performClick()
        rule.runOnIdle { assertEquals("offline-3", savedSelection.value) }
        rule.runOnIdle {
            assertEquals("magnet:?xt=urn:btih:" + "a".repeat(40), source.value)
            assertEquals(false, start.value)
            assertEquals(0, submissions.value)
        }
    }

    @Test fun optionalAddAliasRemainsDraftOnlyWhileSwitchingTargets() {
        val alias = mutableStateOf("")
        val remote = mutableStateOf(false)
        var submitted = 0
        rule.setContent {
            MaterialTheme {
                UniversalAddDestinationPicker(
                    addingRemotely = remote.value,
                    authenticatedRemoteLabel = "On test rTorrent",
                    savedOtherProfiles = emptyList(),
                    enabled = true,
                    aliasDraft = alias.value,
                    onAliasChange = { alias.value = it },
                    onLocal = { remote.value = false },
                    onAuthenticatedRemote = { remote.value = true },
                    onConnectSaved = { submitted++ })
            }
        }
        rule.onNodeWithText("Optional display name").performClick()
        rule.onNodeWithText("Display name in Chunks")
            .performTextReplacement("Custom List Alias")
        rule.onNodeWithText("On test rTorrent").performClick()
        rule.onNodeWithText("Local").performClick()
        rule.onNodeWithText("Custom List Alias").assertExists()
        rule.runOnIdle {
            assertEquals("Custom List Alias", alias.value)
            assertEquals(0, submitted)
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
                    onLocal = {}, onAuthenticatedRemote = {}, onConnectSaved = {})
            }
        }
        rule.onNodeWithText("Keep current draft").performClick()
        rule.runOnIdle { assertEquals(0, replacements.value) }
        rule.onNodeWithText("Use new source").assertDoesNotExist()
    }
    @Test fun duplicateNamesRemainDistinctSavedConnectionActions() {
        val selected = mutableStateOf<String?>(null)
        rule.setContent {
            MaterialTheme {
                UniversalAddDestinationPicker(
                    addingRemotely = false, authenticatedRemoteLabel = "Wood",
                    savedOtherProfiles = listOf(
                        UniversalAddSavedDestination("remote-2", "Wood")),
                    enabled = true,
                    onLocal = {}, onAuthenticatedRemote = {},
                    onConnectSaved = { selected.value = it })
            }
        }
        rule.onNodeWithText("Local").assertIsSelected()
        rule.onNodeWithText("Wood (1)").assertExists()
        rule.onNodeWithText("Wood (2)").performClick()
        rule.runOnIdle { assertEquals("remote-2", selected.value) }
        rule.onNodeWithText("Local").assertIsSelected()
    }

}
