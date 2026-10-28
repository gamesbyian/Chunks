package io.github.gamesbyian.chunks

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.snapshots.AndroidSnapshotRestoreJournalStore
import io.github.gamesbyian.chunks.snapshots.SnapshotRestoreJournalV1
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapshotRestoreJournalInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun installedAppDurablyKeepsUncertainStateWithoutExposingSourceSecrets() {
        val prefs = context.getSharedPreferences("chunks_restore_journal_v1",
            Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        val archive = "a".repeat(64)
        val target = "b".repeat(64)
        val place = "c".repeat(64)
        val hash = "d".repeat(40)
        try {
            val firstStore = AndroidSnapshotRestoreJournalStore(context)
            val beforeAdd = SnapshotRestoreJournalV1.open(
                firstStore, archive, target, listOf(hash))
            beforeAdd.prepare(hash, place)
            beforeAdd.beforeAdd(hash, place)
            val afterProcessReopen = SnapshotRestoreJournalV1.open(
                AndroidSnapshotRestoreJournalStore(context), archive,
                target, listOf(hash))
            assertEquals(SnapshotRestoreJournalV1.Phase.IN_FLIGHT,
                afterProcessReopen.entries().single().phase)
            assertTrue(prefs.all.isNotEmpty())
            val serialized = prefs.all.values.joinToString("|")
            assertFalse(serialized.contains("https://"))
            assertFalse(serialized.contains("/downloads"))
            assertFalse(serialized.contains("passkey"))
            // An absent result cannot silently change IN_FLIGHT to READY.
            afterProcessReopen.reconcileWithCompleteTarget(emptyList(), true)
            assertEquals(SnapshotRestoreJournalV1.Phase.IN_FLIGHT,
                SnapshotRestoreJournalV1.open(
                    AndroidSnapshotRestoreJournalStore(context), archive, target,
                    listOf(hash)).entries().single().phase)
            afterProcessReopen.reconcileWithCompleteTarget(listOf(hash), true)
            assertEquals(SnapshotRestoreJournalV1.Phase.VERIFIED,
                SnapshotRestoreJournalV1.open(
                    AndroidSnapshotRestoreJournalStore(context), archive, target,
                    listOf(hash)).entries().single().phase)
        } finally {
            val edit = prefs.edit().clear()
            for ((key, value) in before) {
                if (value is String) edit.putString(key, value)
            }
            assertTrue(edit.commit())
        }
    }
}
