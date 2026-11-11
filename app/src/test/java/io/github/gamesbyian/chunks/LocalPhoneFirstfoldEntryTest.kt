package io.github.gamesbyian.chunks

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The actual phone-only screen, with no .torrent submission or engine mutation.
 * These checks cover first-fold discoverability, not real peer or SAF capability.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class LocalPhoneFirstfoldEntryTest {
    @get:Rule val rule = createComposeRule()

    private fun showPhoneAt200Percent() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChunksTheme { Surface { LocalEngineScreen(pane = PhonePane.ADD, onBack = {}, onCombined = {}, onSeedingRules = {}) } }
            }
        }
        // A normal return opens on transfers. Enter Add explicitly for this
        // existing first-fold source-order and safety-description acceptance.
        rule.onNodeWithText("Add a transfer").assertExists()
    }

    @Test fun phoneIntakeIsAheadOfLongAdvancedExplanationAt320Dp() {
        showPhoneAt200Percent()
        val title = rule.onNodeWithText("Add a transfer")
        val torrent = rule.onNodeWithText("Open .torrent")
        val magnet = rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD)
        val warning = rule.onNodeWithText("Downloads and seeds with Chunks' own engine.", substring = true)
        title.assertExists()
        torrent.assertExists()
        magnet.assertExists()
        warning.assertExists()
        // A .torrent can be imported without any magnet text; its start policy
        // must remain independently selectable before the picker is used.
        rule.onNodeWithText("Start when added").assertExists()
        assertTrue("The primary .torrent action must precede the engine explanation",
            torrent.getUnclippedBoundsInRoot().top < warning.getUnclippedBoundsInRoot().top)
        assertTrue("Magnet input must be reachable before the long engine explanation",
            magnet.getUnclippedBoundsInRoot().top < warning.getUnclippedBoundsInRoot().top)
        magnet.performTextInput("magnet:?xt=urn:btih:" + "a".repeat(40) + "&dn=Open%20test")
        val submit = rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT)
        submit.assertExists()
        assertTrue("The actual phone-only Add action must precede advanced explanation",
            submit.getUnclippedBoundsInRoot().top < warning.getUnclippedBoundsInRoot().top)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun alternatePhoneViewportKeepsBothIntakeChoicesBeforeExplanation() {
        showPhoneAt200Percent()
        val torrent = rule.onNodeWithText("Open .torrent")
        val magnet = rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD)
        val warning = rule.onNodeWithText("Downloads and seeds with Chunks' own engine.", substring = true)
        assertTrue(torrent.getUnclippedBoundsInRoot().top < warning.getUnclippedBoundsInRoot().top)
        assertTrue(magnet.getUnclippedBoundsInRoot().top < warning.getUnclippedBoundsInRoot().top)
        magnet.performTextInput("magnet:?xt=urn:btih:" + "b".repeat(40) + "&dn=Open%20test")
        val submit = rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT)
        submit.assertExists()
        assertTrue(submit.getUnclippedBoundsInRoot().top < warning.getUnclippedBoundsInRoot().top)
    }
}
