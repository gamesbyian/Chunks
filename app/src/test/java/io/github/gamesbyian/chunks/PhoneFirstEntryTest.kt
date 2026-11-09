package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Source-wired entry callback, not a native download or physical device test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class PhoneFirstEntryTest {
    @get:Rule val rule = createComposeRule()

    private fun show(opened: IntArray) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChunksTheme { Surface {
                    PhoneFirstEntry { opened[0]++ }
                } }
            }
        }
    }

    @Test fun phonePrimaryChoiceIsAvailableAt320dpAnd200PercentWithoutStartingEngine() {
        val opened = intArrayOf(0)
        show(opened)
        rule.onNodeWithText("This device").assertExists()
        val action = rule.onNodeWithText("Open phone downloads")
        action.assertExists()
        val bounds = action.getUnclippedBoundsInRoot()
        assertTrue("Primary phone action must retain at least 48dp height",
            bounds.bottom - bounds.top >= 48.dp)
        assertEquals(0, opened[0])
        assertNull(LocalEngineHolder.current())
        action.performClick()
        assertEquals(1, opened[0])
        assertNull("Entry callback does not automatically run a torrent engine",
            LocalEngineHolder.current())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun largerPhoneRetainsSingleUnambiguousEntryAt200Percent() {
        val opened = intArrayOf(0)
        show(opened)
        rule.onNodeWithText("Open phone downloads").performClick()
        assertEquals(1, opened[0])
        rule.onNodeWithText("No server account is needed.", substring = true).assertExists()
        assertNull(LocalEngineHolder.current())
    }
}
