package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
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
 * Real Compose click handlers at narrow 200% text. No fake RPC, no server
 * mutation or local engine start: route authority remains in MainActivity.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class CombinedAddAndFreshnessTest {
    @get:Rule val rule = createComposeRule()

    private fun show(
        serverLabel: String?,
        onLocal: () -> Unit,
        onServer: (() -> Unit)?,
        otherSavedServers: Boolean = false,
        pendingSharedMagnet: Boolean = false,
        pendingSharedTorrent: Boolean = false,
        readingSharedTorrent: Boolean = false
    ) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChunksTheme {
                    Surface {
                        CombinedAddActions(serverLabel, onLocal, onServer,
                            hasOtherSavedServers = otherSavedServers,
                            pendingSharedMagnet = pendingSharedMagnet,
                            pendingSharedTorrent = pendingSharedTorrent,
                            readingSharedTorrent = readingSharedTorrent)
                    }
                }
            }
        }
    }

    @Test fun sharedTorrentIsSourceOnlyUntilExactLocalOrServerReviewChosen() {
        val local = AtomicInteger()
        val server = AtomicInteger()
        val label = "Server · Personal seedbox #2"
        show(label, { local.incrementAndGet() }, { server.incrementAndGet() },
            pendingSharedTorrent = true)
        rule.onNodeWithText("Choose a destination for the shared .torrent").assertIsDisplayed()
        rule.onNodeWithText("A shared .torrent is ready. Choose where to review it. Nothing is added until you confirm.")
            .assertExists()
        assertEquals(0, local.get())
        assertEquals(0, server.get())
        rule.onNodeWithContentDescription("Review shared .torrent on this device").performClick()
        assertEquals(1, local.get())
        assertEquals(0, server.get())
        rule.onNodeWithContentDescription("Review shared .torrent on $label").performClick()
        assertEquals(1, local.get())
        assertEquals(1, server.get())
        rule.onNodeWithText("content://", substring = true).assertDoesNotExist()
    }

    @Test fun sharedDocumentButtonsRefuseClicksWhileLocalValidationIsRunning() {
        val local = AtomicInteger()
        val server = AtomicInteger()
        val label = "Server · Personal seedbox #2"
        show(label, { local.incrementAndGet() }, { server.incrementAndGet() },
            pendingSharedTorrent = true, readingSharedTorrent = true)
        rule.onNodeWithText("Checking the shared .torrent on this device…").assertExists()
        rule.onNodeWithContentDescription("Review shared .torrent on this device").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Review shared .torrent on $label").assertIsNotEnabled()
        assertEquals(0, local.get())
        assertEquals(0, server.get())
    }

    @Test fun sharedMagnetRequiresExactDestinationAndDoesNotDisplaySecretSource() {
        val local = AtomicInteger()
        val server = AtomicInteger()
        val label = "Server · Personal seedbox #2"
        show(label, { local.incrementAndGet() }, { server.incrementAndGet() },
            pendingSharedMagnet = true)
        rule.onNodeWithText("A shared magnet is ready. Choose where to review it. Nothing is added until you confirm.")
            .assertExists()
        rule.onNodeWithContentDescription("Review shared magnet on this device").assertIsDisplayed()
            .performClick()
        assertEquals(1, local.get())
        assertEquals(0, server.get())
        rule.onNodeWithContentDescription("Review shared magnet on $label").assertIsDisplayed()
            .performClick()
        assertEquals(1, local.get())
        assertEquals(1, server.get())
        rule.onNodeWithText("magnet:?xt=urn", substring = true).assertDoesNotExist()
    }

    @Test fun exactLocalAndAuthenticatedServerCallbacksStaySeparateAt320And200Percent() {
        val local = AtomicInteger()
        val server = AtomicInteger()
        val name = "Server · Personal seedbox #2"
        show(name, { local.incrementAndGet() }, { server.incrementAndGet() })

        rule.onNodeWithContentDescription("Add download on this device").assertIsDisplayed()
            .performClick()
        assertEquals(1, local.get())
        assertEquals(0, server.get())

        rule.onNodeWithContentDescription("Add download on $name").assertIsDisplayed()
            .performClick()
        assertEquals(1, local.get())
        assertEquals(1, server.get())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun savedOnlyServerDoesNotExposeUnverifiedAddRoute() {
        val local = AtomicInteger()
        val name = "Server · Saved but disconnected"
        show(name, { local.incrementAndGet() }, null)
        rule.onNodeWithContentDescription("Add download on $name").assertDoesNotExist()
        rule.onNodeWithContentDescription("Add download on this device").assertIsDisplayed()
            .performClick()
        assertEquals(1, local.get())
    }

    @Test fun localOnlyDoesNotDescribeUnconfiguredSavedServersAt320And200Percent() {
        show(null, {}, null)
        rule.onNodeWithText("Add on this device").assertIsDisplayed()
        rule.onNodeWithText("Other saved servers need their own connection before adding.")
            .assertDoesNotExist()
        rule.onNodeWithText("Choosing which downloads to view never changes the Add destination.")
            .assertDoesNotExist()
    }

    @Test fun savedOfflineServerIsNamedOnlyAsUnavailableAddAuthority() {
        show(null, {}, null, otherSavedServers = true)
        rule.onNodeWithText("Other saved servers need their own connection before adding.")
            .assertExists()
        rule.onNodeWithText("Choosing which downloads to view never changes the Add destination.")
            .assertExists()
        rule.onNodeWithText("Add on this device").assertIsDisplayed()
        rule.onNodeWithText("Add on Server", substring = true).assertDoesNotExist()
    }

    @Test fun signedInOnlyServerDoesNotClaimAnyOtherSavedAccounts() {
        show("Server · exact session", {}, {})
        rule.onNodeWithText("Add on Server · exact session").assertExists()
        rule.onNodeWithText("Other saved servers need their own connection before adding.")
            .assertDoesNotExist()
        rule.onNodeWithText("Choosing which downloads to view never changes the Add destination.")
            .assertExists()
    }

    private fun source(state: EnvironmentAvailability, complete: Boolean, at: Long?) =
        EnvironmentTransferSnapshot(EnvironmentId("a".repeat(64)), state, complete, at, emptyList())

    @Test fun liveCompleteRowsNeedNoWarningButUnverifiedLiveOnesDo() {
        assertNull(combinedRowFreshnessLabel(source(EnvironmentAvailability.LIVE, true, 123L)))
        assertEquals("List not verified complete; last known facts only",
            combinedRowFreshnessLabel(source(EnvironmentAvailability.LIVE, false, 123L)))
        assertEquals("List not verified complete; last known facts only",
            combinedRowFreshnessLabel(source(EnvironmentAvailability.LIVE, true, null)))
    }

    @Test fun staleOfflineLockedAndMissingRowsNeverAppearLive() {
        assertEquals("Out of date; last known facts only",
            combinedRowFreshnessLabel(source(EnvironmentAvailability.STALE, true, 123L)))
        assertEquals("Offline; last known facts only",
            combinedRowFreshnessLabel(source(EnvironmentAvailability.OFFLINE, true, 123L)))
        assertEquals("Locked; current status hidden",
            combinedRowFreshnessLabel(source(EnvironmentAvailability.LOCKED, true, 123L)))
        assertEquals("Source unavailable; status unverified", combinedRowFreshnessLabel(null))
    }
}
