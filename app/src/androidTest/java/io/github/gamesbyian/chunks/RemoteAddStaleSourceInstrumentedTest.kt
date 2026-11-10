package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real installed remote Add Compose field and asynchronous source handoff.
 * A controlled read-only HTTPS fixture supplies verified v1 metainfo without
 * contacting the network. No RPC, server mutation, transfer Add or peer proof.
 */
@RunWith(AndroidJUnit4::class)
class RemoteAddStaleSourceInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val remote by lazy {
        RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
    }
    private val link = "https://downloads.example.org/serve?token=privateFixture"
    private val typed = "magnet:?xt=urn:btih:" + "b".repeat(40) + "&dn=explicit"
    private val bytes = ("d4:infod6:lengthi5e4:name8:test.txt" +
        "12:piece lengthi16384e6:pieces20:01234567890123456789ee").toByteArray()
    private val reviewed by lazy {
        TorrentLinkResult.Fetched(bytes, TorrentMetainfoReader.preview(bytes), "downloads.example.org")
    }

    @Test fun newlyTypedSourceWinsAgainstOldHttpsFetchCompletion() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<TorrentLinkResult>()
        val session = RemoteAddSession(null)
        var source by mutableStateOf(link)
        var writes = 0
        rule.setContent {
            MaterialTheme {
                VerifiedAddScreen(
                    remote = remote,
                    magnet = source,
                    destination = "/srv/disposable",
                    session = session,
                    onMagnet = { source = it },
                    onDestination = {},
                    onBack = {}, onComplete = {},
                    onMutationStarted = { writes++; false },
                    onMutationSettled = {},
                    fetchTorrentLink = {
                        started.complete(Unit)
                        release.await()
                    }
                )
            }
        }
        val list = rule.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("Get torrent from link"))
        rule.onNodeWithText("Get torrent from link").performClick()
        rule.waitUntil(10_000) { started.isCompleted }
        list.performScrollToNode(hasText("Magnet or torrent link"))
        rule.onNodeWithText("Magnet or torrent link").performTextReplacement(typed)
        rule.runOnIdle {
            assertEquals("Explicit source must be the current draft", typed, source)
            release.complete(reviewed)
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("A stale fetched .torrent must not clear a later magnet", typed, source)
            assertNull("A stale HTTP response cannot become selected metainfo", session.selectedBytes)
            assertFalse("A stale HTTP response cannot change the chosen source type", session.useFile)
            assertEquals("Source resolution never performs remote Add", 0, writes)
        }
    }

    @Test fun unchangedSourceMayBeReviewedWithoutAnyRemoteWrite() {
        val session = RemoteAddSession(null)
        var source by mutableStateOf(link)
        var writes = 0
        rule.setContent {
            MaterialTheme {
                VerifiedAddScreen(
                    remote = remote,
                    magnet = source,
                    destination = "/srv/disposable",
                    session = session,
                    onMagnet = { source = it },
                    onDestination = {},
                    onBack = {}, onComplete = {},
                    onMutationStarted = { writes++; false },
                    onMutationSettled = {},
                    fetchTorrentLink = { reviewed }
                )
            }
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Get torrent from link"))
        rule.onNodeWithText("Get torrent from link").performClick()
        rule.waitUntil(10_000) { session.useFile }
        rule.runOnIdle {
            assertTrue(session.useFile)
            assertTrue("Exact metainfo bytes become the reviewed draft",
                session.selectedBytes?.contentEquals(bytes) == true)
            assertEquals("Successful read alone must never Add/Start", 0, writes)
        }
    }
}
