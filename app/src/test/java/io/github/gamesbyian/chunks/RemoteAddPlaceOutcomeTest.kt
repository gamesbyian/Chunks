package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RemoteAddAndStart
import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
import org.junit.Assert.*
import org.junit.Test

class RemoteAddPlaceOutcomeTest {
    private val hash = "ab".repeat(20)
    private val place = "/downloads"

    @Test fun nativeStartOnLoadCannotSaveUnverifiedCustomPlace() {
        assertFalse(verifiedCustomPlaceOutcome(
            RemoteAddAndStart.StartNotVerified(hash, null), false))
        assertFalse(verifiedCustomPlaceOutcome(
            RemoteAddAndStart.StartNotVerified(hash, LifecycleOutcome.Pending(true, true)), false))
        assertFalse(verifiedCustomPlaceOutcome(
            RemoteAddAndStart.AddedStopped(RemoteAddOutcome.Uncertain(hash)), false))
        assertFalse(verifiedCustomPlaceOutcome(
            RemoteAddAndStart.AddedStopped(RemoteAddOutcome.PendingVerification(hash)), false))
        assertFalse(verifiedCustomPlaceOutcome(
            RemoteAddAndStart.AddedStopped(RemoteAddOutcome.AlreadyPresent(hash)), false))
    }

    @Test fun nativeVerifiedAndStoppedFirstVerifiedCanSavePlace() {
        assertTrue(verifiedCustomPlaceOutcome(RemoteAddAndStart.Started(hash), false))
        assertTrue(verifiedCustomPlaceOutcome(
            RemoteAddAndStart.AddedStopped(RemoteAddOutcome.VerifiedStopped(hash, place)), false))
        assertTrue(verifiedCustomPlaceOutcome(
            RemoteAddAndStart.StartNotVerified(hash, LifecycleOutcome.Pending(true, true)), true))
    }
    @Test fun compactFolderSummaryStatesWhatIsKnownWithoutBlockingBlankDefault() {
        assertEquals("Save to: /custom (this Add only)",
            remoteAddSaveToSummary("/custom", "/stored", false, "/observed"))
        assertEquals("Save to: /stored (saved default)",
            remoteAddSaveToSummary("", "/stored", true, "/observed"))
        assertEquals("Save to: Server default (checking; Add is available)",
            remoteAddSaveToSummary("", null, false, null))
        assertEquals("Save to: /observed (rTorrent default)",
            remoteAddSaveToSummary("", null, true, "/observed"))
        assertEquals("Save to: Server default (confirm after adding)",
            remoteAddSaveToSummary("", null, true, null))
    }


}
