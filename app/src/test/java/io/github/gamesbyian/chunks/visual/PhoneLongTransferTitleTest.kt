package io.github.gamesbyian.chunks.visual

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.LocalTransferCard
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Presentation-only proof; callbacks cannot start, stop, remove or mutate a transfer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class PhoneLongTransferTitleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun collapsedPhoneTitleFitsTwoLinesAndExpandedTitleRemainsFullyReadable() {
        val title = "A very long season and episode transfer filename " +
            "with multiple episode names and descriptive words ".repeat(5) +
            "and a complete accessible ending"
        val transfer = LocalTransferStatus(
            infoHash = "e".repeat(40),
            name = title,
            state = LocalTransferState.DOWNLOADING,
            stopped = false,
            progressPpm = 730_000,
            wantedBytes = 1_000_000,
            doneBytes = 730_000,
            payloadDownloadedBytes = 730_000,
            payloadUploadedBytes = 100,
            peers = 1,
            savePath = "/synthetic/phone",
            error = null
        )
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Column(Modifier.width(320.dp).height(700.dp)
                        .verticalScroll(rememberScrollState())) {
                        LocalTransferCard(
                            transfer = transfer, busy = false,
                            onToggle = {}, onRemove = {}, onShowFiles = {})
                    }
                }
            }
        }
        val titleNode = rule.onNodeWithContentDescription(title)
        val compact = titleNode.getUnclippedBoundsInRoot()
        assertTrue("Collapsed title stays within two scaled text lines",
            compact.bottom - compact.top <= 104.dp)
        assertEquals(
            title,
            titleNode.fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
                .single()
        )
        rule.onNodeWithContentDescription("More details for $title on this phone")
            .performScrollTo().performClick()
        rule.waitForIdle()
        val expanded = rule.onNodeWithContentDescription(title).getUnclippedBoundsInRoot()
        assertTrue("Expanded details expose the full title rather than ellipsizing it",
            expanded.bottom - expanded.top > compact.bottom - compact.top + 30.dp)
    }
}
