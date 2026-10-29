package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import io.github.gamesbyian.chunks.model.UnifiedSortDirection
import io.github.gamesbyian.chunks.model.UnifiedSortKey
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CombinedTransfersScreenTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val aliasScope = "https://box.example/rutorrent2/plugins/httprpc/action.php\u0000ian"
    private val other = CombinedSavedProfile("e".repeat(64), "seed @ other.example/RPC2")
    private val rows = listOf(
        RemoteTransfer("1".repeat(40), "Big Archive", 900, 900, true, "/d", 0, 10),
        RemoteTransfer("2".repeat(40), "Small Episode", 10, 5, false, "/d", null, null))

    private fun show(stale: Boolean = false) = rule.setContent {
        ChunksTheme { Surface(color = ChunksColors.Background) {
            CombinedTransfersScreen(
                server = CombinedServerSource(aliasScope, rows, stale = stale, fullView = true,
                    observedAtEpochMillis = 1L),
                savedProfiles = listOf(other), onOpenPhone = {}, onOpenServer = {}, onBack = {})
        } }
    }

    @Test fun showsEveryEnvironmentHonestlyWithoutStartingThePhoneEngine() {
        show()
        rule.onNodeWithText("All environments").assertExists()
        rule.onNodeWithText("Big Archive").assertExists()
        rule.onNodeWithText("Small Episode").assertExists()
        rule.onNodeWithText("On this phone: Engine not running, open On this phone · 0 transfers").assertExists()
        rule.onNodeWithText("Server · seed @ other.example/RPC2: Not connected · 0 transfers").assertExists()
        rule.onNodeWithText(serverEnvironmentLabel(aliasScope) + ": Live · 2 transfers").assertExists()
        rule.onNodeWithText("Started · 100% · 900 B · ↓ 0 B/s · ↑ 10 B/s").assertExists()
        rule.onNodeWithText("Stopped · 50% · 10 B · ↓ not reported · ↑ not reported").assertExists()
        rule.onNodeWithText("Showing 2 · some environments are missing or out of date").assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/combined-all.png")
        // A read-only view must not bring up the phone engine for a remote-only user.
        assertNull(LocalEngineHolder.current())
    }

    @Test fun sortDirectionScopeAndGroupingPersistAcrossReopen() {
        show()
        rule.onNodeWithText("Size").performClick()
        rule.onNodeWithContentDescription("Sorted by Size, Largest first. Reverse order").performClick()
        rule.onNodeWithContentDescription("Sorted by Size, Smallest first. Reverse order").assertExists()
        // Only the connected server: phone and the offline profile drop out of the status lines.
        rule.onAllNodesWithText(serverEnvironmentLabel(aliasScope))[0].performClick()
        rule.onNodeWithText("On this phone: Engine not running, open On this phone · 0 transfers").assertDoesNotExist()
        rule.onNodeWithText("Showing 2").assertExists()
        rule.onNodeWithText("Group by environment").performScrollTo()
        rule.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        rule.onNodeWithText(serverEnvironmentLabel(aliasScope) + " · 2 shown").assertExists()
        val saved = CombinedViewPreferences(context).load()
        assertEquals(UnifiedSortKey.SIZE, saved.sortKey)
        assertEquals(UnifiedSortDirection.ASCENDING, saved.direction)
        assertTrue(saved.scope is EnvironmentViewScope.Only)
        assertEquals(1, (saved.scope as EnvironmentViewScope.Only).ids.size)
        assertTrue(saved.groupByEnvironment)
    }

    @Test fun staleServerListIsLabelledAsLastKnown() {
        show(stale = true)
        rule.onNodeWithText(serverEnvironmentLabel(aliasScope) + ": Out of date, showing last list · 2 transfers")
            .assertExists()
    }
}
