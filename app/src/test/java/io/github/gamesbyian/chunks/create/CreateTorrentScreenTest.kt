package io.github.gamesbyian.chunks.create

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.ChunksTheme
import androidx.compose.material3.Surface
import java.io.ByteArrayInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Review → build → result on the real Create screen (JVM; renders are review aids only). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CreateTorrentScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun source(name: String) = CreateTorrentSource(name, (1..7).map { n ->
        val bytes = ByteArray(20_000 + n) { (it * n).toByte() }
        TorrentMetainfoBuilder.Entry(listOf("Episode $n.mkv"), bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    })

    private fun screen(source: CreateTorrentSource) = rule.setContent {
        ChunksTheme {
            Surface(color = ChunksColors.Background) {
                CreateTorrentScreen(onBack = {}, initialSource = source)
            }
        }
    }

    private fun createAndWait() {
        rule.onNodeWithText("Create torrent").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Torrent created").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun publicTorrentOffersSaveShareAndCopyWithAnHonestSeedingNote() {
        screen(source("Season"))
        rule.onNodeWithText("7 files", substring = true).assertIsDisplayed()
        rule.onNodeWithText("and 2 more").assertExists()
        rule.onNodeWithText("What recipients will see").assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/create-review.png")
        rule.onNodeWithText("Trackers (optional, one per line)").performScrollTo()
            .performTextInput("udp://tracker.example:6969/announce")
        createAndWait()
        rule.onNodeWithText("Not seeding yet").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Save .torrent file").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Share magnet link").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Copy magnet link").performScrollTo().performClick()
        rule.onNodeWithText("Magnet link copied.").assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/create-result.png")
    }

    @Test fun privateTorrentHasNoMagnetActions() {
        screen(source("Private"))
        rule.onNodeWithText("Private torrent", substring = true).performScrollTo().performClick()
        createAndWait()
        rule.onNodeWithText("Save .torrent file").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Share magnet link").assertDoesNotExist()
        rule.onNodeWithText("Copy magnet link").assertDoesNotExist()
    }

    @Test fun anInvalidTrackerIsReportedAndNothingIsCreated() {
        screen(source("Bad"))
        rule.onNodeWithText("Trackers (optional, one per line)").performScrollTo()
            .performTextInput("https://user:secret@tracker.example/announce")
        rule.onNodeWithText("Create torrent").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Invalid tracker URL").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Torrent created").assertDoesNotExist()
    }

    @Test fun seedingACopyIsReviewedFirstAndWarnsWhenPeersCannotFindThePhone() {
        screen(source("Trackerless"))
        createAndWait()
        rule.onNodeWithText("Seed a copy on this phone").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Your original files are not moved or changed", substring = true).assertExists()
        rule.onNodeWithText("Peers may not find this phone").performScrollTo().assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/chunks-visual/create-seed-review.png")
        rule.onNodeWithText("Not now").performScrollTo().performClick()
        rule.onNodeWithText("Seed a copy on this phone").assertExists()
    }
}
