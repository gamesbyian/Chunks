package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Remote Add shows the magnet check before review, and a base32 magnet is
 * reviewed as its standard hex identity. Review only: nothing is sent; the
 * address is unroutable and any mutation start fails the test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi", fontScale = 2.0f)
class RemoteMagnetDraftCheckTest {
    @get:Rule val rule = createComposeRule()

    private fun show(initial: String) {
        val magnet = mutableStateOf(initial)
        val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:9/RPC2", "", ""))
        rule.setContent {
            ChunksTheme { Surface {
                VerifiedAddScreen(remote, magnet = magnet.value, destination = "/srv/one-off",
                    onMagnet = { magnet.value = it }, onDestination = {},
                    onBack = {}, onComplete = {},
                    onMutationStarted = { error("Review must never mutate a server") },
                    onMutationSettled = {})
            } }
        }
    }

    private fun reveal(text: String) =
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))

    @Test fun base32MagnetIsReviewedAsItsStandardHashAtLargeText() {
        show("magnet:?xt=urn:btih:3WBFL3G4PSSV7MF37AJSHWDQMLNR63I4&dn=Sintel")
        reveal("Magnet for: Sintel")
        rule.onNodeWithText("Magnet for: Sintel").assertExists()
        reveal("Review Add")
        rule.onNodeWithText("Review Add").performClick()
        reveal("Infohash: dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c")
        rule.onNodeWithText("Infohash: dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c").assertExists()
    }

    @Test fun mistypedMagnetIsExplainedBeforeReview() {
        show("magnet:?xt=urn:btih:" + "ab".repeat(19))
        reveal(MagnetDraftCheck.BAD_HASH)
        rule.onNodeWithText(MagnetDraftCheck.BAD_HASH).assertExists()
        rule.onNodeWithText("Review Add").assertDoesNotExist()
    }
}
