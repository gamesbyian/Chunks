package io.github.gamesbyian.chunks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A2611-07: the remote Finish in Order step of an rTorrent Add runs after the
 * verified stopped load and before Start. A cancelled Add must propagate, not
 * become an ordinary "could not be read" note that still allows Start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteFinishInOrderAfterAddCancellationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    // Nothing listens on port 1: an ordinary transport failure, never a real server.
    private val remote = RTorrentRemote(RemoteCredentials("https://127.0.0.1:1/RPC2", "u", "p"))
    private val hash = "a".repeat(40)

    @Test fun ordinaryUnreadableFileListStillReturnsTheNote() {
        val note = runBlocking { enableRemoteFinishInOrderAfterAdd(context, remote, "fio-cancel-test", hash) }
        assertEquals("Finish in Order was not set up: the server's file list could not be read.", note)
    }

    @Test fun cancelledAddPropagatesInsteadOfAllowingStart() {
        var outcome: Result<String>? = null
        runBlocking {
            launch(start = CoroutineStart.UNDISPATCHED) {
                coroutineContext.job.cancel()
                outcome = runCatching { enableRemoteFinishInOrderAfterAdd(context, remote, "fio-cancel-test", hash) }
            }.join()
        }
        val failure = outcome!!.exceptionOrNull()
        assertTrue("cancellation must propagate, got $outcome", failure is CancellationException)
    }
}
