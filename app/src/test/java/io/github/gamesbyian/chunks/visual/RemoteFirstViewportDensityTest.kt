package io.github.gamesbyian.chunks.visual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.CompactRemoteInventoryHeader
import io.github.gamesbyian.chunks.RemoteTransfersPrimaryActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Combined presentation-only density proof. The actual remote list supplies
 * 18dp gutters on either side of a 412dp local, leaving exactly 376dp.
 * Fake callbacks never connect or issue rTorrent RPC commands.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class RemoteFirstViewportDensityTest {
    @get:Rule val rule = createComposeRule()

    @Test fun scopeHeaderAndAddRefreshShareTwoRowsOnThePrimaryLocal() {
        var opens = 0
        var adds = 0
        var refreshes = 0
        rule.setContent {
            ChunksTheme {
                Column(Modifier.width(376.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactRemoteInventoryHeader(
                        environmentLabel = "Wood",
                        environment = null,
                        loadedCount = 32,
                        view = "main",
                        stale = false,
                        loading = false,
                        onAllEnvironments = { opens++ }
                    )
                    RemoteTransfersPrimaryActions(
                        loading = false, stale = false,
                        onAdd = { adds++ },
                        onRefresh = { refreshes++ }
                    )
                }
            }
        }
        val badge = rule.onNodeWithText("Wood · 32 transfers").getUnclippedBoundsInRoot()
        val all = rule.onNodeWithText("All environments…").getUnclippedBoundsInRoot()
        val add = rule.onNodeWithText("+ Add torrent").getUnclippedBoundsInRoot()
        val refresh = rule.onNodeWithText("Refresh").getUnclippedBoundsInRoot()
        assertTrue("Environment identity and All environments belong on one row",
            badge.top < all.bottom && all.top < badge.bottom)
        assertEquals("Add and Refresh belong on one row", add.top, refresh.top)
        assertTrue("Primary actions must follow the scope header", add.top >= all.bottom)
        assertTrue("No extra row above the transfer list on a 412dp local", refresh.bottom <= 150.dp)
        assertTrue("All controls remain inside 376dp usable content", all.right <= 376.dp
            && refresh.right <= 376.dp)
        rule.onNodeWithText("All environments…").performClick()
        rule.onNodeWithText("+ Add torrent").performClick()
        rule.onNodeWithText("Refresh").performClick()
        assertEquals(1, opens)
        assertEquals(1, adds)
        assertEquals(1, refreshes)
    }
}
