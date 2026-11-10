package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The disconnected Connections destination is remote management, not a phone queue. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ConnectionsRemoteIntroTest {
    @get:Rule val rule = createComposeRule()

    private fun render(
        profiles: List<RememberedRemoteProfileRoster.Profile> = emptyList(),
        onProfile: (RememberedRemoteProfileRoster.Profile) -> Unit = {}
    ) {
        rule.setContent {
            ChunksTheme {
                ConnectionScreen(
                    endpoint = "", username = "", password = "",
                    error = null, loading = false,
                    rememberLogin = true, savedLoginPresent = profiles.isNotEmpty(),
                    savedProfiles = profiles, credentialNotice = null,
                    onRememberLogin = {},
                    onForgetLogin = {},
                    onSelectProfile = onProfile,
                    onRenameProfile = { _, _ -> false },
                    onForgetProfile = {},
                    onLocalZipReview = {},
                    onInspectSnapshot = {},
                    onLocalEngine = {},
                    onEndpoint = {}, onUsername = {},
                    onPassword = {}, onConnect = {}
                )
            }
        }
    }

    @Test fun remoteIntroOwnsThePurpleCardAndExcludesPhonePromotions() {
        render()
        rule.onNodeWithText("Connect to another computer or server (optional)").assertExists()
        rule.onNodeWithText("Connect to another computer or server (optional)")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithText("Your downloads").assertDoesNotExist()
        rule.onNodeWithText("This device").assertDoesNotExist()
        rule.onNodeWithText("Open phone downloads").assertDoesNotExist()
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").assertExists()
    }

    @Test fun savedProfileSelectionKeepsItsExactAccountIdentity() {
        val profile = RememberedRemoteProfileRoster.Profile(
            id = RememberedRemoteProfileRoster.identityOf("https://example.test/RPC2", "one"),
            label = "Studio",
            credentials = RemoteCredentials("https://example.test/RPC2", "one", "example-only")
        )
        var selectedId: String? = null
        render(profiles = listOf(profile), onProfile = { selectedId = it.id })
        rule.onNodeWithText("Use Studio").performClick()
        assertEquals(profile.id, selectedId)
    }
}
