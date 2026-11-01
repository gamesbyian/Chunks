package io.github.gamesbyian.chunks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.LocalQueueFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalTransferFilterStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = context.getSharedPreferences(
        "chunks-local-transfer-filter-v1", Context.MODE_PRIVATE
    )

    @Before fun clearBefore() { preferences.edit().clear().commit() }
    @After fun clearAfter() { preferences.edit().clear().commit() }

    @Test fun phoneFilterSurvivesNewStoreInstanceWithoutSavingSearchOrCommandTarget() {
        val first = LocalTransferFilterStore(context)
        assertEquals(LocalQueueFilter.ALL, first.load())
        assertTrue(first.save(LocalQueueFilter.STOPPED))
        val afterRecreation = LocalTransferFilterStore(context)
        assertEquals(LocalQueueFilter.STOPPED, afterRecreation.load())
        assertEquals(setOf("filter"), preferences.all.keys)
        assertFalse(preferences.all.values.any { it.toString().contains("magnet:") })
        assertTrue(afterRecreation.save(LocalQueueFilter.NEEDS_ATTENTION))
        assertEquals(LocalQueueFilter.NEEDS_ATTENTION, first.load())
        assertTrue(first.save(LocalQueueFilter.ALL))
        assertEquals(LocalQueueFilter.ALL, LocalTransferFilterStore(context).load())
    }

    @Test fun malformedLegacyAndWrongTypeValuesFailToAllRatherThanInventingAFilter() {
        for (raw in listOf(null, "", "downloading", "all", "NOT_A_FILTER", "STOPPED;other")) {
            assertEquals(LocalQueueFilter.ALL, LocalTransferFilterStore.decode(raw))
        }
        preferences.edit().putInt("filter", 12).commit()
        assertEquals(LocalQueueFilter.ALL, LocalTransferFilterStore(context).load())
        preferences.edit().putString("filter", "RUNNING").commit()
        assertEquals(LocalQueueFilter.RUNNING, LocalTransferFilterStore(context).load())
    }
}
