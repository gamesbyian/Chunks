package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.TransferMove
import io.github.gamesbyian.chunks.model.TransferMove.Direction
import io.github.gamesbyian.chunks.model.TransferMove.Observation
import io.github.gamesbyian.chunks.model.TransferMove.Stage
import org.junit.Assert.*
import org.junit.Test

/** Pure selection, not an installed source engine or file operation. */
class OrphanMoveRecoveryTest {
    private val hash = "ac".repeat(20)
    private val accountA = "a".repeat(64)
    private val accountB = "b".repeat(64)
    private fun move(
        scope: String = accountA,
        direction: Direction = Direction.PHONE_TO_SERVER,
        stage: Stage = Stage.SOURCE_REMOVAL_UNCERTAIN
    ) = TransferMove.start(direction, hash, "Episode", scope, "seed.example",
        sourceReviewToken = if (direction == Direction.PHONE_TO_SERVER) "native-123" else null,
        sourceDirectory = if (direction == Direction.SERVER_TO_PHONE) "/remote/episode" else null
    ).copy(stage = stage)

    @Test fun absentCardWithSameHashOnTwoServersCanOnlyShowCurrentAccount() {
        val a = move()
        val b = move(scope = accountB)
        val otherDirection = move(direction = Direction.SERVER_TO_PHONE)
        val awaiting = move(stage = Stage.AWAITING_TARGET)
        val eligible = OrphanMoveRecovery.candidates(
            listOf(a, b, otherDirection, awaiting),
            Direction.PHONE_TO_SERVER, accountA, emptySet()
        )
        assertEquals(listOf(a), eligible)
        assertEquals(listOf(b), OrphanMoveRecovery.candidates(
            listOf(a,b), Direction.PHONE_TO_SERVER, accountB, emptySet()
        ))
        assertTrue(OrphanMoveRecovery.candidates(
            listOf(a), Direction.PHONE_TO_SERVER, "not-an-account", emptySet()
        ).isEmpty())
        assertTrue(OrphanMoveRecovery.candidates(
            listOf(a), Direction.PHONE_TO_SERVER, accountA, listOf(hash.uppercase())
        ).isEmpty())
        assertEquals(listOf(otherDirection), OrphanMoveRecovery.candidates(
            listOf(otherDirection), Direction.SERVER_TO_PHONE, accountA, emptySet()
        ))
    }

    @Test fun onlyUnchangedUncertainJournalAndSecondFreshAbsentReadPermitLocalCleanup() {
        val expected = move()
        assertTrue(OrphanMoveRecovery.mayClear(
            expected, expected, accountA, Observation.Absent
        ))
        assertFalse(OrphanMoveRecovery.mayClear(
            expected, expected, accountB, Observation.Absent
        ))
        assertFalse(OrphanMoveRecovery.mayClear(
            expected, null, accountA, Observation.Absent
        ))
        assertFalse(OrphanMoveRecovery.mayClear(
            expected, expected, accountA, Observation.Unknown
        ))
        assertFalse(OrphanMoveRecovery.mayClear(
            expected, expected, accountA, Observation.Present
        ))
        assertFalse(OrphanMoveRecovery.mayClear(
            expected, expected.copy(stage = Stage.TARGET_VERIFIED),
            accountA, Observation.Absent
        ))
        assertFalse(OrphanMoveRecovery.mayClear(
            expected.copy(stage = Stage.AWAITING_TARGET),
            expected.copy(stage = Stage.AWAITING_TARGET),
            accountA, Observation.Absent
        ))
    }

    @Test fun legacyRequestedStageRecoversBeforeReadOnlyCandidateSelection() {
        val requested = move(stage = Stage.SOURCE_REMOVAL_REQUESTED)
        val recovered = requireNotNull(TransferMove.decode(requested.encode()))
        assertEquals(Stage.SOURCE_REMOVAL_UNCERTAIN, recovered.stage)
        assertEquals(listOf(recovered), OrphanMoveRecovery.candidates(
            listOf(recovered), Direction.PHONE_TO_SERVER, accountA, emptyList()
        ))
        assertFalse(OrphanMoveRecovery.mayClear(
            recovered, recovered, accountA, Observation.Unknown
        ))
    }
}
