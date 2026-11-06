package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
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
        onPhone: () -> Unit,
        onServer: (() -> Unit)?,
        otherSavedServers: Boolean = false,
        pendingSharedMagnet: Boolean = false
    ) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChunksTheme {
                    Surface {
                        CombinedAddActions(serverLabel, onPhone, onServer,
                            hasOtherSavedServers = otherSavedServers,
                            pendingSharedMagnet = pendingSharedMagnet)
                    }
                }
            }
        }
    }

    @Test fun sharedMagnetRequiresExactDestinationAndDoesNotDisplaySecretSource() {
        val phone = AtomicInteger()
        val server = AtomicInteger()
        val label = "Server · Personal seedbox #2"
        show(label, { phone.incrementAndGet() }, { server.incrementAndGet() },
            pendingSharedMagnet = true)
        rule.onNodeWithText("A shared magnet is ready. Choose where to review it. Nothing is added until you confirm.")
            .assertExists()
        rule.onNodeWithContentDescription("Review shared magnet on this phone").assertIsDisplayed()
            .performClick()
        assertEquals(1, phone.get())
        assertEquals(0, server.get())
        rule.onNodeWithContentDescription("Review shared magnet on $label").assertIsDisplayed()
            .performClick()
        assertEquals(1, phone.get())
        assertEquals(1, server.get())
        rule.onNodeWithText("magnet:?xt=urn", substring = true).assertDoesNotExist()
    }

    @Test fun exactPhoneAndAuthenticatedServerCallbacksStaySeparateAt320And200Percent() {
        val phone = AtomicInteger()
        val server = AtomicInteger()
        val name = "Server · Personal seedbox #2"
        show(name, { phone.incrementAndGet() }, { server.incrementAndGet() })

        rule.onNodeWithContentDescription("Add download on this phone").assertIsDisplayed()
            .performClick()
        assertEquals(1, phone.get())
        assertEquals(0, server.get())

        rule.onNodeWithContentDescription("Add download on $name").assertIsDisplayed()
            .performClick()
        assertEquals(1, phone.get())
        assertEquals(1, server.get())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun savedOnlyServerDoesNotExposeUnverifiedAddRoute() {
        val phone = AtomicInteger()
        val name = "Server · Saved but disconnected"
        show(name, { phone.incrementAndGet() }, null)
        rule.onNodeWithContentDescription("Add download on $name").assertDoesNotExist()
        rule.onNodeWithContentDescription("Add download on this phone").assertIsDisplayed()
            .performClick()
        assertEquals(1, phone.get())
    }

    @Test fun phoneOnlyDoesNotDescribeUnconfiguredSavedServersAt320And200Percent() {
        show(null, {}, null)
        rule.onNodeWithText("Add on this phone").assertIsDisplayed()
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
        rule.onNodeWithText("Add on this phone").assertIsDisplayed()
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
