package io.github.gamesbyian.chunks

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.privacy.GatedProtocolAdd
import io.github.gamesbyian.chunks.privacy.ProtocolTransferPrivacyGate
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

/** Typed-client privacy lock parity: the exact client scope hides its content until unlocked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtocolTransferPrivacyGateTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val profile = CombinedReadOnlyProtocolProfile("c0ffee".repeat(10) + "c0ff", RemoteBackendKind.DELUGE, "Box", 1L,
        inspect = { error("unused") })
    private val scope = requireNotNull(CombinedReadOnlyProtocolAdmission.privacyScope(profile))
    private val other = requireNotNull(CombinedReadOnlyProtocolAdmission.privacyScope(
        profile.copy(id = "decaf0".repeat(10) + "deca")))

    private fun policy(s: String, mode: TransferLockMode) {
        assertTrue(TransferPrivacyPolicyStore(context).setMode(s, mode, true))
        assertTrue(TransferPrivacyRuntime.session.configure(s, mode, true))
    }

    @After fun cleanup() { policy(scope, TransferLockMode.NEVER); policy(other, TransferLockMode.NEVER) }

    private fun show(s: String) = rule.setContent {
        ChunksTheme {
            ProtocolTransferPrivacyGate(scope = s, clientKind = "Deluge", onBack = {}) {
                Text("PROTECTED-ROWS")
            }
        }
    }

    @Test fun aLockedTypedClientNeverComposesItsContent() {
        policy(scope, TransferLockMode.ONCE_PER_PROCESS)
        show(scope)
        rule.onNodeWithText("Deluge client locked").assertExists()
        rule.onNodeWithText("PROTECTED-ROWS").assertDoesNotExist()
        rule.onNodeWithText("Unlock deluge client").assertExists()
    }

    @Test fun anUnprotectedTypedClientShowsContentAndOffersItsPrivacyControls() {
        show(scope)
        rule.onNodeWithText("PROTECTED-ROWS").assertExists()
        rule.onNodeWithText("Deluge client locked").assertDoesNotExist()
    }

    @Test fun anotherClientsLockDoesNotLockThisClient() {
        policy(other, TransferLockMode.ONCE_PER_PROCESS)
        show(scope)
        rule.onNodeWithText("PROTECTED-ROWS").assertExists()
    }

    private fun showAdd(s: String?) = rule.setContent {
        ChunksTheme { GatedProtocolAdd(privacyScope = s, clientKind = "Deluge", onBack = {}) { Text("ADD-FORM") } }
    }

    @Test fun addToALockedTypedClientIsBehindItsGate() {
        policy(scope, TransferLockMode.ONCE_PER_PROCESS)
        showAdd(scope)
        rule.onNodeWithText("Deluge client locked").assertExists()
        rule.onNodeWithText("ADD-FORM").assertDoesNotExist()
    }

    @Test fun addToAnUnverifiableClientIsRefused() {
        showAdd(null)
        rule.onNodeWithText("Cannot verify which client", substring = true).assertExists()
        rule.onNodeWithText("ADD-FORM").assertDoesNotExist()
    }

    @Test fun addToAnUnprotectedTypedClientIsShown() {
        showAdd(scope)
        rule.onNodeWithText("ADD-FORM").assertExists()
    }

    @Test fun aHostOwnedGateRowDoesNotRelockEveryEntryWhenDisposed() {
        policy(scope, TransferLockMode.EVERY_ENTRY)
        val session = TransferPrivacyRuntime.session
        assertTrue(session.finishUnlock(requireNotNull(session.beginUnlock(scope)), true))
        val shown = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            ChunksTheme {
                if (shown.value) ProtocolTransferPrivacyGate(scope = scope, clientKind = "Deluge",
                    onBack = {}, exitOnDispose = false) { Text("ROW") }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { shown.value = false }    // e.g. scrolled off a lazy list
        rule.waitForIdle()
        assertTrue("a disposed row is not a route exit", session.mayView(scope))
    }

    @Test fun anOwnGateStillRelocksEveryEntryOnExit() {
        policy(scope, TransferLockMode.EVERY_ENTRY)
        val session = TransferPrivacyRuntime.session
        assertTrue(session.finishUnlock(requireNotNull(session.beginUnlock(scope)), true))
        val shown = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            ChunksTheme {
                if (shown.value) ProtocolTransferPrivacyGate(scope = scope, clientKind = "Deluge",
                    onBack = {}) { Text("ROW") }
            }
        }
        rule.runOnIdle { shown.value = false }
        rule.waitForIdle()
        assertFalse(session.mayView(scope))
    }

    @Test fun changingExactClientScopeDoesNotCarryOpenPrivacySettingsAcrossClients() {
        policy(scope, TransferLockMode.NEVER)
        policy(other, TransferLockMode.NEVER)
        val selected = androidx.compose.runtime.mutableStateOf(scope)
        rule.setContent {
            ChunksTheme {
                ProtocolTransferPrivacyGate(scope = selected.value, clientKind = "Deluge",
                    onBack = {}) { Text("CLIENT-CONTENT") }
            }
        }
        rule.onNodeWithText("Privacy & authentication").performClick()
        rule.onNodeWithText("Deluge client privacy is off.", substring = true).assertExists()
        rule.runOnIdle { selected.value = other }
        rule.onNodeWithText("CLIENT-CONTENT").assertExists()
        rule.onNodeWithText("Deluge client privacy is off.", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Privacy & authentication").performClick()
        rule.onNodeWithText("Deluge client privacy is off.", substring = true).assertExists()
        rule.runOnIdle { selected.value = scope }
        rule.onNodeWithText("CLIENT-CONTENT").assertExists()
        rule.onNodeWithText("Deluge client privacy is off.", substring = true).assertDoesNotExist()
    }

    @Test fun theLockedTextNamesTheTypedClientNotRTorrent() {
        policy(scope, TransferLockMode.ONCE_PER_PROCESS)
        show(scope)
        rule.onNodeWithText("neither disconnects the Deluge client", substring = true).assertExists()
    }
}
