package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
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
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class RemoteBatchLifecycleLargeTextTest {
    @get:Rule val rule = createComposeRule()

    private val selected = listOf(
        RemoteTransfer("1".repeat(40), "First stopped item", 1000, 100, false, "/p"),
        RemoteTransfer("2".repeat(40), "Second started item", 1000, 100, true, "/p")
    )

    @Test fun narrowTwoHundredPercentActionsFitTwoAcrossAndCannotBypassMutationLease() {
        var attempts = 0
        var settled = 0
        val commands = mutableListOf<String>()
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) {
                ChunksTheme {
                    Surface {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            RemoteBatchLifecyclePanel(
                                selected = selected,
                                enabled = true,
                                onMutationStarted = { attempts++; false },
                                onSettled = { settled++ },
                                performLifecycle = { hash, _ ->
                                    commands += hash
                                    LifecycleOutcome.Verified(true)
                                }
                            )
                        }
                    }
                }
            }
        }

        val start = rule.onNode(hasText("Start 1") and hasClickAction()).getUnclippedBoundsInRoot()
        val stop = rule.onNode(hasText("Stop 1") and hasClickAction()).getUnclippedBoundsInRoot()
        assertEquals("Start/Stop must share one row at 200% text", start.top, stop.top)
        assertTrue("Start/Stop targets must not overlap", start.right <= stop.left)
        assertTrue(start.bottom - start.top >= 48.dp)
        assertTrue(stop.bottom - stop.top >= 48.dp)
        
        rule.onNodeWithText("Start 1").performClick()

        rule.onNodeWithText("Cancel").performScrollTo()
        val confirm = rule.onNode(hasText("Confirm Start") and hasClickAction()).getUnclippedBoundsInRoot()
        val cancel = rule.onNode(hasText("Cancel") and hasClickAction()).getUnclippedBoundsInRoot()
        assertEquals("Confirm/Cancel must share one row at 200% text", confirm.top, cancel.top)
        assertTrue("Confirmation controls must not overlap", confirm.right <= cancel.left)
        assertTrue(confirm.bottom - confirm.top >= 48.dp)
        assertTrue(cancel.bottom - cancel.top >= 48.dp)
        
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, attempts)

        rule.onNodeWithText("Stop 1").performScrollTo().performClick()
        rule.onNodeWithText("Confirm Stop").performScrollTo().performClick()
        rule.onNodeWithText("Another server change or refresh is in progress. Nothing was sent.")
            .assertExists()
        assertEquals(1, attempts)
        assertEquals(0, settled)
        assertTrue("Gate refusal must never send a lifecycle RPC", commands.isEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun largeTextRetainsLegibleBatchReviewOnWiderLocals() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Surface {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            RemoteBatchLifecyclePanel(selected, enabled = false,
                                onMutationStarted = { false }, onSettled = {},
                                performLifecycle = { _, _ -> LifecycleOutcome.Verified(true) })
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("Start 1").assertExists()
        rule.onNodeWithText("Stop 1").assertExists()
        rule.onNodeWithText("Batch Start / Stop").assertExists()
    }
}
