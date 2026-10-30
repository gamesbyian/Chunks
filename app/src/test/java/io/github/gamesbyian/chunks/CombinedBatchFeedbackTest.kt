package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.gamesbyian.chunks.model.EnvironmentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class CombinedBatchFeedbackTest {
    @get:Rule val rule = createComposeRule()

    @Test fun unexpectedPreleaseFailureDoesNotLeakDetailsOrClaimNoAction() = runBlocking {
        var invocations = 0
        val output = describeCombinedBatchSafely({ it.value }) {
            invocations++
            error("secret host token")
        }
        assertEquals(1, invocations)
        assertEquals(1, output.size)
        assertTrue(output.single().contains("outcome unknown"))
        assertTrue(output.single().contains("may already have applied"))
        assertTrue(output.single().contains("Refresh each owning environment"))
        assertFalse(output.single().contains("secret host token"))
        assertFalse(output.single().contains("No command was sent"))
    }

    @Test fun cancellationPropagatesWithoutInventingAnOutcome() {
        var caught = false
        try {
            runBlocking {
                describeCombinedBatchSafely({ it.value }) {
                    throw CancellationException("screen destroyed")
                }
            }
        } catch (_: CancellationException) {
            caught = true
        }
        assertTrue(caught)
    }

    @Test fun ongoingAndSettledBatchHavePoliteAnnouncementNotPerRowSpamming() {
        rule.setContent {
            ChunksTheme {
                Surface {
                    CombinedBatchFeedback(true, emptyList())
                }
            }
        }
        rule.onNodeWithText("Working… each environment's list will refresh afterwards.")
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun individualResultsRemainReadableAfterBoundedPoliteSummary() {
        val result = listOf(
            "On this phone: 1 confirmed started.",
            "Server · account: 1 uncertain, refresh before retry."
        )
        rule.setContent {
            ChunksTheme {
                Surface {
                    CombinedBatchFeedback(false, result)
                }
            }
        }
        rule.onNodeWithText("Batch result available. Review outcomes for each environment.")
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        result.forEach { rule.onNodeWithText(it).assertExists() }
    }
}
