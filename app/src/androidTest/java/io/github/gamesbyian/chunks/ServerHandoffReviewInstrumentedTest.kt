package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A phone transfer's exported .torrent only primes the server Add review. */
@RunWith(AndroidJUnit4::class)
class ServerHandoffReviewInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val torrent = ("d8:announce32:https://tracker.example/announce4:info" +
        "d6:lengthi5e4:name14:phone-file.txt12:piece lengthi16384e" +
        "6:pieces20:01234567890123456789ee").toByteArray()

    @Test fun phoneDocumentIsDisclosedConsumedOnceAndNeverSubmittedAutomatically() {
        var pending by mutableStateOf<ByteArray?>(torrent)
        var consumed = 0
        var mutations = 0
        rule.setContent {
            MaterialTheme {
                VerifiedAddScreen(
                    RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", "")),
                    magnet = "", destination = "/home/dev/chunks-public-test",
                    onMagnet = {}, onDestination = {}, onBack = {}, onComplete = {},
                    onMutationStarted = { mutations++; true }, onMutationSettled = {},
                    initialTorrent = pending,
                    onInitialTorrentConsumed = { consumed++; pending = null }
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("phone-file.txt", substring = true).assertExists()
        rule.onNodeWithText("From a transfer on this phone", substring = true).assertExists()
        rule.onNodeWithText("may contain a private passkey", substring = true).assertExists()
        assertEquals(1, consumed)
        assertEquals("Prefilling the review must not start any server mutation", 0, mutations)
    }
}
