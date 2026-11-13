package io.github.gamesbyian.chunks

import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.EnvironmentHue
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Global colour visibility is a display-only preference. The immutable
 * per-environment palette remains intact while disabled and across reloads.
 */
@RunWith(RobolectricTestRunner::class)
class EnvironmentColourStoreTest {
    @Test fun globalSwitchDefaultsOnAndPreservesProfileChoices() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = EnvironmentColourStore(context)
        val local = EnvironmentSnapshots.LOCAL
        val remote = EnvironmentSnapshots.serverId("https://box.example/RPC2", "test")
        val palette = store.ensure(listOf(local, remote))
        assertTrue(store.showEnvironmentColours())
        assertEquals(2, listOfNotNull(palette.hueOf(local), palette.hueOf(remote)).size)
        assertTrue(store.choose(remote, EnvironmentHue.PINK))
        val revisionBefore = EnvironmentColourStore.revision
        assertTrue(store.setShowEnvironmentColours(false))
        assertFalse(store.showEnvironmentColours())
        assertTrue(EnvironmentColourStore.revision > revisionBefore)
        assertEquals(EnvironmentHue.PINK, EnvironmentColourStore(context).load().hueOf(remote))
        assertTrue(store.setShowEnvironmentColours(true))
        assertTrue(EnvironmentColourStore(context).showEnvironmentColours())
        assertEquals(EnvironmentHue.PINK, EnvironmentColourStore(context).load().hueOf(remote))
    }
}
