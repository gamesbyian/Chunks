package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Production Add shortcut surface. No rTorrent transport or file operation is
 * performed: selecting one path must only invoke the draft's exact callback.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class RecentRemotePlacesLargeTextTest {
    @get:Rule val rule = createComposeRule()
    private val paths = (1..8).map {
        "/storage/downloads/season-$it/long-but-readable-folder"
    }

    private fun show(enabled: Boolean, choices: MutableList<String>) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) {
                ChunksTheme { Surface {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        item {
                            RecentRemotePlaces(paths, enabled) { choices.add(it) }
                        }
                    }
                } }
            }
        }
    }

    @Test fun lastOfEightRecentPlacesRemainsReachableVerticallyAt320And200Percent() {
        val selected = mutableListOf<String>()
        show(true, selected)
        assertEquals("Displaying shortcuts alone must not choose a destination", 0, selected.size)
        rule.onNodeWithText("Recent save folders").assertExists()
        val last = rule.onNodeWithContentDescription("Use recent server folder: " + paths.last())
        last.performScrollTo()
        val bounds = last.getUnclippedBoundsInRoot()
        assertTrue("A recent Place needs a 48dp-class touch target",
            bounds.bottom - bounds.top >= 48.dp)
        last.performClick()
        assertEquals(listOf(paths.last()), selected)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun disabledShortcutsCannotChangeAnInFlightAddDraft() {
        val selected = mutableListOf<String>()
        show(false, selected)
        rule.onNodeWithContentDescription("Use recent server folder: " + paths.first()).assertExists()
        assertEquals(0, selected.size)
    }
}
