package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToNode
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
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
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CombinedCompactLabelTest {
    @get:Rule val rule = createComposeRule()

    private val a = EnvironmentId("server-a")
    private val b = EnvironmentId("server-b")
    private val c = EnvironmentId("server-c")

    @Test fun uniqueFriendlyNamesAreCompactAndCollisionsKeepExactLabels() {
        val exact = mapOf(
            EnvironmentSnapshots.LOCAL to LOCAL_ENVIRONMENT_LABEL,
            a to "Server · Wood · host.example/rutorrent/",
            b to "Server · ian @ host.example · host.example/rutorrent/",
            c to "Server · ian @ host.example · host.example/rutorrent2/")
        val compact = mapOf(
            EnvironmentSnapshots.LOCAL to LOCAL_ENVIRONMENT_LABEL,
            a to "Server · Wood",
            b to "Server · ian @ host.example",
            c to "Server · ian @ host.example")
        val shown = compactEnvironmentLabels(exact, compact)
        assertEquals(LOCAL_ENVIRONMENT_LABEL, shown[EnvironmentSnapshots.LOCAL])
        assertEquals("Server · Wood", shown[a])
        // Two instances on one host must never look identical.
        assertEquals(exact[b], shown[b])
        assertEquals(exact[c], shown[c])
    }

    @Test fun missingOrBlankCompactNameFallsBackToExact() {
        val exact = mapOf(a to "Server · x · h/RPC2", b to "Server · y · h/RPC2")
        val shown = compactEnvironmentLabels(exact, mapOf(a to "  "))
        assertEquals(exact, shown)
    }

    @Test fun compactServerLabelDropsOnlyThePath() {
        assertEquals("Server · ian @ box.example",
            compactServerLabel("https://box.example/rutorrent2/plugins/httprpc/action.php\u0000ian"))
        assertEquals("Server · box.example", compactServerLabel("https://box.example/RPC2\u0000"))
    }

    @Test fun rowsShowCompactOriginWhileSpokenActionsKeepExactIdentity() {
        val scope = "https://box.example/rutorrent2/plugins/httprpc/action.php\u0000ian"
        val rows = listOf(RemoteTransfer("1".repeat(40), "Big Archive", 900, 900, true, "/d", 0, 10))
        rule.setContent { ChunksTheme { Surface(color = ChunksColors.Background) {
            CombinedTransfersScreen(
                server = CombinedServerSource(scope, rows, stale = false, fullView = true,
                    observedAtEpochMillis = System.currentTimeMillis()),
                savedProfiles = emptyList(), onOpenLocal = {}, onOpenServer = {}, onBack = {})
        } } }
        val exact = serverEnvironmentLabel(scope)
        val details = "Details for Big Archive on $exact"
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(details))
        rule.onNodeWithContentDescription(details).assertExists()
        assertTrue("row shows the short profile origin beside its colour",
            rule.onAllNodesWithText("ian @ box.example", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("the RPC path is not repeated in the row",
            rule.onAllNodesWithText(exact, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
