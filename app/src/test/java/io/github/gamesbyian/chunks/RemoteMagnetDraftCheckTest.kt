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

    @Test fun processOwnedSourceSurvivesSwitchingBetweenAddPresentationRoutes() {
        // The phone and server forms receive the *same* owner-provided state.
        // This test uses a deliberately disconnected server: source continuity
        // alone must never attempt an RPC or authorize a torrent Add.
        val source = mutableStateOf("magnet:?xt=urn:btih:" + "a".repeat(40) + "&dn=PhoneDraft")
        val onServer = mutableStateOf(false)
        val startChoice = mutableStateOf(true)
        val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:9/RPC2", "", ""))
        rule.setContent {
            ChunksTheme { Surface {
                if (onServer.value) {
                    VerifiedAddScreen(remote, magnet = source.value, destination = "/srv/one-off",
                        onMagnet = { source.value = it }, onDestination = {},
                        onBack = {}, onComplete = {},
                        onMutationStarted = { error("Viewing a shared source cannot mutate a server") },
                        onMutationSettled = {}, addStartState = startChoice)
                } else {
                    androidx.compose.material3.Text("Phone Add source is held by the app")
                }
            } }
        }
        rule.runOnIdle { onServer.value = true }
        reveal("Magnet for: PhoneDraft")
        rule.onNodeWithText("Magnet for: PhoneDraft").assertExists()
        reveal("Start downloading after verified Add (recommended)")
        rule.onNodeWithText("Start downloading after verified Add (recommended)").performClick()
        rule.runOnIdle { org.junit.Assert.assertFalse("Start selection is shared with phone", startChoice.value) }
        rule.runOnIdle {
            onServer.value = false
            source.value = "magnet:?xt=urn:btih:" + "b".repeat(40) + "&dn=EditedOnPhone"
        }
        rule.runOnIdle { onServer.value = true }
        reveal("Magnet for: EditedOnPhone")
        rule.onNodeWithText("Magnet for: EditedOnPhone").assertExists()
        rule.runOnIdle { org.junit.Assert.assertFalse("Unmount must not reset Start intent", startChoice.value) }
    }

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

    @Test fun copiedMagnetFillsAnEmptyServerAddWithItsNote() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("copied",
                "magnet:?xt=urn:btih:" + "e".repeat(40) + "&dn=Copied"))
        show("")
        reveal("Magnet for: Copied")
        rule.onNodeWithText("Filled from your clipboard. Nothing is added until you confirm.").assertExists()
    }

    @Test fun aTorrentLinkReplacesTheMagnetCheckWithAnExplicitFetch() {
        show("https://archive.org/download/x/x_archive.torrent")
        reveal("Torrent link: archive.org · x_archive.torrent")
        rule.onNodeWithText("Get torrent from link").assertExists()
        rule.onNodeWithText(MagnetDraftCheck.NOT_A_MAGNET).assertDoesNotExist()
        rule.onNodeWithText("Review Add").assertDoesNotExist()
    }

    /** Opt-in real public HTTPS fetch with a redirect: the fetched .torrent is reviewed by its verified hash. */
    @Test fun realRedirectedLinkIsReviewedByItsVerifiedHash() {
        org.junit.Assume.assumeTrue(System.getenv("CHUNKS_REAL_LINK_FETCH") == "1")
        show("https://archive.org/download/FREE_background_music_dhalius/FREE_background_music_dhalius_archive.torrent")
        reveal("Get torrent from link")
        rule.onNodeWithText("Get torrent from link").performClick()
        rule.waitUntil(30_000) { runCatching { reveal("Review Add") }.isSuccess }
        rule.onNodeWithText("Review Add").performClick()
        reveal("Infohash: e531c7ca2cca262ea75a3496a7bdbb53de90abed")
        rule.onNodeWithText("Infohash: e531c7ca2cca262ea75a3496a7bdbb53de90abed").assertExists()
        reveal("Source: Selected .torrent file")
    }

    /** Android 10+ denies clipboard reads without window focus (ON_RESUME precedes focus): wait for it. */
    @Test fun clipboardIsReadOnlyOnceTheWindowHasFocus() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("copied",
                "magnet:?xt=urn:btih:" + "c".repeat(40) + "&dn=Focused"))
        val focused = mutableStateOf(false)
        val window = object : androidx.compose.ui.platform.WindowInfo {
            override val isWindowFocused: Boolean get() = focused.value
        }
        val magnet = mutableStateOf("")
        val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:9/RPC2", "", ""))
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalWindowInfo provides window) {
                ChunksTheme { Surface {
                    androidx.compose.foundation.layout.Column {
                        VerifiedAddScreen(remote, magnet = magnet.value, destination = "/srv/one-off",
                            onMagnet = { magnet.value = it }, onDestination = {},
                            onBack = {}, onComplete = {},
                            onMutationStarted = { error("Review must never mutate a server") },
                            onMutationSettled = {})
                    }
                } }
            }
        }
        rule.waitForIdle()
        org.junit.Assert.assertEquals("No read while unfocused", "", magnet.value)
        focused.value = true
        rule.waitForIdle()
        rule.waitUntil(5_000) { magnet.value.contains("c".repeat(40)) }
        reveal("Magnet for: Focused")
    }
}
