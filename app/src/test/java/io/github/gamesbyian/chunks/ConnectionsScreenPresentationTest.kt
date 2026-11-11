package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Connections manages verified profiles and setup, never an alternative queue list. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ConnectionsScreenPresentationTest {
    @get:Rule val rule = createComposeRule()

    private fun render(
        profiles: List<RememberedRemoteProfileRoster.Profile> = emptyList(),
        connected: String? = null,
        onProfile: (RememberedRemoteProfileRoster.Profile) -> Unit = {},
        onViewCurrent: () -> Unit = {},
        onDisconnect: () -> Unit = {}
    ) {
        rule.setContent {
            ChunksTheme {
                ConnectionScreen(
                    endpoint = "", username = "", password = "",
                    error = null, loading = false,
                    rememberLogin = true, savedLoginPresent = profiles.isNotEmpty(),
                    savedProfiles = profiles, credentialNotice = null,
                    connectedSessionName = connected,
                    onViewConnectedTransfers = onViewCurrent,
                    onDisconnectCurrent = onDisconnect,
                    onOpenProfileTransfers = onProfile,
                    onRememberLogin = {},
                    onForgetLogin = {},
                    onSelectProfile = {},
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

    @Test fun purpleIntroductionIsRemoteOnlyAndLoginFormFollows() {
        render()
        rule.onNodeWithText("Connect to another computer or server (optional)").assertExists()
        rule.onNodeWithText("Your downloads").assertDoesNotExist()
        rule.onNodeWithText("This device").assertDoesNotExist()
        rule.onNodeWithText("Open phone downloads").assertDoesNotExist()
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").assertExists()
    }

    @Test fun savedProfileOpensThatExactProfileWithoutAConnectAttempt() {
        val profile = RememberedRemoteProfileRoster.Profile(
            id = RememberedRemoteProfileRoster.identityOf("https://example.test/RPC2", "one"),
            label = "Studio",
            credentials = RemoteCredentials("https://example.test/RPC2", "one", "example-only")
        )
        var selectedId: String? = null
        render(profiles = listOf(profile), onProfile = { selectedId = it.id })
        // The button's accessible name carries the exact saved profile (label and address).
        rule.onNode(androidx.compose.ui.test.hasContentDescription(
            "View transfers for saved Studio", substring = true)).performClick()
        assertEquals(profile.id, selectedId)
    }

    @Test fun verifiedCurrentSessionIsShownAsManagementWithViewAction() {
        var views = 0
        var disconnects = 0
        render(connected = "Studio", onViewCurrent = { views++ },
            onDisconnect = { disconnects++ })
        rule.onNodeWithText("Connected · Studio").assertExists()
        rule.onNodeWithText("View transfers").performClick()
        assertEquals(1, views)
        assertEquals(0, disconnects)
    }
}
