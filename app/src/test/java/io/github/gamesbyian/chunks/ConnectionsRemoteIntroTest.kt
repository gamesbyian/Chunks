package io.github.gamesbyian.chunks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ConnectionsRemoteIntroTest {
    @get:Rule val rule = createComposeRule()
    private fun profile(number: Int) = RememberedRemoteProfileRoster.Profile(
        "profile-$number", "Studio", RemoteCredentials("https://server.test/rutorrent$number/RPC2", "one", "fixture"))
    private fun render(profiles: List<RememberedRemoteProfileRoster.Profile> = emptyList(),
                       connected: String? = null, onProfile: (RememberedRemoteProfileRoster.Profile) -> Unit = {},
                       onView: ((RememberedRemoteProfileRoster.Profile) -> Unit)? = null,
                       onCurrent: () -> Unit = {}, onDisconnect: () -> Unit = {}, scale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                ChunksTheme { ConnectionScreen(endpoint = "", username = "", password = "", error = null,
                    loading = false, rememberLogin = true, savedLoginPresent = profiles.isNotEmpty(),
                    savedProfiles = profiles, credentialNotice = null, onRememberLogin = {}, onForgetLogin = {},
                    onSelectProfile = onProfile, onOpenProfileTransfers = onView,
                    connectedProfileId = profiles.firstOrNull()?.id, connectedSessionName = connected,
                    onViewConnectedTransfers = onCurrent, onDisconnectCurrent = onDisconnect,
                    onRenameProfile = { _, _ -> false }, onForgetProfile = {}, onLocalZipReview = {},
                    onInspectSnapshot = {}, onLocalEngine = {}, onEndpoint = {}, onUsername = {}, onPassword = {}, onConnect = {}) }
            }
        }
    }
    private fun action(id: String, text: String) = rule.onNode(hasText(text) and
        hasAnyAncestor(hasTestTag("connection-profile-$id")))

    @Test fun compactEmptyCardContainsAddAndFormOpensOnlyOnRequest() {
        render()
        rule.onNodeWithText("Connect to a remote client").assertExists()
        rule.onNodeWithText("Manage torrents on another computer or server.").assertExists()
        rule.onNodeWithText("Your downloads").assertDoesNotExist()
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").assertDoesNotExist()
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_ADD).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_NAME).assertExists()
    }
    @Test fun connectPreparesOnlyTheExactSavedProfileWithoutGrantingInventoryAuthority() {
        val first = profile(1); val second = profile(2)
        val selected = mutableListOf<String>(); val viewed = mutableListOf<String>()
        render(listOf(first, second), onProfile = { selected += it.id }, onView = { viewed += it.id })
        rule.onNodeWithText("Connect to a remote client").assertDoesNotExist()
        rule.onAllNodesWithText("View transfers").assertCountEquals(0)
        action(second.id, "Connect").performClick()
        assertEquals(listOf(second.id), selected)
        assertTrue(viewed.isEmpty())
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_NAME).assertExists()
    }
    @Test fun readOnlyInventoryRemainsDistinctFromExplicitConnectUnderManage() {
        val first = profile(1); val second = profile(2)
        val selected = mutableListOf<String>(); val viewed = mutableListOf<String>()
        render(listOf(first, second), onProfile = { selected += it.id }, onView = { viewed += it.id })
        action(second.id, "Manage").performClick()
        rule.onNodeWithText(second.address).assertExists()
        rule.onNodeWithText("View available inventory (read only)").performScrollTo().performClick()
        assertEquals(listOf(second.id), viewed)
        assertTrue(selected.isEmpty())
        rule.onNodeWithText("Edit connection / login").performScrollTo().performClick()
        assertEquals(listOf(second.id), selected)
    }
    @Test fun connectedProfileAppearsOnceWithViewAndManageAndDisconnectIsSecondary() {
        var views = 0; var disconnects = 0
        val profile = profile(1)
        render(listOf(profile), "Studio", onCurrent = { views++ }, onDisconnect = { disconnects++ })
        rule.onAllNodesWithText("Studio").assertCountEquals(1)
        rule.onNodeWithText("Connected").assertExists()
        rule.onNodeWithText("Saved servers").assertDoesNotExist()
        rule.onNodeWithText("Disconnect").assertDoesNotExist()
        rule.onNodeWithText("View transfers").performClick()
        assertEquals(1, views); assertEquals(0, disconnects)
        action(profile.id, "Manage").performClick()
        rule.onNodeWithText("Disconnect").performScrollTo().performClick()
        assertEquals(1, disconnects)
    }
    @Test @Config(qualifiers = "w320dp-h700dp-mdpi")
    fun twoActionsRemainReachableAtNarrow200PercentWithoutEndpointOnRoster() {
        val p = profile(1)
        render(listOf(p), scale = 2f)
        action(p.id, "Connect").performScrollTo().assertHeightIsAtLeast(48.dp)
        action(p.id, "Manage").performScrollTo().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText(p.address).assertDoesNotExist()
    }
}
