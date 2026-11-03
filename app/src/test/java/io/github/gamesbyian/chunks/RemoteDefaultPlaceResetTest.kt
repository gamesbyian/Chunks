package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A remote Add Place override is a one-off draft, not an rTorrent setting.
 * Presentation test only: it does not claim a verified server destination.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi", fontScale = 2.0f)
class RemoteDefaultPlaceResetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun resetIsDiscoverableAndOnlyClearsDraftOverride() {
        val draft = mutableStateOf("/server/custom")
        var changes = 0
        rule.setContent {
            ChunksTheme { Surface {
                RemoteDefaultPlaceResetControl(
                    show = draft.value.isNotBlank(),
                    enabled = true,
                    onReset = { changes++; draft.value = "" })
            } }
        }
        rule.onNodeWithContentDescription("Use rTorrent default save folder for this Add")
            .performClick()
        assertEquals(1, changes)
        assertEquals("", draft.value)
        rule.onNodeWithText("Reset to rTorrent default folder").assertDoesNotExist()
    }

    @Test fun unavailableServerDefaultDoesNotOfferAnActionableReset() {
        rule.setContent {
            ChunksTheme { Surface {
                RemoteDefaultPlaceResetControl(show = false, enabled = true, onReset = {
                    error("Cannot reset when rTorrent has not reported a default")
                })
            } }
        }
        rule.onNodeWithText("Reset to rTorrent default folder").assertDoesNotExist()
    }
}
