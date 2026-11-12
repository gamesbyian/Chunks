package io.github.gamesbyian.chunks.media

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.MediaSettingsScreen
import io.github.gamesbyian.chunks.visual.smallTouchTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Options → Artwork & info at 320dp and 200% text: reachable, 48dp, default-on and opt-out, persistent. JVM tier. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-xxhdpi")
class MediaSettingsScreenTest {
    @get:Rule val rule = createComposeRule()
    private val media get() = ChunksMedia.get(ApplicationProvider.getApplicationContext())

    @Before fun reset() { media.updateSettings(MediaPrivacySettings()) }

    @Test fun settingsAreReachableConsentGatedAndPersisted() {
        var back = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                ChunksTheme { Surface { MediaSettingsScreen(onBack = { back++ }) } }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Online lookup").fetchSemanticsNodes().isNotEmpty() }
        val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        assertEquals(emptyList<String>(), rule.smallTouchTargets(density))
        rule.onRoot().captureRoboImage("build/chunks-visual/media-settings-320dp-200pct.png")

        // Fresh-install online lookup is ON; the existing toggle must still persist OFF and ON.
        assertTrue(media.settingsStore.load().onlineLookupConsent)
        rule.onNodeWithText("Online lookup").performClick()
        rule.waitUntil(5_000) { !media.settingsStore.load().onlineLookupConsent }
        rule.onNodeWithText("Online lookup").performClick()
        rule.waitUntil(5_000) { media.settingsStore.load().onlineLookupConsent }
        rule.onNodeWithText("Turn on online lookup?").assertDoesNotExist()

        // A switch below the fold is reachable and persists to the same store the lists use.
        rule.onNodeWithText("Show artwork").performScrollTo().performClick()
        rule.waitUntil(5_000) { !media.settingsStore.load().showArtwork }
        rule.onNodeWithText("Add your key…").performScrollTo().performClick()
        rule.onNodeWithText("TMDB API key or token").performScrollTo().assertExists()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        rule.onNodeWithText("Clear artwork, matches & lookup history").performScrollTo().assertExists()
        assertEquals(emptyList<String>(), rule.smallTouchTargets(density))
        rule.onNodeWithText("Back").performScrollTo().performClick()
        assertEquals(1, back)
        assertTrue(media.settingsStore.load().onlineLookupConsent)
    }
}
