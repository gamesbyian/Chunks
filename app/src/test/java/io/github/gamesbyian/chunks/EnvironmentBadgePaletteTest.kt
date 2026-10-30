package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.EnvironmentHue
import io.github.gamesbyian.chunks.model.EnvironmentId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class EnvironmentBadgePaletteTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val id = EnvironmentId("e".repeat(64))

    @Test fun visibleBadgeUpdatesAfterColourChangeAndRestoreWithoutNavigation() {
        val store = EnvironmentColourStore(context)
        val automatic = store.ensure(listOf(id)).hueOf(id)
        assertNotNull(automatic)
        assertTrue(store.restoreAutomatic(id))
        val chosen = EnvironmentHue.entries.first { it != automatic }

        rule.setContent {
            ChunksTheme {
                Surface(color = ChunksColors.Background) {
                    EnvironmentBadge(label = "Test server", server = true,
                        environment = id, modifier = Modifier.testTag("badge"))
                }
            }
        }

        rule.waitForIdle()
        val original = rule.onNodeWithTag("badge").captureToImage().asAndroidBitmap()
        try {
            // A different store object updates an already-composed badge.
            assertTrue(EnvironmentColourStore(context).choose(id, chosen))
            rule.waitUntil(5_000) {
                !original.sameAs(rule.onNodeWithTag("badge").captureToImage().asAndroidBitmap())
            }
            val changed = rule.onNodeWithTag("badge").captureToImage().asAndroidBitmap()
            assertFalse("Choosing a hue must update an existing badge", original.sameAs(changed))

            assertTrue(EnvironmentColourStore(context).restoreAutomatic(id))
            rule.waitUntil(5_000) {
                original.sameAs(rule.onNodeWithTag("badge").captureToImage().asAndroidBitmap())
            }
            val restored = rule.onNodeWithTag("badge").captureToImage().asAndroidBitmap()
            assertTrue("Restoring automatic should render the original badge", original.sameAs(restored))
            assertEquals(automatic, store.load().hueOf(id))
        } finally {
            store.restoreAutomatic(id)
        }
    }

    @Test fun concurrentPaletteEffectsAcrossSeparateStoresRemainIdempotent() {
        // A live remote page and the combined page can both first see the same
        // two configured accounts. Neither may overwrite committed identity
        // assignments; a later ensure is idempotent for their exact IDs.
        val phone = EnvironmentId("p".repeat(64))
        val remote = EnvironmentId("r".repeat(64))
        val ids = listOf(phone, remote)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val go = java.util.concurrent.CountDownLatch(1)
        try {
            val first = pool.submit<io.github.gamesbyian.chunks.model.EnvironmentPalette.State> {
                go.await()
                EnvironmentColourStore(context).ensure(ids)
            }
            val second = pool.submit<io.github.gamesbyian.chunks.model.EnvironmentPalette.State> {
                go.await()
                EnvironmentColourStore(context).ensure(ids)
            }
            go.countDown()
            val a = first.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val b = second.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(a, b)
            assertNotNull(a.hueOf(phone))
            assertNotNull(a.hueOf(remote))
            assertNotEquals(a.hueOf(phone), a.hueOf(remote))
            val again = EnvironmentColourStore(context).ensure(ids)
            assertEquals("Repeated initialization must preserve all identity colours", a, again)
            // Revision is intentionally global: unrelated badges/tests can
            // update it, so this test must not mistake their writes for ours.
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun invalidEnvironmentCannotModifyPaletteOrTriggerUiRevision() {
        val store = EnvironmentColourStore(context)
        val revision = EnvironmentColourStore.revision
        assertFalse(store.choose(EnvironmentId("bad id"), EnvironmentHue.PINK))
        assertFalse(store.restoreAutomatic(EnvironmentId("bad id")))
        assertEquals(revision, EnvironmentColourStore.revision)
    }
}
