package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Combined Transfers must offer the same quick return-to-top as the owning lists. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class CombinedReturnToTopTest {
    @get:Rule val rule = createComposeRule()

    @Test fun deepScrollOffersAccessibleReturnToTopAtTwoHundredPercentText() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        CombinedViewPreferences(context).save(UnifiedTransferQuery())
        val transfers = (1..35).map { i ->
            RemoteTransfer(i.toString(16).padStart(40, '0'), "Transfer $i",
                1024L, 0L, false, "/fixture")
        }
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Surface(color = ChunksColors.Background) {
                        CombinedTransfersScreen(
                            server = CombinedServerSource(
                                aliasScope = "https://fixture.example/RPC2\u0000demo",
                                transfers = transfers, stale = false, fullView = true,
                                observedAtEpochMillis = 1L
                            ),
                            savedProfiles = emptyList(),
                            onOpenPhone = {}, onOpenServer = {}, onBack = {}
                        )
                    }
                }
            }
        }

        rule.onNodeWithContentDescription("Back to top").assertDoesNotExist()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(25)
        rule.onNodeWithContentDescription("Back to top").assertExists().performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithContentDescription("Back to top").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithContentDescription("Back to top").assertDoesNotExist()
    }
}
