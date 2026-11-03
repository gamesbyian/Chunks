package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RemoteAddAndStart
import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Navigation is offered only for a positively verified hash. */
class VerifiedAddNavigationHashTest {
    private val hash = "a".repeat(40)

    @Test fun verifiesStartedAndStoppedButNeverAmbiguousAdd() {
        assertEquals(hash, verifiedAddNavigationHash(RemoteAddAndStart.Started(hash)))
        assertEquals(hash, verifiedAddNavigationHash(RemoteAddAndStart.AddedStopped(
            RemoteAddOutcome.VerifiedStopped(hash, "/downloads"))))
        assertNull(verifiedAddNavigationHash(RemoteAddAndStart.AddedStopped(
            RemoteAddOutcome.AlreadyPresent(hash))))
        assertNull(verifiedAddNavigationHash(RemoteAddAndStart.AddedStopped(
            RemoteAddOutcome.PendingVerification(hash))))
        assertNull(verifiedAddNavigationHash(RemoteAddAndStart.AddedStopped(
            RemoteAddOutcome.Uncertain(hash))))
    }

    @Test fun unverifiedSecondStartNeverEnablesVerifiedNavigation() {
        assertNull(verifiedAddNavigationHash(RemoteAddAndStart.StartNotVerified(
            hash, LifecycleOutcome.Uncertain(true))))
    }
}
