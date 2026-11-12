package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
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

/** The disconnected Connections destination is remote management, not a local queue. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ConnectionsRemoteIntroTest {
    @get:Rule val rule = createComposeRule()

    private fun render(
        profiles: List<RememberedRemoteProfileRoster.Profile> = emptyList(),
        onProfile: (RememberedRemoteProfileRoster.Profile) -> Unit = {},
        onViewTransfers: ((RememberedRemoteProfileRoster.Profile) -> Unit)? = null,
        connected: String? = null,
        onViewConnectedTransfers: () -> Unit = {},
        onDisconnect: () -> Unit = {},
        fontScale: Float = 1f
    ) {
        rule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale)) {
            ChunksTheme {
                ConnectionScreen(
                    endpoint = "", username = "", password = "",
                    error = null, loading = false,
                    rememberLogin = true, savedLoginPresent = profiles.isNotEmpty(),
                    savedProfiles = profiles, credentialNotice = null,
                    onRememberLogin = {},
                    onForgetLogin = {},
                    onSelectProfile = onProfile,
                    connectedSessionName = connected,
                    onViewConnectedTransfers = onViewConnectedTransfers,
                    onDisconnectCurrent = onDisconnect,
                    onOpenProfileTransfers = onViewTransfers,
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
    }

    @Test fun remoteIntroOwnsThePurpleCardAndExcludesLocalPromotions() {
        render()
        rule.onNodeWithText("Connect to another computer or server (optional)").assertExists()
        rule.onNodeWithText("Connect to another computer or server (optional)")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithText("Your downloads").assertDoesNotExist()
        rule.onNodeWithText("This device").assertDoesNotExist()
        rule.onNodeWithText("Open local downloads").assertDoesNotExist()
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
    @Test fun savedProfileViewAndLoginRemainDistinctExactAccountActions() {
        val first = RememberedRemoteProfileRoster.Profile(
            id = RememberedRemoteProfileRoster.identityOf("https://server.test/rutorrent/RPC2", "one"),
            label = "Studio",
            credentials = RemoteCredentials("https://server.test/rutorrent/RPC2", "one", "example-only")
        )
        val second = RememberedRemoteProfileRoster.Profile(
            id = RememberedRemoteProfileRoster.identityOf("https://server.test/rutorrent2/RPC2", "one"),
            label = "Studio",
            credentials = RemoteCredentials("https://server.test/rutorrent2/RPC2", "one", "example-only")
        )
        val viewed = mutableListOf<String>()
        val chosenLogins = mutableListOf<String>()
        render(profiles = listOf(first, second),
            onViewTransfers = { viewed += it.id },
            onProfile = { chosenLogins += it.id })
        rule.onAllNodesWithText("View transfers").assertCountEquals(2)
        rule.onAllNodesWithText("Use saved login").assertCountEquals(2)
        // TalkBack must distinguish duplicate friendly labels by their saved
        // endpoint, without speaking credential secrets or ambiguous buttons.
        for (profile in listOf(first, second)) {
            rule.onNodeWithContentDescription(
                "View transfers for saved " + profile.label + " on " + profile.address
            ).assertExists()
            rule.onNodeWithContentDescription(
                "Use saved login for " + profile.label + " on " + profile.address
            ).assertExists()
            rule.onNodeWithContentDescription(
                "Rename saved " + profile.label + " on " + profile.address
            ).assertExists()
            rule.onNodeWithContentDescription(
                "Forget saved " + profile.label + " on " + profile.address
            ).assertExists()
        }
        // Both profiles have identical friendly labels but distinct endpoint routes.
        rule.onAllNodesWithText("View transfers")[1].performClick()
        assertEquals(listOf(second.id), viewed)
        assertEquals(emptyList<String>(), chosenLogins)
        rule.onNodeWithContentDescription(
            "Use saved login for " + first.label + " on " + first.address
        ).performClick()
        assertEquals(listOf(first.id), chosenLogins)
        assertEquals(listOf(second.id), viewed)
    }

    @Test fun verifiedConnectedSessionUsesConnectionsNotAnEditableLoginOrLegacyList() {
        var view = 0
        var disconnect = 0
        render(connected = "Studio", onViewConnectedTransfers = { view++ },
            onDisconnect = { disconnect++ })
        rule.onNodeWithText("Connect to another computer or server (optional)").assertExists()
        rule.onNodeWithText("Connected · Studio").assertExists()
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").assertDoesNotExist()
        rule.onNodeWithText("Connect to rTorrent").assertDoesNotExist()
        rule.onNodeWithText("View transfers").performClick()
        assertEquals(1, view)
        assertEquals(0, disconnect)
        rule.onNodeWithText("Disconnect").performClick()
        assertEquals(1, disconnect)
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-mdpi")
    fun duplicateProfileActionsStaySideBySideAndSpeakTheirExactRouteAt200Percent() {
        val profile = RememberedRemoteProfileRoster.Profile(
            id = RememberedRemoteProfileRoster.identityOf("https://server.test/rutorrent2/RPC2", "one"),
            label = "Studio",
            credentials = RemoteCredentials("https://server.test/rutorrent2/RPC2", "one", "example-only")
        )
        render(profiles = listOf(profile), onViewTransfers = {}, fontScale = 2f)
        val view = "View transfers for saved " + profile.label + " on " + profile.address
        val login = "Use saved login for " + profile.label + " on " + profile.address
        rule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasContentDescription(view))
        val viewBounds = rule.onNodeWithContentDescription(view).getUnclippedBoundsInRoot()
        val loginBounds = rule.onNodeWithContentDescription(login).getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("saved client actions remain on one row at 320dp/200%",
            viewBounds.top == loginBounds.top && viewBounds.right <= loginBounds.left)
        org.junit.Assert.assertTrue("exact-route TalkBack controls retain 48dp touch targets",
            listOf(viewBounds, loginBounds).all { it.bottom - it.top >= 48.dp })
    }

}
