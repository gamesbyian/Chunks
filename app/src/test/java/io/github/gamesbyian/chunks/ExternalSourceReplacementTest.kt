package io.github.gamesbyian.chunks

import android.net.Uri
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.gamesbyian.chunks.privacy.TransferLockMode
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual app navigation with a delayed privacy-gated source handoff. No engine Add. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ExternalSourceReplacementTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE

    private fun policy(mode: TransferLockMode) {
        assertTrue(TransferPrivacyPolicyStore(context).setMode(scope, mode, true))
        assertTrue(TransferPrivacyRuntime.session.configure(scope, mode, true))
    }

    @After fun cleanup() { policy(TransferLockMode.NEVER) }

    @Test fun aNewDocumentCannotReviveAnUnconsumedMagnetBehindThePhoneLock() {
        val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40) + "&dn=Superseded"
        val incomingMagnet = mutableStateOf<String?>(null)
        val incomingDocument = mutableStateOf<Uri?>(null)
        policy(TransferLockMode.ONCE_PER_PROCESS)
        rule.setContent {
            ChunksTheme {
                ChunksApp(sharedMagnet = incomingMagnet.value, incomingTorrent = incomingDocument.value,
                    onIncomingMagnetConsumed = { incomingMagnet.value = null },
                    onIncomingTorrentConsumed = { incomingDocument.value = null })
            }
        }
        rule.runOnIdle { incomingMagnet.value = magnet }
        rule.onNodeWithText("Transfers on this device are locked").assertExists()
        // The provider intentionally refuses this new source. Even a refused
        // document must not cause the old, never-consumed magnet to be offered.
        rule.runOnIdle {
            incomingDocument.value = Uri.parse("content://missing.example/new.torrent")
        }
        rule.waitForIdle()
        rule.onNodeWithText("Transfers on this device are locked").assertExists()
        rule.onNodeWithText("Back to Chunks").performClick()
        // The old Back route disposed the privacy gate. Fixed navigation now
        // keeps one local gate mounted when moving between local destinations.
        // Navigate to Connections before the test-only policy reset so the
        // next Add mounts a fresh gate that reads the updated store.
        rule.onNodeWithText("Connections").performClick()
        rule.runOnIdle { policy(TransferLockMode.NEVER) }
        rule.onNodeWithText("+ Add").performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodes(hasText("The shared file was unavailable", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasSetTextAction() and hasText(magnet)).assertDoesNotExist()
        rule.onNodeWithText("Magnet for: Superseded").assertDoesNotExist()
    }
}
