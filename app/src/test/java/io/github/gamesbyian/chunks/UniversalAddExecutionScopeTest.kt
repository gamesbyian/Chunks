package io.github.gamesbyian.chunks

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Both production Add screens choose the same ViewModel-retained mutation scope.
 * This covers lifetime selection only; it never mocks a successful native/RPC Add.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UniversalAddExecutionScopeTest {
    @Test fun retainedOwnerOutlivesDiscardedAddCompositionForBothEngines() {
        val store = ViewModelStore()
        val screenJob = Job()
        try {
            val owner = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[
                LocalAddAttemptOwner::class.java]
            val transient = CoroutineScope(Dispatchers.Unconfined + screenJob)
            val local = UniversalAddExecutionScope.select(owner.addAttemptScope, transient)
            val remote = UniversalAddExecutionScope.select(owner.addAttemptScope, transient)
            assertSame(local, remote)
            assertSame(owner.addAttemptScope, local)
            val retainedJob = requireNotNull(local.coroutineContext[Job])
            screenJob.cancel()
            assertTrue(retainedJob.isActive)

            var ranAfterNavigation = false
            local.launch(Dispatchers.Unconfined) { ranAfterNavigation = true }
            assertTrue(ranAfterNavigation)

            // A new Activity with the same ViewModelStore sees that same scope,
            // rather than implicitly restarting or duplicating its Add attempt.
            val recovered = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[
                LocalAddAttemptOwner::class.java]
            assertSame(local, recovered.addAttemptScope)
            assertSame(owner.session, recovered.session)
            assertSame(owner.remoteSessions, recovered.remoteSessions)
            assertSame(owner.attempts, recovered.attempts)
        } finally {
            store.clear()
        }
    }

    @Test fun remotePreparedTorrentBindsOnlyTheExplicitCurrentReviewedDocument() {
        val owner = LocalAddAttemptOwner()
        fun torrent(name: String): ByteArray =
            ("d8:announce32:https://tracker.example/announce4:infod6:lengthi5e4:name" + name.length + ":" + name +
                "12:piece lengthi16384e6:pieces20:01234567890123456789ee").toByteArray()
        val bytes = torrent("First")
        val otherBytes = torrent("Other")
        val uri = android.net.Uri.parse("content://fixture.documents/first.torrent")
        val review = PendingLocalAdd(
            io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(bytes), bytes.copyOf(), uri)
        owner.reviewedTorrent.value = review
        assertTrue(owner.stageRemoteTorrentHandoff(bytes, review))
        org.junit.Assert.assertEquals(uri, owner.remoteHandoff?.originalDocument)
        org.junit.Assert.assertArrayEquals(bytes, owner.remoteHandoff?.bytes)
        // An export of identical bytes is a different source, not permission to
        // archive/delete the earlier Android document.
        assertTrue(owner.stageRemoteTorrentHandoff(bytes, null))
        org.junit.Assert.assertNull(owner.reviewedTorrent.value)
        org.junit.Assert.assertNull(owner.remoteHandoff?.originalDocument)
        org.junit.Assert.assertArrayEquals(bytes, owner.remoteHandoff?.bytes)
        val export = owner.remoteHandoff?.bytes
        assertFalse(owner.stageRemoteTorrentHandoff(bytes, review))
        assertSame(export, owner.remoteHandoff?.bytes)
        org.junit.Assert.assertNull(owner.remoteHandoff?.originalDocument)
        // A current review with changed bytes is refused without clearing text.
        owner.reviewedTorrent.value = review
        owner.sourceDraft.value = "new draft"
        assertFalse(owner.stageRemoteTorrentHandoff(otherBytes, review))
        org.junit.Assert.assertEquals("new draft", owner.sourceDraft.value)
        assertSame(review, owner.reviewedTorrent.value)
        assertTrue(owner.stageRemoteTorrentHandoff(otherBytes, null))
        org.junit.Assert.assertArrayEquals(otherBytes, owner.remoteHandoff?.bytes)
        org.junit.Assert.assertNull(owner.reviewedTorrent.value)
        org.junit.Assert.assertNull(owner.remoteHandoff?.originalDocument)
        otherBytes[0] = 0
        assertTrue(requireNotNull(owner.remoteHandoff?.bytes)[0] != 0.toByte())
        org.junit.Assert.assertNull(owner.session.attemptedTicket)
    }

    @Test fun oldRemoteAcknowledgmentAndClaimCannotConsumeANewerIdenticalDocumentReview() {
        val owner = LocalAddAttemptOwner()
        val bytes = ("d4:infod6:lengthi5e4:name8:test.txt" +
            "12:piece lengthi16384e6:pieces20:01234567890123456789ee").toByteArray()
        val preview = io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(bytes)
        val uri = android.net.Uri.parse("content://fixture.documents/review.torrent")
        val older = PendingLocalAdd(preview, bytes.copyOf(), uri)
        val newer = PendingLocalAdd(preview, bytes.copyOf(), uri)
        owner.adoptSharedDocument(older, forAuthenticatedRemote = true)
        val staleHandoff = owner.remoteHandoff
        val remote = owner.remoteSessions.forAccount("reviewed-account")
        remote.selectedBytes = bytes.copyOf()
        remote.useFile = true
        remote.associatedLocalReview = older

        owner.adoptSharedDocument(newer, forAuthenticatedRemote = true)
        val currentHandoff = owner.remoteHandoff
        assertFalse(owner.claimRemoteHandoff(staleHandoff))
        owner.retireCompletedRemoteReview(remote.selectedLocalReview())
        assertSame(newer, owner.reviewedTorrent.value)
        assertSame(currentHandoff, owner.remoteHandoff)
        assertTrue(owner.claimRemoteHandoff(currentHandoff))
        assertFalse(owner.claimRemoteHandoff(currentHandoff))

        // A magnet attempt cannot retire even the older file selection.
        remote.useFile = false
        org.junit.Assert.assertNull(remote.selectedLocalReview())
        owner.adoptSharedDocument(older, forAuthenticatedRemote = true)
        owner.retireCompletedRemoteReview(remote.selectedLocalReview())
        assertSame(older, owner.reviewedTorrent.value)
        remote.useFile = true
        owner.retireCompletedRemoteReview(remote.selectedLocalReview())
        org.junit.Assert.assertNull(owner.reviewedTorrent.value)
        org.junit.Assert.assertNull(owner.remoteHandoff)
    }

    @Test fun standAlonePreviewUsesScreenScopeAndDoesNotCreateAnotherExecutor() {
        val job = Job()
        try {
            val transient = CoroutineScope(Dispatchers.Unconfined + job)
            assertSame(transient, UniversalAddExecutionScope.select(null, transient))
            job.cancel()
            assertFalse(UniversalAddExecutionScope.select(null, transient)
                .coroutineContext[Job]!!.isActive)
        } finally {
            job.cancel()
        }
    }

    @Test fun clearingAppOwnerCancelsBothEngineSubmissionLifetimes() {
        val store = ViewModelStore()
        val owner = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[
            LocalAddAttemptOwner::class.java]
        val local = owner.addAttemptScope
        val remote = owner.addAttemptScope
        assertSame(local, remote)
        assertTrue(requireNotNull(local.coroutineContext[Job]).isActive)
        store.clear()
        assertTrue(requireNotNull(remote.coroutineContext[Job]).isCancelled)
    }
}
