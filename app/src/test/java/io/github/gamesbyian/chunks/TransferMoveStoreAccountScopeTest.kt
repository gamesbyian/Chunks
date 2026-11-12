package io.github.gamesbyian.chunks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.TransferMove
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Same torrent on two accounts must never share or erase an uncertain Move. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferMoveStoreAccountScopeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences(
        "chunks_transfer_moves_v1", Context.MODE_PRIVATE)
    private val hash = "ab".repeat(20)
    private val scopeA = TransferMoveStore.scopeDigest("https://seed.example/RPC2\u0000alice")
    private val scopeB = TransferMoveStore.scopeDigest("https://seed.example/RPC2\u0000bob")
    private val direction = TransferMove.Direction.LOCAL_TO_SERVER

    private fun record(scope: String) = TransferMove.start(
        direction, hash, "Same torrent", scope, "seed.example",
        sourceReviewToken = "local-generation-1"
    ).copy(stage = TransferMove.Stage.SOURCE_REMOVAL_UNCERTAIN)

    @Before fun before() { prefs.edit().clear().commit() }
    @After fun after() { prefs.edit().clear().commit() }

    @Test fun sameHashOnTwoAccountsKeepsIndependentMoveJournals() {
        val store = TransferMoveStore(context)
        val a = record(scopeA)
        val b = record(scopeB)
        assertTrue(store.save(a))
        assertTrue(store.save(b))
        assertEquals(a, store.get(direction, hash, scopeA))
        assertEquals(b, store.get(direction, hash, scopeB))
        assertNull(store.get(direction, hash))
        assertEquals(setOf(a, b), store.all(direction).toSet())
        assertEquals(2, prefs.all.size)
        // The obsolete hash-only deletion API must fail closed on ambiguity.
        assertFalse(store.remove(direction, hash))
        assertEquals(2, prefs.all.size)
        assertTrue(store.remove(a))
        assertNull(store.get(direction, hash, scopeA))
        assertEquals(b, store.get(direction, hash, scopeB))
        assertEquals(listOf(b), store.all(direction))
        assertTrue(store.remove(b))
        assertTrue(store.all(direction).isEmpty())
    }

    @Test fun legacyJournalRemainsVisibleOnlyOnItsOriginalAccount() {
        val original = record(scopeA)
        val legacyKey = direction.name + "_" + hash
        assertTrue(prefs.edit().putString(legacyKey, original.encode()).commit())
        val store = TransferMoveStore(context)
        assertEquals(original, store.get(direction, hash, scopeA))
        assertNull(store.get(direction, hash, scopeB))
        assertEquals(original, store.get(direction, hash)) // one disconnected candidate
        val second = record(scopeB)
        assertTrue(store.save(second))
        assertTrue(prefs.contains(legacyKey)) // do not erase the other account's old Move
        assertEquals(setOf(original, second), store.all(direction).toSet())
        assertTrue(store.remove(second))
        assertEquals(original, store.get(direction, hash, scopeA))
        assertTrue(prefs.contains(legacyKey))
        // The original account migrates atomically, without losing the uncertain stage.
        assertTrue(store.save(original))
        assertFalse(prefs.contains(legacyKey))
        assertEquals(original, TransferMoveStore(context).get(direction, hash, scopeA))
        assertEquals(TransferMove.Stage.SOURCE_REMOVAL_UNCERTAIN,
            TransferMoveStore(context).get(direction, hash, scopeA)?.stage)
    }

    @Test fun invalidOrMismatchedStoredIdentityCannotImpersonateAccount() {
        val original = record(scopeA)
        val wrongKey = direction.name + "_" + scopeB + "_" + hash
        assertTrue(prefs.edit().putString(wrongKey, original.encode()).commit())
        val store = TransferMoveStore(context)
        assertNull(store.get(direction, hash, scopeB))
        assertNull(store.get(direction, hash, scopeA))
        assertTrue(store.all(direction).isEmpty())
        assertNull(store.get(direction, hash, "invalid"))
        assertFalse(store.remove(direction, hash))
        assertFalse(store.save(record(scopeB)))
        assertTrue(prefs.contains(wrongKey)) // no destructive cleanup of unknown records
    }

    @Test fun conflictingScopedRecordNeverFallsBackToLegacySource() {
        val old = record(scopeA)
        val staleKey = direction.name + "_" + hash
        val scopedKey = direction.name + "_" + scopeA + "_" + hash
        val newer = old.copy(stage = TransferMove.Stage.TARGET_VERIFIED)
        assertTrue(prefs.edit().putString(staleKey, old.encode())
            .putString(scopedKey, newer.encode()).commit())
        val store = TransferMoveStore(context)
        assertEquals(newer, store.get(direction, hash, scopeA))
        assertFalse(store.remove(old))
        assertEquals(newer, store.get(direction, hash, scopeA))
        assertTrue(prefs.contains(staleKey))
        // A corrupt scoped value cannot resurrect the legacy job for a write.
        assertTrue(prefs.edit().putString(scopedKey, "corrupt").commit())
        assertNull(store.get(direction, hash, scopeA))
        assertNull(store.get(direction, hash))
        assertTrue(store.all(direction).isEmpty())
        assertFalse(store.remove(old))
        assertTrue(prefs.contains(scopedKey))
        assertTrue(prefs.contains(staleKey))
    }

    @Test fun sameAccountCannotReplaceUnresolvedMoveOrResendRemovalBlindly() {
        val store = TransferMoveStore(context)
        val uncertain = record(scopeA)
        assertTrue(store.save(uncertain))
        assertFalse(store.save(uncertain.copy(sourceReviewToken = "local-generation-2")))
        assertFalse(store.save(uncertain.copy(name = "Another transfer")))
        assertFalse(store.save(uncertain.copy(stage = TransferMove.Stage.AWAITING_TARGET)))
        assertFalse(store.save(uncertain.copy(stage = TransferMove.Stage.SOURCE_REMOVAL_REQUESTED)))
        assertEquals(uncertain, TransferMoveStore(context).get(direction, hash, scopeA))
        // A fresh observed source followed by an explicit second approval
        // may advance UNCERTAIN -> TARGET_VERIFIED -> REQUESTED.
        val rechecked = uncertain.copy(stage = TransferMove.Stage.TARGET_VERIFIED)
        assertTrue(store.save(rechecked))
        assertTrue(store.save(rechecked.copy(stage = TransferMove.Stage.SOURCE_REMOVAL_REQUESTED)))
        assertEquals(TransferMove.Stage.SOURCE_REMOVAL_UNCERTAIN,
            TransferMoveStore(context).get(direction, hash, scopeA)?.stage)
    }

    @Test fun staleRemovalCompletionCannotClearNewerUncertainMoveStage() {
        val store = TransferMoveStore(context)
        val latest = record(scopeA)
        assertTrue(store.save(latest))
        val stale = latest.copy(stage = TransferMove.Stage.TARGET_VERIFIED)
        assertFalse(store.remove(stale))
        assertEquals(latest, store.get(direction, hash, scopeA))
        assertTrue(store.remove(latest))
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun successfulRemovalCanClearRecoveredRequestedStageOnlyForSameJob() {
        val store = TransferMoveStore(context)
        val requested = record(scopeA).copy(stage = TransferMove.Stage.SOURCE_REMOVAL_REQUESTED)
        assertTrue(store.save(requested))
        assertEquals(TransferMove.Stage.SOURCE_REMOVAL_UNCERTAIN,
            store.get(direction, hash, scopeA)?.stage)
        assertTrue(store.remove(requested))
        assertNull(store.get(direction, hash, scopeA))
    }

    @Test fun legacyAndScopedDuplicateCountAsOneJournal() {
        val original = record(scopeA)
        val legacyKey = direction.name + "_" + hash
        assertTrue(prefs.edit().putString(legacyKey, original.encode()).commit())
        assertTrue(TransferMoveStore(context).save(original))
        // A committed migration removes the old value; orphaned duplicate copies
        // from a prior manual restore must still not appear twice in the UI.
        assertTrue(prefs.edit().putString(legacyKey, original.encode()).commit())
        assertEquals(listOf(original), TransferMoveStore(context).all(direction))
        assertTrue(TransferMoveStore(context).remove(original))
        assertTrue(prefs.all.isEmpty())
    }
}
