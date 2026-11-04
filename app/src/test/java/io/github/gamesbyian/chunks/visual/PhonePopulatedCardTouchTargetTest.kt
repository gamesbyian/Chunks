package io.github.gamesbyian.chunks.visual

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.LocalTransferCard
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Closes the previously omitted *populated* phone-card touch-target tier:
 * actual primary controls, expanded disclosure and two-choice Remove review
 * at 200% fonts. All callbacks are inert; this test cannot delete or stop data.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class PhonePopulatedCardTouchTargetTest {
    @get:Rule val rule = createComposeRule()

    private val name = "An exceedingly long public fixture name for a large-font transfer"

    private fun show(widthDp: Int, heightDp: Int) {
        val fixture = LocalTransferStatus(
            infoHash = "f".repeat(40),
            name = name,
            state = LocalTransferState.DOWNLOADING,
            stopped = false,
            progressPpm = 420_000,
            wantedBytes = 1_000_000,
            doneBytes = 420_000,
            payloadDownloadedBytes = 420_000,
            payloadUploadedBytes = 900,
            peers = 2,
            savePath = "/fixture/downloads",
            error = null
        )
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Surface {
                        Column(
                            Modifier.width(widthDp.dp).height(heightDp.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            LocalTransferCard(
                                transfer = fixture,
                                busy = false,
                                onToggle = {},
                                onRemove = {},
                                onRemoveData = {},
                                onShowFiles = {}
                            )
                        }
                    }
                }
            }
        }
    }

    private fun auditProgressively(widthDp: Int, heightDp: Int) {
        show(widthDp, heightDp)
        val density = 1f
        val undersized = mutableListOf<String>()
        rule.waitForIdle()
        undersized += rule.smallTouchTargets(density)

        val details = rule.onNodeWithContentDescription("More details for $name on this phone")
        details.performScrollTo().performClick()
        rule.waitForIdle()
        undersized += rule.smallTouchTargets(density)

        rule.onNodeWithContentDescription("Remove transfer $name from this phone")
            .performScrollTo().performClick()
        rule.waitForIdle()
        undersized += rule.smallTouchTargets(density)

        rule.onNodeWithText("Remove transfer, keep data").performScrollTo()
        rule.waitForIdle()
        undersized += rule.smallTouchTargets(density)
        rule.onNodeWithText("Remove transfer and data").performScrollTo()
        rule.waitForIdle()
        undersized += rule.smallTouchTargets(density)

        rule.onRoot().captureRoboImage(
            "build/chunks-visual/phone-populated-200pct-${widthDp}x${heightDp}.png"
        )
        assertTrue(
            "Visible clickable phone-card targets under 48dp: ${undersized.distinct()}",
            undersized.isEmpty()
        )
    }

    @Test fun populatedPhoneCardHasUsableControlsAt320x700And200Percent() {
        auditProgressively(320, 700)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun populatedPhoneCardHasUsableControlsAt412x892And200Percent() {
        auditProgressively(412, 892)
    }
}
