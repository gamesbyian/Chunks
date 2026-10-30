package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
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

/**
 * Presentation regression: no RPC or mutation fence is exercised by the fake
 * unreachable server. Review/confirm semantics remain owned by the real control.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class TransferLifecycleControlLargeTextTest {
    @get:Rule val rule = createComposeRule()

    private fun show(started: Boolean, scale: Float, attempts: MutableList<String>) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                ChunksTheme {
                    Surface {
                        TransferLifecycleControl(
                            remote = RTorrentRemote(RemoteCredentials(
                                "https://unreachable.example/RPC2", "fixture", "placeholder")),
                            transfer = RemoteTransfer(
                                "1".repeat(40), "A very long named transfer for the exact remote server",
                                10_000, 0, started, "/fixture"),
                            snapshotStale = false,
                            snapshotRevision = 1,
                            onMutationStarted = { attempts += "lease refused"; false },
                            onSettled = { attempts += "settled" }
                        )
                    }
                }
            }
        }
    }

    @Test fun confirmationStacksAndButtonsRemainReachableAt200Percent() {
        val attempts = mutableListOf<String>()
        show(started = false, scale = 2f, attempts = attempts)
        rule.onNodeWithText("Start transfer…").performClick()

        val confirm = rule.onNodeWithText("Confirm start").getUnclippedBoundsInRoot()
        val cancel = rule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()
        assertTrue("Confirmation must precede cancel vertically at narrow widths", cancel.top >= confirm.bottom)
        assertTrue("Confirm must be at least 48dp tall", confirm.bottom - confirm.top >= 48.dp)
        assertTrue("Cancel must be at least 48dp tall", cancel.bottom - cancel.top >= 48.dp)
        assertEquals("Both actions should be full-width", confirm.left, cancel.left)
        assertEquals("Both actions should be full-width", confirm.right, cancel.right)

        rule.onRoot().captureRoboImage("build/chunks-visual/remote-lifecycle-confirm-320x700-200pct.png")
        rule.onNodeWithText("Cancel").performClick()
        assertTrue("Review and Cancel are never remote commands", attempts.isEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun diagnosticButtonsExpandWithoutFixedHeightAt200Percent() {
        val attempts = mutableListOf<String>()
        show(started = true, scale = 2f, attempts = attempts)
        val diagnose = rule.onNodeWithText("Diagnose download activity")
        diagnose.assertExists()
        val measure = rule.onNodeWithText("Measure download progress (3 samples)")
        val measureBounds = measure.getUnclippedBoundsInRoot()
        val diagnoseBounds = diagnose.getUnclippedBoundsInRoot()
        assertTrue(measureBounds.bottom - measureBounds.top >= 48.dp)
        assertTrue(diagnoseBounds.bottom - diagnoseBounds.top >= 48.dp)
        rule.onRoot().captureRoboImage("build/chunks-visual/remote-lifecycle-diagnose-412x892-200pct.png")
        assertTrue("Viewing diagnostics should not issue commands", attempts.isEmpty())
    }
}
