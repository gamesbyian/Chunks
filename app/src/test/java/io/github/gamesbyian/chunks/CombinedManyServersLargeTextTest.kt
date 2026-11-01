package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Large saved roster: wrapping source chips must not strand actual transfers.
 * Synthetic Compose/layout evidence only; no server auth or physical TalkBack.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class CombinedManyServersLargeTextTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val hash = "e".repeat(40)
    private val activeScope = "https://active.example/RPC2\u0000owner"
    private val savedReads = AtomicInteger()
    private val profiles = (1..12).map { n ->
        val scope = "https://seed$n.example/RPC2\u0000owner"
        CombinedSavedProfile(
            EnvironmentSnapshots.serverId(scope.substringBefore('\u0000'), "owner").value,
            "Seedbox $n", scope,
            listOnly = { savedReads.incrementAndGet(); error("Saved profile was contacted without a tap") })
    }

    private fun show() {
        CombinedViewPreferences(context).save(UnifiedTransferQuery())
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) {
                ChunksTheme { Surface(color = ChunksColors.Background) {
                    CombinedTransfersScreen(
                        server = CombinedServerSource(
                            activeScope,
                            listOf(RemoteTransfer(hash, "Public completed archive",
                                1_024, 1_024, false, "/d", 0, null)),
                            stale = false, fullView = true,
                            observedAtEpochMillis = System.currentTimeMillis()
                        ),
                        savedProfiles = profiles,
                        onOpenPhone = {}, onOpenServer = {}, onBack = {}
                    )
                } }
            }
        }
    }

    @Test fun twelveServerChoicesDoNotStrandLiveTransferAt320And200Percent() {
        show()
        val list = rule.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("Public completed archive"))
        rule.onNodeWithText("Public completed archive").assertExists()
        val detail = "Details for Public completed archive on " + serverEnvironmentLabel(activeScope)
        list.performScrollToNode(androidx.compose.ui.test.hasContentDescription(detail))
        rule.onNodeWithContentDescription(detail).performClick()
        rule.onNodeWithText("Transfer details").assertExists()
        rule.onNodeWithText("Exact infohash: $hash").assertExists()
        assertEquals("No saved account may fetch just because its chip is visible", 0, savedReads.get())
        assertNull(LocalEngineHolder.current())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun finalSavedSourceChipCanBeSelectedWithoutFetchingOrExecuting() {
        show()
        // Chips are inside one lazy item that wraps vertically. The last
        // profile remains accessible by vertical motion, no sideways strip.
        rule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Server · Seedbox 12"))
        rule.onNodeWithText("Server · Seedbox 12").performClick()
        rule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("No transfers are visible", substring = true))
        assertEquals(0, savedReads.get())
        assertNull(LocalEngineHolder.current())
    }
}
