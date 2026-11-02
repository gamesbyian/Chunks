package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual review UI, no server access. In particular, Cancel must not dispatch d.erase. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class TransferSessionRemoveLargeTextTest {
    @get:Rule val rule = createComposeRule()
    private val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
    private val transfer = RemoteTransfer(
        hash = "c".repeat(40), name = "Long public example to keep readable",
        sizeBytes = 512, completedBytes = 512, started = false,
        directory = "/downloads/specific-profile"
    )
    private var mutations = 0

    private fun show() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChunksTheme {
                    Surface {
                      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        TransferSessionRemoveControl(
                            remote, transfer, snapshotStale = false, mainView = true,
                            onMutationStarted = { mutations++; false }, onSettled = {}
                        )
                      }
                    }
                }
            }
        }
    }

    @Test fun confirmAndCancelGrowWith200PercentTextAt320DpWithoutMutation() {
        show()
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Confirm removal from rTorrent").assertExists()
        rule.onNodeWithText("Infohash: " + "c".repeat(40)).assertExists()
        rule.onNodeWithText("Current directory: /downloads/specific-profile").assertExists()
        rule.onNodeWithText("Remove transfer and data (unavailable)").assertExists()
        val confirm = rule.onNodeWithText("Remove transfer, keep data").performScrollTo().getUnclippedBoundsInRoot()
        val cancel = rule.onNodeWithText("Cancel").performScrollTo().getUnclippedBoundsInRoot()
        assertTrue("Confirm needs a minimum 52dp touch target", confirm.bottom - confirm.top >= 52.dp)
        assertTrue("Cancel needs a minimum 48dp touch target", cancel.bottom - cancel.top >= 48.dp)
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        rule.onNodeWithText("Remove transfer, keep data").assertDoesNotExist()
        assertEquals("Cancel must not request a remote mutation", 0, mutations)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun exactRemovalReviewRemainsReachableOn412Dp() {
        show()
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Remove transfer, keep data").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, mutations)
    }

    @Test fun staleInventoryNeverOffersDestructiveConfirmation() {
        rule.setContent {
            ChunksTheme {
                Surface {
                    TransferSessionRemoveControl(remote, transfer,
                        snapshotStale = true, mainView = true,
                        onMutationStarted = { mutations++; false }, onSettled = {})
                }
            }
        }
        rule.onNodeWithText("Refresh this stale server snapshot before removing a transfer.").assertExists()
        rule.onNodeWithText("Remove transfer, keep data").assertDoesNotExist()
        assertEquals(0, mutations)
    }
}
