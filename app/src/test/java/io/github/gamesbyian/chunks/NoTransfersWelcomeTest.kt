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
                 profiles: Boolean = false, query: String = "", addOpen: Boolean = false) =
            showPhoneFirstRunWelcome(confirmed, stale, empty, profiles, query, addOpen)
        org.junit.Assert.assertTrue(show())
        org.junit.Assert.assertFalse(show(confirmed = false))
        org.junit.Assert.assertFalse(show(stale = true))
        org.junit.Assert.assertFalse(show(empty = false))
        org.junit.Assert.assertFalse(show(profiles = true))
        org.junit.Assert.assertFalse(show(query = "hidden"))
        org.junit.Assert.assertFalse(show(addOpen = true))
    }

}
