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
    private fun started(direction: Direction = Direction.LOCAL_TO_SERVER) =
        TransferMove.start(direction, hash, "Big Buck Bunny", scope, "seed.example",
            sourceReviewToken = if (direction == Direction.LOCAL_TO_SERVER) "native-session:17" else null,
            sourceDirectory = if (direction == Direction.SERVER_TO_LOCAL) "/server/downloads" else null)

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
        val requested = started(Direction.SERVER_TO_LOCAL).copy(stage = Stage.SOURCE_REMOVAL_REQUESTED)
        val reloaded = requireNotNull(TransferMove.decode(requested.encode()))
        assertEquals(Stage.SOURCE_REMOVAL_UNCERTAIN, reloaded.stage)
        assertEquals(Direction.SERVER_TO_LOCAL, reloaded.direction)
    }

    @Test fun messagesNeverClaimFilesMoved() {
        val requested = started().copy(stage = Stage.SOURCE_REMOVAL_REQUESTED)
        val done = TransferMove.onRemovalResult(requested, Observation.Absent) as Next.Done
        assertTrue(done.message.contains("Downloaded files remain on this device"))
        val back = TransferMove.onRemovalResult(started(Direction.SERVER_TO_LOCAL)
            .copy(stage = Stage.SOURCE_REMOVAL_REQUESTED), Observation.Absent) as Next.Done
        assertTrue(back.message.contains("Downloaded files remain on the server"))
    }

    @Test fun localSourceGenerationRoundTripsAndNeverAuthorizesReplacement() {
        val original = TransferMove.start(
            Direction.LOCAL_TO_SERVER, hash, "Big Buck Bunny", scope, "seed.example",
            sourceReviewToken = "native-session:17"
        )
        assertTrue(original.encode().startsWith("v3\n"))
        assertEquals(original, TransferMove.decode(original.encode()))
        assertTrue(TransferMove.sourceIdentityMatches(original, "Big Buck Bunny", "native-session:17"))
        assertFalse(TransferMove.sourceIdentityMatches(original, "Big Buck Bunny", "native-session:18"))
        assertFalse(TransferMove.sourceIdentityMatches(original, "Changed title", "native-session:17"))
        assertFalse(TransferMove.sourceIdentityMatches(original, "Big Buck Bunny", null))
    }

    @Test fun legacyLocalMoveCanBeRecoveredButCannotEraseUnidentifiedSource() {
        val old = TransferMove.start(Direction.LOCAL_TO_SERVER, hash,
            "Big Buck Bunny", scope, "seed.example")
        assertTrue(old.encode().startsWith("v1\n"))
        val recovered = requireNotNull(TransferMove.decode(old.encode()))
        assertNull(recovered.sourceReviewToken)
        assertFalse(TransferMove.sourceIdentityMatches(recovered, recovered.name, "fresh-native:1"))
        val legacyReviewed = recovered.copy(stage = Stage.TARGET_VERIFIED)
        assertTrue(TransferMove.beginRemoval(legacyReviewed, Observation.Present) is Next.Keep)
        // A legacy server-side Move also lacked its reviewed original directory
        // and must be restarted, not silently grant consent to a replacement.
        val remoteSource = TransferMove.start(Direction.SERVER_TO_LOCAL, hash,
            "Big Buck Bunny", scope, "seed.example")
        assertFalse(TransferMove.sourceIdentityMatches(remoteSource, "Big Buck Bunny", null,
            "/server/downloads"))
        val reviewed = TransferMove.start(Direction.SERVER_TO_LOCAL, hash, "Big Buck Bunny",
            scope, "seed.example", sourceDirectory = "/server/downloads")
        assertTrue(reviewed.encode().startsWith("v3\n"))
        assertEquals(reviewed, TransferMove.decode(reviewed.encode()))
        assertTrue(TransferMove.sourceIdentityMatches(reviewed, "Big Buck Bunny", null,
            "/server/downloads"))
        assertFalse(TransferMove.sourceIdentityMatches(reviewed, "Big Buck Bunny", null,
            "/server/other"))
        assertFalse(TransferMove.sourceIdentityMatches(reviewed, "Renamed", null,
            "/server/downloads"))
    }

    @Test fun remoteMoveNeverTreatsDifferentNamesWithTheSameLongPrefixAsOneJob() {
        val prefix = "a".repeat(512)
        val expected = prefix + "original suffix"
        val changed = prefix + "replacement suffix"
        val record = TransferMove.start(Direction.SERVER_TO_LOCAL, hash, expected,
            scope, "seed.example", sourceDirectory = "/server/downloads")
        assertEquals(expected, record.name)
        assertFalse(TransferMove.sourceIdentityMatches(record, changed, null,
            "/server/downloads"))
        assertTrue(TransferMove.sourceIdentityMatches(record, expected, null,
            "/server/downloads"))

        // The adapter caps returned names at 2048. A name of exactly that
        // length may hide a suffix, so cannot prove original source identity.
        val unobservable = "x".repeat(2048)
        val capped = TransferMove.start(Direction.SERVER_TO_LOCAL, hash, unobservable,
            scope, "seed.example", sourceDirectory = "/server/downloads")
        assertFalse(TransferMove.sourceIdentityMatches(capped, unobservable, null,
            "/server/downloads"))

        val cappedFolder = "/" + "d".repeat(4095)
        val directoryBound = TransferMove.start(Direction.SERVER_TO_LOCAL, hash, expected,
            scope, "seed.example", sourceDirectory = cappedFolder)
        assertFalse(TransferMove.sourceIdentityMatches(directoryBound, expected, null,
            cappedFolder))
    }

    @Test fun noncanonicalEscapesCannotChangeReviewedMoveNameOrDirectory() {
        val original = TransferMove.start(Direction.SERVER_TO_LOCAL, hash, "Episode",
            scope, "server", sourceDirectory = "/srv/episode")
        val encoded = original.encode()
        assertEquals(original, TransferMove.decode(encoded))
        // Both corrupted journal strings used to decode into the ORIGINAL
        // name/Place by silently removing a backslash. Neither can be used
        // to authorize an exact-source job removal now.
        assertNull(TransferMove.decode(encoded.replace("Episode", "Ep\\isode")))
        assertNull(TransferMove.decode(encoded.replace("/srv/episode", "/srv/epi\\sode")))
        assertNull("Trailing escape must not be appended as a literal path suffix",
            TransferMove.decode(encoded + "\\"))
        // Real literal backslashes and escaped newlines remain supported.
        val literal = original.copy(name = "Ep\\isode\nTwo",
            sourceDirectory = "/srv/with\\backslash")
        assertEquals(literal, TransferMove.decode(literal.encode()))
    }

    @Test fun codecRoundTripsAwkwardNamesAndRejectsMalformedRecords() {
        val move = TransferMove.start(Direction.SERVER_TO_LOCAL, hash, "Line\nbreak \\ slash", scope, "host")
        assertEquals(move, TransferMove.decode(move.encode()))
        assertNull(TransferMove.decode("v2\nLOCAL_TO_SERVER"))
        assertNull(TransferMove.decode(move.encode().replace(scope, "short")))
        assertThrows(IllegalArgumentException::class.java) {
            TransferMove.start(Direction.LOCAL_TO_SERVER, "a".repeat(64), "v2", scope, "h")
        }
    }
}
