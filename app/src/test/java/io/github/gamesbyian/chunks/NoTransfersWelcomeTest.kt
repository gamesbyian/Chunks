package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class NoTransfersWelcomeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun explainsTheTwoChoicesAndOnlyNavigatesOnTap() {
        var add = 0
        var connections = 0
        rule.setContent {
            ChunksTheme {
                Surface {
                    NoTransfersWelcome(onAdd = { add++ }, onConnections = { connections++ })
                }
            }
        }
        rule.onNodeWithText("Welcome to Chunks").assertExists()
        rule.onNodeWithText("Start a download on this device, or connect a remote BitTorrent client. " +
            "You can use Chunks with either, or both.").assertExists()
        assertEquals(0, add)
        assertEquals(0, connections)
        rule.onNodeWithText("Add a torrent").performClick()
        assertEquals(1, add)
        assertEquals(0, connections)
        rule.onNodeWithText("Connections").performClick()
        assertEquals(1, add)
        assertEquals(1, connections)
    }
    @Test fun firstRunRequiresAConfirmedFreshUnfilteredEmptyInventory() {
        fun show(confirmed: Boolean = true, stale: Boolean = false, empty: Boolean = true,
                 profiles: Boolean = false, query: String = "", addOpen: Boolean = false,
                 defaultFilter: Boolean = true, restoreHealthy: Boolean = true,
                 noIncoming: Boolean = true) =
            showPhoneFirstRunWelcome(confirmed, stale, empty, profiles, query, addOpen,
                defaultFilter, restoreHealthy, noIncoming)
        org.junit.Assert.assertTrue(show())
        org.junit.Assert.assertFalse(show(confirmed = false))
        org.junit.Assert.assertFalse(show(stale = true))
        org.junit.Assert.assertFalse(show(empty = false))
        org.junit.Assert.assertFalse(show(profiles = true))
        org.junit.Assert.assertFalse(show(query = "hidden"))
        org.junit.Assert.assertFalse(show(addOpen = true))
        // A zero-job filtered view must keep filter controls reachable so
        // the user can return to All rather than seeing a false first launch.
        org.junit.Assert.assertFalse(show(defaultFilter = false))
        org.junit.Assert.assertFalse(show(restoreHealthy = false))
        org.junit.Assert.assertFalse(show(noIncoming = false))
    }

    @Test fun canonicalWelcomeNeverConflatesUnobservedRestoreOfflineOrFilteredZero() {
        fun show(
            noResume: Boolean? = true, running: Boolean = false,
            confirmed: Boolean = false, stale: Boolean = false, count: Int = 0,
            remote: Boolean = false, narrow: Boolean = false,
            unlocked: Boolean = true, incoming: Boolean = false
        ) = showCanonicalFirstRunWelcome(noResume, running, confirmed, stale,
            count, remote, narrow, unlocked, incoming)
        org.junit.Assert.assertTrue(show())
        org.junit.Assert.assertTrue(show(running = true, confirmed = true))
        org.junit.Assert.assertFalse(show(noResume = null))
        org.junit.Assert.assertFalse(show(noResume = false))
        // Saved local jobs cannot be erased by an early, incomplete empty
        // native poll while a restore is still pending.
        org.junit.Assert.assertFalse(show(noResume = false, running = true, confirmed = true))
        // A stopped engine may still have an observed nonempty frame.
        // Durable absence alone cannot erase that frame into Welcome.
        org.junit.Assert.assertFalse(show(noResume = true, running = false, count = 1))
        org.junit.Assert.assertFalse(show(running = true))
        org.junit.Assert.assertFalse(show(running = true, confirmed = true, count = 1))
        org.junit.Assert.assertFalse(show(stale = true))
        org.junit.Assert.assertFalse(show(remote = true))
        org.junit.Assert.assertFalse(show(narrow = true))
        org.junit.Assert.assertFalse(show(unlocked = false))
        org.junit.Assert.assertFalse(show(incoming = true))
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-mdpi")
    fun narrowAt200PercentKeepsBothNavigationChoicesActionable() {
        var add = 0
        var connections = 0
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 2f)
            ) {
                ChunksTheme {
                    Surface {
                        NoTransfersWelcome(
                            onAdd = { add++ },
                            onConnections = { connections++ }
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("Welcome to Chunks").assertExists()
        rule.onNodeWithText("Add a torrent").performClick()
        rule.onNodeWithText("Connections").performClick()
        assertEquals(1, add)
        assertEquals(1, connections)
    }
}
