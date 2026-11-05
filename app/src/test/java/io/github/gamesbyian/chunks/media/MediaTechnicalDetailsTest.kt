package io.github.gamesbyian.chunks.media

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.media.inspection.ByteArrayMediaByteSource
import io.github.gamesbyian.chunks.media.ui.MediaTechnicalDetails
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h900dp-xxhdpi")
class MediaTechnicalDetailsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val mkv = javaClass.classLoader!!.getResource("media-fixtures/embedded/tracks.mkv")!!.readBytes()

    @Test fun nothingIsReadUntilInspectIsTappedThenTracksShow() {
        var opens = 0
        rule.setContent { ChunksTheme { Surface { Column(Modifier.background(ChunksColors.Background).padding(12.dp)) {
            MediaTechnicalDetails("Show.S01E01.mkv", { opens++; ByteArrayMediaByteSource(mkv) })
        } } } }
        assertEquals(0, opens)
        rule.onNodeWithText("Inspect media").assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Show 5 tracks").assertExists() }.isSuccess }
        rule.onNodeWithText("Show 5 tracks").performClick()
        rule.onRoot().captureRoboImage("build/chunks-visual/media-technical-details.png")
        rule.onNodeWithText("2. Audio — AAC · stereo", substring = true).assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/media-technical-details.png")
        assertEquals(1, opens)
    }

    @Test fun unreadableFileSaysUnavailable() {
        rule.setContent { ChunksTheme { MediaTechnicalDetails("x.bin", { ByteArrayMediaByteSource(ByteArray(100)) }) } }
        rule.onNodeWithText("Inspect media").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Technical details unavailable for this file.").assertExists() }.isSuccess }
        rule.onNodeWithText("Try again").assertExists()
    }
}
