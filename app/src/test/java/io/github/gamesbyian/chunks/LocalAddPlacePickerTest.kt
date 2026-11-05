package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.io.File
import io.github.gamesbyian.chunks.visual.renderedContrast
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi", fontScale = 2.0f)
open class LocalAddPlacePickerTest {
    @get:Rule val rule = createComposeRule()
    private val public = LocalPlaceChoice(LocalDownloadPlace(LocalPlaceKind.PUBLIC_DOWNLOADS,
        File("/downloads"), "Downloads/Chunks Downloads", "Shared folder"), null)
    private val app = LocalPlaceChoice(LocalDownloadPlace(LocalPlaceKind.APP_STORAGE,
        File("/app"), "Chunks app folder", "Private folder"), null)

    @Test fun oneTimeSelectionRestoresAndOnlyExplicitDefaultActionPersists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("local-download-place", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertTrue(LocalDownloadPlaces.remember(context, public))
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            var current by rememberSaveable { mutableStateOf<String?>(null) }
            val saved = LocalDownloadPlacePolicy.resolve(LocalPlaceKind.PUBLIC_DOWNLOADS, listOf(public, app))
            ChunksTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
                LocalDownloadPlacePicker(LocalAddPlacePolicy.resolve(saved, current), true,
                    onChoose = { current = LocalAddPlacePolicy.identity(it.place) },
                    currentAdd = true, defaultResolution = saved,
                    onUseDefault = { current = null },
                    onSaveDefault = { LocalDownloadPlaces.remember(context, it) })
            } } }
        }
        rule.onNodeWithText("Change download location").performScrollTo().performClick()
        rule.onNodeWithText("Chunks app folder").performScrollTo().performClick()
        assertEquals(public.place.kind.key, prefs.getString("default-kind", null))
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Saves to: Chunks app folder").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("For this Add only. Saving a phone default is a separate action.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Use phone default for this Add").performScrollTo()
            .assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Saves to: Downloads/Chunks Downloads").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Change download location").performScrollTo().performClick()
        rule.onNodeWithText("Chunks app folder").performScrollTo().performClick()
        val save = rule.onNodeWithText("Save this location as phone default").performScrollTo()
            .assertHasClickAction().assertHeightIsAtLeast(48.dp)
        assertTrue("Save default text must have AA contrast", save.renderedContrast() >= 4.5)
        save.performClick()
        assertEquals(app.place.kind.key, prefs.getString("default-kind", null))
        prefs.edit().clear().commit()
    }

    @Test fun lostOneTimePlaceOffersRecoveryAndNeverEnablesSavingAFallback() {
        val saved = LocalDownloadPlacePolicy.resolve(null, listOf(public, app.copy(failure = "unmounted")))
        rule.setContent { ChunksTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            LocalDownloadPlacePicker(LocalAddPlacePolicy.resolve(saved, LocalAddPlacePolicy.identity(app.place)),
                true, onChoose = {}, currentAdd = true, defaultResolution = saved,
                onUseDefault = {}, onSaveDefault = { error("unavailable") })
        } } } }
        rule.onNodeWithText("No phone download location is available.").assertExists()
        rule.onNodeWithText("Use phone default for this Add").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Save this location as phone default").assertDoesNotExist()
    }
}

@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi", fontScale = 2.0f)
class LocalAddPlacePicker412Test : LocalAddPlacePickerTest()
