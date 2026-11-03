package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Front-of-Add exact scope language, not mocked RPC or mutation evidence. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class RemoteAddScopeIntroTest {
    @get:Rule val rule = createComposeRule()

    @Test fun selectedRemoteServerAndPhoneCopyBoundaryAreVisibleAtLargeText() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChunksTheme { Surface {
                    RemoteAddScopeIntro("Seedbox 2", "https://seed.example/rutorrent2/plugins/rpc/rpc.php\u0000owner")
                } }
            }
        }
        rule.onNodeWithText("Add a download to this server").assertExists()
        rule.onNodeWithText("Adding to: Seedbox 2").assertExists()
        rule.onNodeWithText("Nothing is copied to this phone by Add.", substring = true).assertExists()
        rule.onNodeWithText("Saved Chunks server-seeding rules are not applied yet;", substring = true).assertExists()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun missingOptionalFriendlyAliasNeverInventsOne() {
        rule.setContent {
            ChunksTheme { Surface { RemoteAddScopeIntro(null, null) } }
        }
        rule.onNodeWithText("Add a download to this server").assertExists()
        rule.onNodeWithText("Adding to:", substring = true).assertDoesNotExist()
    }
}
