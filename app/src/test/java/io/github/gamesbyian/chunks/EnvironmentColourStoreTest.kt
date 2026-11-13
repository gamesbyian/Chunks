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
import org.robolectric.annotation.Config

/**
 * Global colour visibility is a display-only preference. The immutable
 * per-environment palette remains intact while disabled and across reloads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EnvironmentColourStoreTest {
    @Test fun globalSwitchDefaultsOnAndPreservesProfileChoices() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("chunks-environment-colours-v1", android.content.Context.MODE_PRIVATE)
            .edit().remove("show_environment_colours").commit()
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
