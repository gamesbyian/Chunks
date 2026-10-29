package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.model.TransferMove.Direction
import io.github.gamesbyian.chunks.model.TransferMove.Next
import io.github.gamesbyian.chunks.model.TransferMove.Observation
import io.github.gamesbyian.chunks.model.TransferMove.Stage
import org.junit.Assert.*
import org.junit.Test

class TransferMoveTest {
    private val hash = "B63EAA9720EE797667EF58EA35EF7D81AB5D1C16"
    private val scope = "a".repeat(64)
    private fun started(direction: Direction = Direction.PHONE_TO_SERVER) =
        TransferMove.start(direction, hash, "Big Buck Bunny", scope, "seed.example")

    private fun saved(next: Next) = (next as Next.Save).record

    @Test fun startsAwaitingTargetWithNormalizedIdentityAndNoRemovalPossible() {
        val move = started()
        assertEquals(Stage.AWAITING_TARGET, move.stage)
        assertEquals(hash.lowercase(), move.infoHash)
        assertTrue(TransferMove.beginRemoval(move, Observation.Present) is Next.Keep)
    }

    @Test fun targetMustBeObservedBeforeRemovalAndAgainImmediatelyBefore() {
        val move = started()
        assertEquals(Stage.AWAITING_TARGET, saved(TransferMove.onTargetObserved(move, Observation.Absent)).stage)
        assertTrue(TransferMove.onTargetObserved(move, Observation.Unknown) is Next.Keep)
        val verified = saved(TransferMove.onTargetObserved(move, Observation.Present))
        assertEquals(Stage.TARGET_VERIFIED, verified.stage)
        // Fresh preflight: target vanished in the meantime, so the source is kept.
        assertEquals(Stage.AWAITING_TARGET,
            saved(TransferMove.beginRemoval(verified, Observation.Unknown)).stage)
        assertEquals(Stage.SOURCE_REMOVAL_REQUESTED,
            saved(TransferMove.beginRemoval(verified, Observation.Present)).stage)
    }

    @Test fun removalCompletesOnlyOnObservedAbsenceAndNeverRetriesItself() {
        val requested = started().copy(stage = Stage.SOURCE_REMOVAL_REQUESTED)
        assertTrue(TransferMove.onRemovalResult(requested, Observation.Absent) is Next.Done)
        val unknown = saved(TransferMove.onRemovalResult(requested, Observation.Unknown))
        assertEquals(Stage.SOURCE_REMOVAL_UNCERTAIN, unknown.stage)
        assertEquals(Stage.SOURCE_REMOVAL_UNCERTAIN,
            saved(TransferMove.onRemovalResult(requested, Observation.Present)).stage)
        // Uncertain state cannot start a new removal or re-check the target.
        assertTrue(TransferMove.beginRemoval(unknown, Observation.Present) is Next.Keep)
        assertTrue(TransferMove.onTargetObserved(unknown, Observation.Present) is Next.Keep)
        // Only observation resolves it.
        assertTrue(TransferMove.onSourceObserved(unknown, Observation.Absent) is Next.Done)
        assertTrue(TransferMove.onSourceObserved(unknown, Observation.Unknown) is Next.Keep)
        assertEquals(Stage.TARGET_VERIFIED,
            saved(TransferMove.onSourceObserved(unknown, Observation.Present)).stage)
    }

    @Test fun crashDuringRemovalRecoversAsUncertain() {
        val requested = started(Direction.SERVER_TO_PHONE).copy(stage = Stage.SOURCE_REMOVAL_REQUESTED)
        val reloaded = requireNotNull(TransferMove.decode(requested.encode()))
        assertEquals(Stage.SOURCE_REMOVAL_UNCERTAIN, reloaded.stage)
        assertEquals(Direction.SERVER_TO_PHONE, reloaded.direction)
    }

    @Test fun messagesNeverClaimFilesMoved() {
        val requested = started().copy(stage = Stage.SOURCE_REMOVAL_REQUESTED)
        val done = TransferMove.onRemovalResult(requested, Observation.Absent) as Next.Done
        assertTrue(done.message.contains("Downloaded files remain on this phone"))
        val back = TransferMove.onRemovalResult(started(Direction.SERVER_TO_PHONE)
            .copy(stage = Stage.SOURCE_REMOVAL_REQUESTED), Observation.Absent) as Next.Done
        assertTrue(back.message.contains("Downloaded files remain on the server"))
    }

    @Test fun codecRoundTripsAwkwardNamesAndRejectsMalformedRecords() {
        val move = TransferMove.start(Direction.SERVER_TO_PHONE, hash, "Line\nbreak \\ slash", scope, "host")
        assertEquals(move, TransferMove.decode(move.encode()))
        assertNull(TransferMove.decode("v2\nPHONE_TO_SERVER"))
        assertNull(TransferMove.decode(move.encode().replace(scope, "short")))
        assertThrows(IllegalArgumentException::class.java) {
            TransferMove.start(Direction.PHONE_TO_SERVER, "a".repeat(64), "v2", scope, "h")
        }
    }
}
