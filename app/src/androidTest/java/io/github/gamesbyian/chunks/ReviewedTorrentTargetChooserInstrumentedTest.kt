package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Compose checks for an Add target switch, with no actual RPC mutation. */
@RunWith(AndroidJUnit4::class)
class ReviewedTorrentTargetChooserInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectedTargetRequiresAnExplicitTapAndCopiesTheOriginalDefinition() {
        val verifiedBytes = ("d4:info" +
            "d6:lengthi5e4:name8:test.txt12:piece lengthi16384e" +
            "6:pieces20:01234567890123456789ee").toByteArray()
        val calls = mutableListOf<ByteArray>()
        compose.setContent {
            MaterialTheme {
                ReviewedTorrentTargetChooser(
                    verifiedTorrentBytes = verifiedBytes,
                    enabled = true,
                    onPrepareServerReview = { calls += it })
            }
        }
        compose.onNodeWithText("Choose where to add this .torrent").assertExists()
        compose.onNodeWithText("Original tracker URLs may contain private passkeys.",
            substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, calls.size) }
        compose.onNodeWithText("Review .torrent on connected server").performClick()
        compose.runOnIdle {
            assertEquals(1, calls.size)
            assertArrayEquals(verifiedBytes, calls.single())
            assertFalse(verifiedBytes === calls.single())
            calls.single()[0] = 0
            assertEquals('d'.code.toByte(), verifiedBytes[0])
        }
    }

    @Test fun unavailableRemoteTargetCannotPrepareServerReview() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                ReviewedTorrentTargetChooser(
                    verifiedTorrentBytes = byteArrayOf(1, 2, 3),
                    enabled = false,
                    onPrepareServerReview = { calls++ })
            }
        }
        compose.onNodeWithText("Review .torrent on connected server")
            .assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
