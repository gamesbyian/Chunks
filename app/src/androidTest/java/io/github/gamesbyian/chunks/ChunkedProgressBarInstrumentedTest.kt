package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Installed native Compose proof. The accessibility percentage is continuous,
 * while actual chronological growth bands are measured in *rendered pixels*.
 * Simulated test observations do not claim real network transfers or physical
 * handset acceptance.
 */
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

    @Test fun actualPixelsHaveThreeProportionalRecentBandsNotFixedEmptySlots() {
        // A unique environment isolates the test from persisted earlier app data.
        val key = requireNotNull(ProgressChunkStore.identity(
            "native-visual-" + System.nanoTime(), "b".repeat(40)))
        val progress = mutableFloatStateOf(0f)
        val clock = mutableLongStateOf(1_000L)
        val fresh = mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(280.dp)) {
                    ChunkedProgressBar(
                        progress = progress.floatValue,
                        observationEpochMillis = clock.longValue,
                        historyKey = key,
                        observeFresh = fresh.value,
                        activeColor = Color.Red,
                        modifier = Modifier.testTag("chronological-progress")
                    )
                }
            }
        }
        fun observe(ppm: Int, time: Long) {
            rule.runOnIdle {
                clock.longValue = time
                progress.floatValue = ppm / 1_000_000f
            }
            rule.waitUntil(10_000) {
                ProgressChunkStore.current(key)?.totalPpm == ppm
            }
            rule.waitForIdle()
        }
        observe(0, 1_000L)
        observe(80_000, 2_000L)
        observe(80_000, 3_000L)
        observe(130_000, 4_000L)
        observe(280_000, 5_000L)
        assertEquals(
            listOf(80_000, 50_000, 150_000),
            ProgressChunkStore.current(key)?.paintedBands()
        )
        val image = rule.onNodeWithTag("chronological-progress").captureToImage().toPixelMap()
        val y = image.height / 2
        fun sample(fraction: Float): Color =
            image[(image.width * fraction).toInt(), y]
        val first = sample(.04f)  // First 8%, dark red
        val second = sample(.10f) // Newer 5%, subtly lighter red
        val third = sample(.20f)  // Newest 15%, dark red again
        val unfilled = sample(.50f)
        assertTrue("First band is filled", first.red > .80f)
        assertTrue("Second band is distinct", second.green > first.green + .025f)
        assertTrue("Newest band retains its own tone",
            abs(third.green - first.green) < .035f)
        assertTrue("The track has no prefabricated filled slots", unfilled.red < .45f)
        rule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(.28f, 0f..1f)
            )
        ).assertExists()

        // A stale older snapshot must not create a fourth event or a correction.
        rule.runOnIdle {
            fresh.value = false
            clock.longValue = 4_500L
            progress.floatValue = .13f
        }
        rule.waitForIdle()
        assertEquals(280_000, ProgressChunkStore.current(key)?.totalPpm)
        assertEquals(3, ProgressChunkStore.current(key)?.bands?.size)
    }
}
