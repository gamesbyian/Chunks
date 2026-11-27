package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.NewRemoteReadOnlyCapabilities
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import io.github.gamesbyian.chunks.privacy.TransferLockMode
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Typed-client privacy lock parity at the screen: combined Transfers narrowed to one typed client
 * is that client's own view and sits behind its gate (invariant control
 * `combined-typed-client-view-ungated`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CombinedProtocolPrivacyLockTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val id = "be".repeat(32)
    private val hash = "12".repeat(20)
    private val profile = CombinedReadOnlyProtocolProfile(id, RemoteBackendKind.DELUGE, "Garage box", 1L) {
        val now = System.currentTimeMillis()
        CombinedReadOnlyProtocolReceipt(RemoteBackendKind.DELUGE,
            EnvironmentTransferSnapshot(EnvironmentId(id), EnvironmentAvailability.LIVE, true, now,
                listOf(UnifiedTransferObservation(UnifiedTransferKey.of(EnvironmentId(id), hash),
                    "SecretTitle", "SecretTitle", 100L, 500_000, started = true,
                    downloadBytesPerSecond = 0L, uploadBytesPerSecond = 0L, peers = null))),
            NewRemoteReadOnlyCapabilities.forBackend(RemoteBackendKind.DELUGE, EnvironmentId(id), now))
    }
    private val scope = requireNotNull(CombinedReadOnlyProtocolAdmission.privacyScope(profile))

    private fun policy(mode: TransferLockMode) {
        assertTrue(TransferPrivacyPolicyStore(context).setMode(scope, mode, true))
        assertTrue(TransferPrivacyRuntime.session.configure(scope, mode, true))
    }

    @After fun cleanup() = policy(TransferLockMode.NEVER)

    private fun showOwnView() = rule.setContent {
        ChunksTheme {
            CombinedTransfersScreen(server = null, savedProfiles = emptyList(), onOpenLocal = {},
                onOpenServer = null, onBack = {}, protocolProfiles = listOf(profile),
                scopeFocusRequest = id)
        }
    }

    @Test fun aLockedTypedClientsOwnViewShowsTheLockAndNeverItsJobs() {
        policy(TransferLockMode.ONCE_PER_PROCESS)
        showOwnView()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Deluge client locked").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("SecretTitle", substring = true).assertDoesNotExist()
    }

    @Test fun anUnprotectedTypedClientsOwnViewOffersItsPrivacyControls() {
        showOwnView()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Privacy & authentication").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Deluge client locked").assertDoesNotExist()
        // Positive control for the locked case: the same job really is read and shown.
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("SecretTitle", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun unlock() {
        // An unlocked protected scope is shown only on a device with a secure screen lock.
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))
            .setIsDeviceSecure(true)
        val session = TransferPrivacyRuntime.session
        val ticket = requireNotNull(session.beginUnlock(scope))
        assertTrue(session.finishUnlock(ticket, deviceAuthenticated = true))
    }

    @Test fun lockNowHidesTheClientsRowsAtOnceNotOnTheNextTick() {
        policy(TransferLockMode.ONCE_PER_PROCESS)
        unlock()
        showOwnView()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("SecretTitle", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Freeze time: the screen's own 10 s tick must not be what hides the rows.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Lock now").performClick()
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithText("SecretTitle", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Deluge client locked").assertExists()
    }

    @Test fun leavingTheClientsOwnViewRelocksEveryEntryButStayingDoesNot() {
        policy(TransferLockMode.EVERY_ENTRY)
        unlock()
        val shown = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            ChunksTheme {
                if (shown.value) CombinedTransfersScreen(server = null, savedProfiles = emptyList(),
                    onOpenLocal = {}, onOpenServer = null, onBack = {},
                    protocolProfiles = listOf(profile), scopeFocusRequest = id)
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("SecretTitle", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("still unlocked while the view is open", TransferPrivacyRuntime.session.mayView(scope))
        rule.runOnIdle { shown.value = false }
        rule.waitForIdle()
        assertFalse("leaving the view relocks EVERY_ENTRY", TransferPrivacyRuntime.session.mayView(scope))
    }
}
