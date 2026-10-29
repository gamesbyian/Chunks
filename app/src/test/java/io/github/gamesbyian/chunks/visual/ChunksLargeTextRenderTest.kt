package io.github.gamesbyian.chunks.visual

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.ActionTile
import io.github.gamesbyian.chunks.ChunksBanner
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.ChunksTone
import io.github.gamesbyian.chunks.LocalTransferCard
import io.github.gamesbyian.chunks.R
import io.github.gamesbyian.chunks.StatusPill
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared visual components at Android's 200% font scale (JVM render, review only). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1400dp-xxhdpi", fontScale = 2.0f)
class ChunksLargeTextRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun componentsAt200Percent() {
        val status = LocalTransferStatus("d".repeat(40), "Our Planet S01 (2160p)", LocalTransferState.DOWNLOADING,
            stopped = false, progressPpm = 320_000, wantedBytes = 38_600_000_000, doneBytes = 12_352_000_000,
            payloadDownloadedBytes = 0, payloadUploadedBytes = 0, peers = 42, savePath = "/x", error = null)
        rule.setContent {
            ChunksTheme { androidx.compose.material3.Surface {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill("Downloading", ChunksTone.ACTIVE); StatusPill("Stopped", ChunksTone.PAUSED)
                    }
                    LocalTransferCard(status, busy = false, onToggle = {}, onRemove = {})
                    ActionTile("Remove from rTorrent…", ChunksColors.Orange, onClick = {},
                        modifier = Modifier.fillMaxWidth(), icon = R.drawable.chunks_ic_remove, stacked = false)
                    ChunksBanner("Connection unsuccessful", "Login rejected. Check the username/password.", ChunksTone.ERROR)
                }
            } }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/components-200pct.png")
    }
}
