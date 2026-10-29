package io.github.gamesbyian.chunks.visual

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.ChunkedProgressBar
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.ChunksTone
import io.github.gamesbyian.chunks.LocalFileSelection
import io.github.gamesbyian.chunks.LocalTransferCard
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.StatusPill
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** JVM render of shared components for comparison with reference sheet 05. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp-xxhdpi")
class ChunksComponentRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun status(name: String, state: LocalTransferState, stopped: Boolean, ppm: Int,
                       peers: Int, error: String? = null) =
        LocalTransferStatus("c".repeat(40), name, state, stopped = stopped, progressPpm = ppm,
            wantedBytes = 38_600_000_000, doneBytes = 38_600_000_000L * ppm / 1_000_000,
            payloadDownloadedBytes = 0, payloadUploadedBytes = 1_400_000, peers = peers,
            savePath = "/storage/emulated/0/Download/Chunks", error = error,
            downloadPayloadRateBytesPerSecond = if (!stopped && ppm < 1_000_000) 5_280_000 else 0)

    @Test fun componentSheet() {
        rule.setContent {
            ChunksTheme { androidx.compose.material3.Surface {
                Column(Modifier.fillMaxWidth().background(ChunksColors.Background).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill("Downloading", ChunksTone.ACTIVE)
                        StatusPill("Sharing", ChunksTone.COMPLETE)
                        StatusPill("Stopped", ChunksTone.PAUSED)
                        StatusPill("Error", ChunksTone.ERROR)
                        StatusPill("Unknown", ChunksTone.INACTIVE)
                    }
                    for ((tone, value) in listOf(ChunksTone.ACTIVE to 0.68f, ChunksTone.PAUSED to 0.58f,
                            ChunksTone.COMPLETE to 1f, ChunksTone.ERROR to 0.2f, ChunksTone.INACTIVE to 0f))
                        ChunkedProgressBar(value, Modifier.fillMaxWidth(), tone = tone)
                    LocalTransferCard(status("Our Planet S01 (2160p)", LocalTransferState.DOWNLOADING, false, 320_000, 42),
                        busy = false, onToggle = {}, onRemove = {})
                    LocalTransferCard(status("Creative Assets Collection", LocalTransferState.SEEDING, false, 1_000_000, 18),
                        busy = false, onToggle = {}, onRemove = {})
                    LocalTransferCard(status("Sci Fi Pack (2024)", LocalTransferState.DOWNLOADING, true, 580_000, 0),
                        busy = false, onToggle = {}, onRemove = {})
                    Text("End of sheet")
                }
            } }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/components.png")
    }

    @Test fun fileRows() {
        val files = listOf(
            LocalTransferFile(0, "The Wanderer/The.Wanderer.2024.mkv", 8_400_000_000, 5_712_000_000, true),
            LocalTransferFile(1, "The Wanderer/Sample.mkv", 542_000_000, 542_000_000, true),
            LocalTransferFile(2, "The Wanderer/Soundtrack/01 Theme.flac", 42_000_000, 0, true),
            LocalTransferFile(3, "The Wanderer/Poster.jpg", 2_000_000, 0, false),
            LocalTransferFile(4, "The Wanderer/Extras.zip", 1_200_000_000, 0, true),
            LocalTransferFile(5, "The Wanderer/Subtitles/en.srt", 80_000, 80_000, true))
        rule.setContent {
            ChunksTheme { androidx.compose.material3.Surface {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LocalFileSelection(files, busy = false, transferKey = "render",
                        onFilePriority = { _, _ -> }, onFileWanted = { _, _ -> })
                }
            } }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/file-rows.png")
    }
}
