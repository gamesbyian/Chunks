package io.github.gamesbyian.chunks.media

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.media.ui.SimilarTransferDisplay
import io.github.gamesbyian.chunks.media.ui.SimilarTransfersSection
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.MediaMetadataCacheKey
import io.github.gamesbyian.chunks.model.MediaRelationIndex
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
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
class SimilarTransfersSectionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val attr = MediaAttribution("TVmaze", "CC BY-SA 4.0", null)

    @Test fun confirmedIdentitiesProduceLabelledEnvironmentRows() {
        val hash = "a".repeat(40)
        val src = UnifiedTransferKey.of(EnvironmentId("local"), hash)
        val sameOnServer = UnifiedTransferKey.of(EnvironmentId("rtorrent:2"), hash)
        val next = UnifiedTransferKey.of(EnvironmentId("rtorrent:2"), "b".repeat(40))
        fun ep(e: Int) = MediaCandidate(MediaMetadataCacheKey("tvmaze", "show", "475"), MediaKind.TV_EPISODE, "Babylon 5",
            season = 2, episode = e, attribution = attr)
        val members = MediaRelationBridge.members(listOf(src to ep(7), sameOnServer to ep(7), next to ep(8)).map { (k, c) ->
            k to TransferMediaAssignment(MediaTransferKey.of(k), MediaAssignmentState.AUTOMATIC, c) })
        val related = MediaRelationIndex.build(members).similar(src, setOf(src, sameOnServer, next))
        val info = mapOf(
            sameOnServer to SimilarTransferDisplay("Babylon.5.S02E07.Soul.Mates.mkv", "Seedbox 2", ChunksColors.Orange, "Seeding"),
            next to SimilarTransferDisplay("Babylon.5.S02E08.Gropos.mkv", "Seedbox 2", ChunksColors.Orange, "Paused", stale = true))
        var opened: UnifiedTransferKey? = null
        rule.setContent { ChunksTheme { Surface { Column(Modifier.background(ChunksColors.Background).padding(12.dp)) {
            SimilarTransfersSection(related, { info[it] }, { opened = it })
        } } } }
        rule.onRoot().captureRoboImage("build/chunks-visual/media-similar-transfers.png")
        rule.onNodeWithText("Also on Seedbox 2").assertExists()
        rule.onNodeWithText("Nearby episode", substring = true).assertExists()
        rule.onNodeWithContentDescription("cached, may be out of date", substring = true).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(next, opened)
    }

    @Test fun nothingRendersWithoutCandidates() {
        rule.setContent { ChunksTheme { SimilarTransfersSection(emptyList(), { null }, {}) } }
        rule.onNodeWithText("Similar transfers").assertDoesNotExist()
    }
}
