package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Presentation fixtures only; the actual write probes are in LocalDownloadPlaces. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi", fontScale = 2.0f)
class LocalDownloadPlacePickerTest {
    @get:Rule val rule = createComposeRule()

    private val public = LocalDownloadPlace(LocalPlaceKind.PUBLIC_DOWNLOADS,
        File("/Downloads/Chunks Downloads"), "Downloads/Chunks Downloads", "Shared folder")
    private val app = LocalDownloadPlace(LocalPlaceKind.APP_STORAGE,
        File("/app/Downloads"), "Chunks app folder", "Private folder")

    @Test fun automaticSourceIsVisibleAtLargeTextWithoutChoosingAnything() {
        val resolution = LocalDownloadPlacePolicy.resolve(null,
            listOf(LocalPlaceChoice(public, null), LocalPlaceChoice(app, null)))
        rule.setContent {
            ChunksTheme { Surface {
                LocalDownloadPlacePicker(resolution, enabled = true, onChoose = {})
            } }
        }
        rule.onNodeWithText("Saves to: Downloads/Chunks Downloads").assertExists()
        rule.onNodeWithText("Automatic local default", substring = true).assertExists()
        rule.onNodeWithText("Temporary fallback", substring = true).assertDoesNotExist()
    }

    @Test fun unmountedSavedLocationIsClearlyOnlyAWorkingFallback() {
        val resolution = LocalDownloadPlacePolicy.resolve(LocalPlaceKind.PUBLIC_DOWNLOADS,
            listOf(LocalPlaceChoice(public, "unmounted"), LocalPlaceChoice(app, null)))
        rule.setContent {
            ChunksTheme { Surface {
                LocalDownloadPlacePicker(resolution, enabled = true, onChoose = {})
            } }
        }
        rule.onNodeWithText("Saves to: Chunks app folder").assertExists()
        rule.onNodeWithText("Temporary fallback:", substring = true).assertExists()
        rule.onNodeWithText("Your saved download location", substring = true).assertExists()
    }
}
