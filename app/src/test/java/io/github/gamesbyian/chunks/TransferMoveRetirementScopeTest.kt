package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.TransferMove
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The final destructive Move click must remain bound to its original server account. */
class TransferMoveRetirementScopeTest {
    private val hash = "ab".repeat(20)
    private val accountA = "https://example.test/rutorrent/\\u0000alice"
    private val accountB = "https://example.test/rutorrent/\\u0000bob"

    @Test fun sameHashAndNameOnAnotherServerAccountCannotRetireRemoteSource() {
        val record = TransferMove.start(
            TransferMove.Direction.SERVER_TO_PHONE, hash, "Episode",
            TransferMoveStore.scopeDigest(accountA), "example.test",
            sourceDirectory = "/data/episode"
        )
        assertTrue(moveRetirementScopeConfirmed(
            record, accountA, "Episode", null, "/data/episode"
        ))
        assertFalse(moveRetirementScopeConfirmed(
            record, accountB, "Episode", null, "/data/episode"
        ))
        assertFalse(moveRetirementScopeConfirmed(
            record, null, "Episode", null, "/data/episode"
        ))
        assertFalse(moveRetirementScopeConfirmed(
            record, accountA, "Episode", null, "/data/another"
        ))
    }

    @Test fun phoneOriginMoveAlsoRequiresOriginalAccountAndNativeGeneration() {
        val record = TransferMove.start(
            TransferMove.Direction.PHONE_TO_SERVER, hash, "Season",
            TransferMoveStore.scopeDigest(accountA), "example.test",
            sourceReviewToken = "phone-generation-42"
        )
        assertTrue(moveRetirementScopeConfirmed(
            record, accountA, "Season", "phone-generation-42", null
        ))
        assertFalse(moveRetirementScopeConfirmed(
            record, accountB, "Season", "phone-generation-42", null
        ))
        assertFalse(moveRetirementScopeConfirmed(
            record, accountA, "Season", "phone-generation-43", null
        ))
        assertFalse(moveRetirementScopeConfirmed(
            record, accountA, "Different transfer", "phone-generation-42", null
        ))
    }
}
