package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.privacy.LocalTransferPrivacyGate
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
class LocalTransferPrivacyGateInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = "chunks-native-privacy-instrumentation-only"

    @Before fun isolate() {
        assertTrue(TransferPrivacyPolicyStore(context).setMode(
            scope, TransferLockMode.NEVER, authenticatedForChange = true))
        assertTrue(TransferPrivacyRuntime.session.configure(
            scope, TransferLockMode.NEVER, authenticatedForChange = true))
    }
    @After fun cleanup() {
        TransferPrivacyRuntime.session.lockNow(scope)
        TransferPrivacyPolicyStore(context).setMode(scope, TransferLockMode.NEVER, true)
        TransferPrivacyRuntime.session.configure(scope, TransferLockMode.NEVER, true)
    }

    @Test fun persistedEnabledPolicyPreventsPrivateContentFromEvenBeingComposed() {
        val store = TransferPrivacyPolicyStore(context)
        assertTrue(store.setMode(scope, TransferLockMode.ONCE_PER_PROCESS, false))
        TransferPrivacyRuntime.session.configure(scope,
            TransferLockMode.ONCE_PER_PROCESS, authenticatedForChange = true)
        var compositions = 0
        compose.setContent {
            MaterialTheme {
                LocalTransferPrivacyGate(scope) {
                    compositions++
                    Text("PERSONAL TORRENT TITLE AND PATH")
                }
            }
        }
        compose.onNodeWithText("Phone transfers locked").assertExists()
        compose.onNodeWithText("Unlock phone transfers").assertExists()
        compose.onNodeWithText("PERSONAL TORRENT TITLE AND PATH").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, compositions) }
        assertFalse(TransferPrivacyRuntime.session.mayView(scope))
    }

    @Test fun optOutDefaultShowsContentButOffersExplicitPrivacySettings() {
        compose.setContent {
            MaterialTheme {
                LocalTransferPrivacyGate(scope) {
                    Text("Local client status placeholder")
                }
            }
        }
        compose.onNodeWithText("Local client status placeholder").assertExists()
        compose.onNodeWithText("Privacy & authentication").performClick()
        compose.onNodeWithText(
            "Phone transfer privacy is off. Enabling it requires a device screen lock " +
                "and will immediately hide transfers."
        ).assertExists()
        compose.onNodeWithText("Phone transfers locked").assertDoesNotExist()
    }
}
