package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed Compose source precedence, without a live peer, provider or RPC
 * mutation. The readers are controlled solely to pause a foreground preference
 * lookup and supply an eligible copied source; the real UI and source callbacks
 * are exercised at the actual Android text field.
 */
@RunWith(AndroidJUnit4::class)
class VerifiedAddClipboardPrefillRaceInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val copied = "magnet:?xt=urn:btih:" + "ab".repeat(20) + "&dn=clipboard"
    private val typed = "magnet:?xt=urn:btih:" + "cd".repeat(20) + "&dn=explicit"

    private fun remote() = RTorrentRemote(
        RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))

    @Test fun typedSourceDuringSuspendedPreferenceReadAlwaysWins() {
        val enteredRead = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Boolean>()
        var source by mutableStateOf("")
        var clipboardReads = 0
        var candidateWrites = 0
        val server = remote()
        rule.setContent {
            MaterialTheme {
                VerifiedAddScreen(
                    remote = server,
                    magnet = source,
                    destination = "/srv/disposable",
                    onMagnet = { source = it; candidateWrites++ },
                    onDestination = {},
                    onBack = {}, onComplete = {},
                    onMutationStarted = { error("This source-selection test must not issue an RPC write") },
                    onMutationSettled = {},
                    clipboardEnabledReader = {
                        enteredRead.complete(Unit)
                        releaseRead.await()
                    },
                    clipboardTextReader = { clipboardReads++; copied }
                )
            }
        }
        rule.waitUntil(8_000) { enteredRead.isCompleted }
        val list = rule.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("Magnet or torrent link"))
        rule.onNodeWithText("Magnet or torrent link").performTextReplacement(typed)
        rule.runOnIdle {
            assertEquals("The explicit source was entered", typed, source)
            assertEquals(1, candidateWrites)
            releaseRead.complete(true)
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("The earlier clipboard may not overwrite a typed draft", typed, source)
            assertEquals("Cancelled stale prefill must not even examine the clipboard", 0, clipboardReads)
            assertEquals("No second implicit source replacement", 1, candidateWrites)
        }
    }

    @Test fun anUnchangedEmptySourceStillPrefillsOnceWithoutSubmitting() {
        var source by mutableStateOf("")
        var clipboardReads = 0
        var mutations = 0
        val server = remote()
        rule.setContent {
            MaterialTheme {
                VerifiedAddScreen(
                    remote = server,
                    magnet = source,
                    destination = "/srv/disposable",
                    onMagnet = { source = it },
                    onDestination = {},
                    onBack = {}, onComplete = {},
                    onMutationStarted = { mutations++; false },
                    onMutationSettled = {},
                    clipboardEnabledReader = { true },
                    clipboardTextReader = { clipboardReads++; copied }
                )
            }
        }
        rule.waitUntil(8_000) { source == copied }
        rule.runOnIdle {
            assertEquals(copied, source)
            assertEquals("Clipboard candidate is read only once in the foreground", 1, clipboardReads)
            assertEquals("Prefill is never a transfer Add or Start", 0, mutations)
            assertTrue("Source is a draft, not a dispatched command", source.startsWith("magnet:?"))
        }
    }
}
