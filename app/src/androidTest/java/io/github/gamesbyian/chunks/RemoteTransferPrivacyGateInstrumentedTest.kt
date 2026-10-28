package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.privacy.RemoteTransferPrivacyGate
import io.github.gamesbyian.chunks.privacy.RemoteTransferPrivacyScope
import io.github.gamesbyian.chunks.privacy.TransferLockMode
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteTransferPrivacyGateInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val a = requireNotNull(RemoteTransferPrivacyScope.fromAccount(
        "https://fixture.invalid/RPC2", "alice"))
    private val b = requireNotNull(RemoteTransferPrivacyScope.fromAccount(
        "https://fixture.invalid/RPC2", "bob"))

    @Before fun reset() {
        val store = TransferPrivacyPolicyStore(context)
        listOf(a,b).forEach { scope ->
            assertTrue(store.setMode(scope, TransferLockMode.NEVER, true))
            assertTrue(TransferPrivacyRuntime.session.configure(scope,
                TransferLockMode.NEVER, true))
        }
    }
    @After fun cleanup() {
        val store = TransferPrivacyPolicyStore(context)
        listOf(a,b).forEach { scope ->
            TransferPrivacyRuntime.session.lockNow(scope)
            store.setMode(scope, TransferLockMode.NEVER, true)
            TransferPrivacyRuntime.session.configure(scope, TransferLockMode.NEVER, true)
        }
    }

    @Test fun enablingOneRemoteProfileHidesItsListButNotUnprotectedOtherAccount() {
        val store = TransferPrivacyPolicyStore(context)
        assertTrue(store.setMode(a, TransferLockMode.ONCE_PER_PROCESS, false))
        assertTrue(TransferPrivacyRuntime.session.configure(a,
            TransferLockMode.ONCE_PER_PROCESS, true))
        var protectedComposed = false
        compose.setContent {
            MaterialTheme {
                RemoteTransferPrivacyGate(scope = a, onBack = {}) {
                    protectedComposed = true
                    Text("PRIVATE REMOTE TORRENT NAME")
                }
            }
        }
        compose.onNodeWithText("rTorrent profile locked").assertExists()
        compose.onNodeWithText("PRIVATE REMOTE TORRENT NAME").assertDoesNotExist()
        compose.runOnIdle { assertFalse(protectedComposed) }

        assertEquals(TransferLockMode.NEVER, store.mode(b))
        assertTrue(TransferPrivacyRuntime.session.mayView(b))
        assertFalse(TransferPrivacyRuntime.session.mayView(a))
    }

    @Test fun defaultUnprotectedRemoteAccountOffersSeparatePrivacySettings() {
        compose.setContent {
            MaterialTheme {
                RemoteTransferPrivacyGate(scope = b, onBack = {}) {
                    Text("BOB PUBLIC DEMONSTRATION")
                }
            }
        }
        compose.onNodeWithText("BOB PUBLIC DEMONSTRATION").assertExists()
        compose.onNodeWithText("Privacy & authentication").assertExists()
    }
}
