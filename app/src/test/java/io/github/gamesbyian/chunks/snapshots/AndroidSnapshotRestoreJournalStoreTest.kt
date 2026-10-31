package io.github.gamesbyian.chunks.snapshots

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real Android SharedPreferences type-corruption checks. A previous
 * unacknowledged snapshot Add may be hidden under any occupied journal key;
 * wrong-type storage MUST NEVER create another writable batch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSnapshotRestoreJournalStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val archive = "a".repeat(64)
    private val target = "b".repeat(64)
    private val place = "c".repeat(64)
    private val infohash = "1".repeat(40)

    private val prefs get() = context.getSharedPreferences(
        "chunks_restore_journal_v1", Context.MODE_PRIVATE)

    @Before fun resetOnlyTestOwnedPreferences() {
        assertTrue(prefs.edit().clear().commit())
    }

    private fun open(store: AndroidSnapshotRestoreJournalStore) =
        SnapshotRestoreJournalV1.open(store, archive, target, listOf(infohash))

    private fun occupiedKey(): String = prefs.all.keys.single().also {
        assertTrue(it.startsWith("chunks.restore.v1."))
    }

    @Test fun occupiedWrongTypeRefusesRecoveryWithoutReplacingOrRemovingAnyValue() {
        val store = AndroidSnapshotRestoreJournalStore(context)
        open(store)
        val key = occupiedKey()
        assertTrue(prefs.edit().putInt(key, 371).commit())

        try {
            open(AndroidSnapshotRestoreJournalStore(context))
            fail("An unreadable occupied import journal must never become a new REVIEW batch")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("no Add is authorized"))
        }

        assertFalse(store.compareAndCommit(key, null, "replacement"))
        assertFalse(store.compareAndCommit(key, "old value", "replacement"))
        assertTrue(prefs.contains(key))
        assertEquals(371, prefs.getInt(key, -1))
        assertEquals(1, prefs.all.size)
    }

    @Test fun wrongTypeAfterReviewPreventsDurableReadyAndInFlightTransitions() {
        val store = AndroidSnapshotRestoreJournalStore(context)
        val reviewed = open(store)
        val key = occupiedKey()
        assertTrue(prefs.edit().putLong(key, 999L).commit())

        try {
            reviewed.prepare(infohash, place)
            fail("An occupied foreign preference type cannot be overwritten by prepare")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("not committed"))
        }
        assertEquals(SnapshotRestoreJournalV1.Phase.REVIEW, reviewed.entries().single().phase)
        assertTrue(prefs.contains(key))
        assertEquals(999L, prefs.getLong(key, -1L))
    }

    @Test fun ordinaryRealPreferencesStillPersistInFlightAcrossNewStoreInstances() {
        val store = AndroidSnapshotRestoreJournalStore(context)
        val first = open(store)
        first.prepare(infohash, place)
        first.beforeAdd(infohash, place)
        val resumed = open(AndroidSnapshotRestoreJournalStore(context))
        assertEquals(SnapshotRestoreJournalV1.Phase.IN_FLIGHT,
            resumed.entries().single().phase)
        assertEquals(1, prefs.all.size)
    }
}
