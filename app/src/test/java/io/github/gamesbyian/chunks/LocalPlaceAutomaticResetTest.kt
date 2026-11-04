package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A saved phone location can return to automatic beside it; nothing else changes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi", fontScale = 2.0f)
class LocalPlaceAutomaticResetTest {
    @get:Rule val rule = createComposeRule()

    private val public = LocalDownloadPlace(LocalPlaceKind.PUBLIC_DOWNLOADS,
        File("/Downloads/Chunks Downloads"), "Downloads/Chunks Downloads", "Shared folder")
    private val app = LocalDownloadPlace(LocalPlaceKind.APP_STORAGE,
        File("/app/Downloads"), "Chunks app folder", "Private folder")
    private val choices = listOf(LocalPlaceChoice(public, null), LocalPlaceChoice(app, null))
    private val label = "Use automatic location for new phone downloads"

    private fun show(remembered: LocalPlaceKind?, onReset: () -> Unit) {
        val resolution = LocalDownloadPlacePolicy.resolve(remembered, choices)
        rule.setContent {
            ChunksTheme { Surface {
                LocalDownloadPlacePicker(resolution, enabled = true, onChoose = {}, onUseAutomatic = onReset)
            } }
        }
    }

    @Test fun savedChoiceOffersAReturnToAutomaticAtLargeText() {
        var resets = 0
        show(LocalPlaceKind.APP_STORAGE) { resets++ }
        rule.onNodeWithText("Your saved default for future phone downloads.").assertExists()
        rule.onNodeWithText(label).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, resets)
    }

    @Test fun fallbackFromASavedChoiceAlsoOffersIt() {
        show(LocalPlaceKind.REMOVABLE_APP_STORAGE) {}
        rule.onNodeWithText(label).assertExists()
    }

    @Test fun automaticDefaultOffersNoReset() {
        show(null) { error("nothing to reset") }
        rule.onNodeWithText("Automatic phone default", substring = true).assertExists()
        rule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test fun forgetRemovesOnlyTheSavedChoice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("local-download-place", Context.MODE_PRIVATE)
        prefs.edit().putString("default-kind", LocalPlaceKind.APP_STORAGE.key)
            .putString("resolved-root", "/app/Downloads").commit()
        assertTrue(LocalDownloadPlaces.forget(context))
        assertNull(prefs.getString("default-kind", null))
        assertEquals("/app/Downloads", prefs.getString("resolved-root", null))
    }
}
