package io.github.gamesbyian.chunks

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Remote Add mutation/readback must survive a configuration change, which
 * preserves the ViewModelStore but destroys the old Activity composition.
 * It must not survive a genuine owner clear as an orphaned write executor.
 * No actual RPC or user account is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteAddViewModelLifetimeTest {
    @Test fun oneShotRemoteAttemptScopeAndExactAccountTicketSurviveOwnerReuse() {
        val store = ViewModelStore()
        try {
            val provider = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
            val first = provider[LocalAddAttemptOwner::class.java]
            val scope = first.addAttemptScope
            val job = requireNotNull(scope.coroutineContext[Job])
            assertTrue(job.isActive)
            val draft = first.remoteSessions.forAccount("fixture-server\u0000account-a")
            draft.destination = "/srv/reviewed"
            draft.alreadyAttempted = true

            // Activity recreation reuses the same ViewModelStore and its
            // existing exact-account one-ticket state, not a new RPC authorizer.
            val restored = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[
                LocalAddAttemptOwner::class.java]
            assertSame(first, restored)
            assertSame(first.attempts, restored.attempts)
            assertSame(scope, restored.addAttemptScope)
            assertSame(draft, restored.remoteSessions.forAccount("fixture-server\u0000account-a"))
            assertTrue(restored.remoteSessions.forAccount("fixture-server\u0000account-a").alreadyAttempted)
            assertTrue(job.isActive)
            assertFalse(restored.remoteSessions.forAccount("fixture-server\u0000account-b").alreadyAttempted)
        } finally {
            store.clear()
        }
    }

    @Test fun unconsumedExternalHandoffsSurviveRecreationButCanBeConsumedOnce() {
        val store = ViewModelStore()
        try {
            val provider = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
            val first = provider[LocalAddAttemptOwner::class.java]
            val sharedUri = android.net.Uri.parse("content://chunks.test.documents/queued-torrent")
            // This state is recorded synchronously by MainActivity.accept() before
            // the Compose Add surface has necessarily observed the external intent.
            first.offerAdd(ExternalAddSource.TorrentDocument(sharedUri))
            val document = requireNotNull(first.incomingDelivery) as ExternalIntakeDelivery.Add
            val restored = provider[LocalAddAttemptOwner::class.java]
            assertSame(document, restored.incomingDelivery)
            assertTrue((document.source as ExternalAddSource.TorrentDocument).uri == sharedUri)
            assertTrue(restored.claimIncoming(document))
            assertTrue("Consuming the one-shot URI must also clear the Activity's source",
                first.incomingDelivery == null)
            assertFalse(restored.claimIncoming(document))

            val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
            first.offerAdd(ExternalAddSource.Magnet(magnet))
            val text = requireNotNull(first.incomingDelivery) as ExternalIntakeDelivery.Add
            assertSame(first, restored)
            assertSame(text, restored.incomingDelivery)
            assertTrue(text.source == ExternalAddSource.Magnet(magnet))
            assertFalse(restored.claimIncoming(document))
            assertTrue(restored.claimIncoming(text))
            assertTrue(first.incomingDelivery == null)
            assertFalse("Retaining an incoming source never authorizes an Add ticket",
                restored.remoteSessions.forAccount("test-server\\u0000account").alreadyAttempted)
        } finally {
            store.clear()
        }
    }

    @Test fun unfinishedCreateShareAndReviewedCreateSourceSurviveOwnerReuseWithoutStartingTransfer() {
        val store = ViewModelStore()
        try {
            val first = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[
                LocalAddAttemptOwner::class.java]
            val shared = io.github.gamesbyian.chunks.create.SharedCreateDocument.Request.File(
                android.net.Uri.parse("content://chunks.test.documents/create-source"))
            // The Create screen owns a read-only scan. Its external request
            // must remain until the scan completes, even if Activity recreates.
            val delivery = ExternalIntakeDelivery.Create(shared)
            first.createReviewRequest.value = delivery
            val restored = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[
                LocalAddAttemptOwner::class.java]
            assertSame(delivery, restored.createReviewRequest.value)
            val reviewed = io.github.gamesbyian.chunks.create.CreateTorrentSource("Shared file", emptyList())
            restored.createSource.value = reviewed
            restored.createReviewRequest.value = null
            assertSame(reviewed, first.createSource.value)
            assertTrue(first.createReviewRequest.value == null)
            assertFalse("A retained Create source never authorizes the Add executor",
                first.remoteSessions.forAccount("fixture-server\\u0000account").alreadyAttempted)
            assertTrue(first.session.attemptedTicket == null)
        } finally {
            store.clear()
        }
    }

    @Test fun clearingTheOwnerCancelsItsRemoteSubmissionScope() {
        val store = ViewModelStore()
        val owner = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[
            LocalAddAttemptOwner::class.java]
        val job = requireNotNull(owner.addAttemptScope.coroutineContext[Job])
        assertTrue(job.isActive)
        store.clear()
        assertTrue("An Activity finishing permanently must not keep an orphaned Add executor",
            job.isCancelled)
    }
}
