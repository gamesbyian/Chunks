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
