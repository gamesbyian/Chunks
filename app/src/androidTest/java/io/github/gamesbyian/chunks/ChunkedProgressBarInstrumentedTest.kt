package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native semantics proof: visual chunks preserve the exact continuous fraction. */
@RunWith(AndroidJUnit4::class)
class ChunkedProgressBarInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun partialSegmentStillAnnouncesExactProgress() {
        rule.setContent {
            MaterialTheme {
                ChunkedProgressBar(progress = 0.31f, modifier = Modifier.fillMaxWidth())
            }
        }
        rule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(0.31f, 0f..1f)
            )
        ).assertExists()
    }

    @Test fun invalidProgressDoesNotAnnounceACompletedTransfer() {
        rule.setContent {
            MaterialTheme {
                ChunkedProgressBar(progress = Float.NaN, modifier = Modifier.fillMaxWidth())
            }
        }
        rule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(0f, 0f..1f)
            )
        ).assertExists()
    }
}
