package io.github.gamesbyian.chunks.seeding

import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.seeding.SeedingController.Assignment
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real SharedPreferences serialization is part of the at-most-once stop boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreferencesAssignmentStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val prefs get() = context.getSharedPreferences("chunks_seeding_assignments", 0)

    @Before fun clean() {
        assertTrue(prefs.edit().clear().commit())
    }

    @Test fun pendingAttemptSurvivesStoreRecreationAndPreservesStopUncertainty() {
        val original = Assignment(
            SeedingPolicy(ratioGoal = BigDecimal("1.00")),
            SeedingResolution.Source.RULE, "ipt",
            reasons = listOf("Saved rule"),
            stopAttemptedReason = "Ratio reached the threshold.",
            note = "The engine did not confirm the pause."
        )
        assertTrue(PreferencesAssignmentStore(context).put("ABCD", original))
        val restored = PreferencesAssignmentStore(context).get("abcd")
        assertEquals(original, restored)
        assertTrue(prefs.getString("a:abcd", "").orEmpty().contains("stopAttempt"))
        assertNull(restored?.stoppedByRule)
    }

    @Test fun earlierAssignmentsWithoutStopAttemptMarkerKeepTheirMeaning() {
        val old = Assignment(SeedingPolicy(maxSeedSeconds = 1), SeedingResolution.Source.DEFAULT, null,
            stoppedByRule = "Time limit reached")
        assertTrue(PreferencesAssignmentStore(context).put("old", old))
        val raw = org.json.JSONObject(prefs.getString("a:old", null)!!)
        raw.remove("stopAttempt") // the deployed pre-journal schema
        assertTrue(prefs.edit().putString("a:old", raw.toString()).commit())
        val recovered = PreferencesAssignmentStore(context).get("old")
        assertEquals(old, recovered)
        assertNull(recovered?.stopAttemptedReason)
    }

    @Test fun occupiedUnreadableRecordCannotBecomeANewManagedTransfer() {
        val key = "a:broken"
        assertTrue(prefs.edit().putString(key, "{unreadable").commit())
        val restored = PreferencesAssignmentStore(context).get("broken")
        assertNotNull(restored)
        assertNull(restored?.policy)
        assertTrue(restored?.note.orEmpty().contains("left unchanged"))
        assertEquals("{unreadable", prefs.getString(key, null))
        assertNull("Only an absent record means no assignment",
            PreferencesAssignmentStore(context).get("new-transfer"))

        assertTrue(prefs.edit().putInt(key, 3).commit())
        assertNull(PreferencesAssignmentStore(context).get("broken")?.policy)
        assertEquals(3, prefs.getInt(key, 0))
    }
}
