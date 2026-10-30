package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class RemoteTransfersPrimaryActionsLargeTextTest {
    @get:Rule val rule = createComposeRule()

    private fun show(widthDp: Int, scale: Float, loading: Boolean = false,
                     stale: Boolean = false, add: () -> Unit = {}, refresh: () -> Unit = {}) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                ChunksTheme {
                    Surface {
                        Box(Modifier.width(widthDp.dp)) {
                            RemoteTransfersPrimaryActions(loading, stale, add, refresh)
                        }
                    }
                }
            }
        }
    }

    @Test fun narrow200PercentStacksButtonsWithFullWidthAndNoClippedHeight() {
        var adds = 0
        var refreshes = 0
        show(320, 2f, add = { adds++ }, refresh = { refreshes++ })
        val add = rule.onNodeWithText("+ Add torrent").getUnclippedBoundsInRoot()
        val refresh = rule.onNodeWithText("Refresh").getUnclippedBoundsInRoot()
        assertTrue("Refresh must be below Add at 320dp / 200%", refresh.top >= add.bottom)
        assertEquals(add.left, refresh.left)
        assertEquals(add.right, refresh.right)
        assertTrue(add.bottom - add.top >= 48.dp)
        assertTrue(refresh.bottom - refresh.top >= 48.dp)
        rule.onRoot().captureRoboImage("build/chunks-visual/remote-add-refresh-320x700-200pct.png")
        rule.onNodeWithText("+ Add torrent").performClick()
        rule.onNodeWithText("Refresh").performClick()
        assertEquals(1, adds)
        assertEquals(1, refreshes)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun biggerPhoneStillStacksAt200Percent() {
        show(412, 2f)
        val add = rule.onNodeWithText("+ Add torrent").getUnclippedBoundsInRoot()
        val refresh = rule.onNodeWithText("Refresh").getUnclippedBoundsInRoot()
        assertTrue(refresh.top >= add.bottom)
        assertEquals(add.left, refresh.left)
        assertEquals(add.right, refresh.right)
        rule.onRoot().captureRoboImage("build/chunks-visual/remote-add-refresh-412x892-200pct.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w600dp-h700dp-mdpi")
    fun wideNormalTextKeepsCompactSingleRow() {
        show(550, 1f)
        val add = rule.onNodeWithText("+ Add torrent").getUnclippedBoundsInRoot()
        val refresh = rule.onNodeWithText("Refresh").getUnclippedBoundsInRoot()
        assertEquals(add.top, refresh.top)
        assertTrue(add.right < refresh.left)
    }

    private fun showSort(widthDp: Int, scale: Float, onSort: () -> Unit = {}) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                ChunksTheme {
                    Surface {
                        Box(Modifier.width(widthDp.dp)) {
                            RemoteTransfersSortSummary(120, "Download speed", onSort)
                        }
                    }
                }
            }
        }
    }

    @Test fun sortHeaderIsVerticalAndAccessibleAt320dp200Percent() {
        var callbacks = 0
        showSort(320, 2f) { callbacks++ }
        val count = rule.onNodeWithText("120 shown").getUnclippedBoundsInRoot()
        val sort = rule.onNodeWithText("Sort: Download speed").getUnclippedBoundsInRoot()
        assertTrue("Sort button must not fight the count for width", sort.top >= count.bottom)
        assertTrue(sort.left >= 0.dp && sort.right <= 320.dp)
        rule.onNodeWithText("Sort: Download speed").performClick()
        assertEquals(1, callbacks)
        rule.onRoot().captureRoboImage("build/chunks-visual/remote-sort-320x700-200pct.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w600dp-h700dp-mdpi")
    fun sortHeaderStaysOnOneRowWhenSpaceIsAvailable() {
        showSort(550, 1f)
        val count = rule.onNodeWithText("120 shown").getUnclippedBoundsInRoot()
        val sort = rule.onNodeWithText("Sort: Download speed").getUnclippedBoundsInRoot()
        // The button is taller than the count label and the Row centers it;
        // their vertical intervals should overlap without horizontal collision.
        assertTrue(count.bottom > sort.top && count.top < sort.bottom)
        assertTrue(count.right < sort.left)
    }

    @Test fun loadingAndStaleKeepOriginalCommandGates() {
        var adds = 0
        var refreshes = 0
        show(320, 2f, loading = true, stale = true,
            add = { adds++ }, refresh = { refreshes++ })
        rule.onNodeWithText("+ Add torrent").assertIsNotEnabled()
        rule.onNodeWithText("Refreshing…").assertIsNotEnabled()
        assertEquals(0, adds)
        assertEquals(0, refreshes)
    }
}
