package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The same Connections form selects the backend, without implicit login or Add. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class UniversalConnectionProtocolPickerTest {
    @get:Rule val rule = createComposeRule()
    private var selectedKind = RemoteBackendKind.RTORRENT
    private var remoteAttempts = 0
    private var rTorrentAttempts = 0

    private fun render(
        enabled: Boolean = true,
        initial: RemoteBackendKind = RemoteBackendKind.RTORRENT
    ) {
        selectedKind = initial
        remoteAttempts = 0
        rTorrentAttempts = 0
        rule.setContent {
            var kind by remember { mutableStateOf(initial) }
            var endpoint by remember { mutableStateOf("") }
            var daemon by remember { mutableStateOf("") }
            ChunksTheme {
                ConnectionScreen(
                    endpoint = endpoint, username = "", password = "",
                    error = null, loading = false,
                    rememberLogin = true, savedLoginPresent = false,
                    savedProfiles = emptyList(), credentialNotice = null,
                    onRememberLogin = {}, onForgetLogin = {}, onSelectProfile = {},
                    onRenameProfile = { _, _ -> false }, onForgetProfile = {},
                    onLocalZipReview = {}, onInspectSnapshot = {}, onLocalEngine = {},
                    onEndpoint = { endpoint = it }, onUsername = {}, onPassword = {},
                    onConnect = { rTorrentAttempts++ },
                    enableAdditionalProtocols = enabled,
                    chosenBackend = kind,
                    onChosenBackend = { kind = it; selectedKind = it },
                    delugeDaemonHostId = daemon,
                    onDelugeDaemonHostId = { daemon = it },
                    onConnectReadOnlyBackend = { remoteAttempts++ }
                )
            }
        }
    }

    @Test fun legacyDefaultHasNoNewControlsAndPreservesExistingRTorrentEntry() {
        render(enabled = false)
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_ADD).performClick()
        rule.onNodeWithTag("connection-backend-qbittorrent").assertDoesNotExist()
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").assertExists()
        assertEquals(RemoteBackendKind.RTORRENT, selectedKind)
        assertEquals(0, remoteAttempts)
        assertEquals(0, rTorrentAttempts)
    }

    @Test fun oneConnectionFormSwitchesToQBittorrentExactUrlWithoutInvokingRTorrent() {
        render()
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_ADD).performClick()
        rule.onNodeWithTag("connection-backend-qbittorrent").performClick()
        rule.waitForIdle()
        assertEquals(RemoteBackendKind.QBITTORRENT, selectedKind)
        rule.onNodeWithText("qBittorrent WebUI root URL").assertExists()
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").assertDoesNotExist()
        rule.onNodeWithText("Advanced connection settings").assertDoesNotExist()
        rule.onNodeWithText("Deluge daemon ID (optional)").assertDoesNotExist()
        assertEquals(0, remoteAttempts)
        assertEquals(0, rTorrentAttempts)
    }

    /**
     * Requirement re-expressed (2026-11-18): Deluge offers an optional daemon
     * ID. Blank adopts the Web UI's daemon only when exactly one is
     * registered (RemoteProtocolConnect.resolveDelugeDaemon); several require
     * the explicit ID. Connect still needs the password and never uses rTorrent
     * endpoint discovery.
     */
    @Test fun delugeOffersOptionalDaemonFieldAndNeverUsesRPCEndpointDiscovery() {
        render()
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_ADD).performClick()
        rule.onNodeWithTag("connection-backend-deluge").performClick()
        rule.waitForIdle()
        assertEquals(RemoteBackendKind.DELUGE, selectedKind)
        rule.onNodeWithText("Deluge Web JSON URL").assertExists()
        rule.onNodeWithText("Advanced connection settings").assertDoesNotExist()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Deluge daemon ID (optional)"))
        rule.onNodeWithText("Deluge daemon ID (optional)").assertExists()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(
            hasTestTag(ChunksSemantics.CONNECTION_CONNECT))
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_CONNECT).assertIsNotEnabled()
        assertEquals(0, remoteAttempts)
    }

    @Test fun selectingTransmissionAndReturningToRTorrentRetainsLegacyLabels() {
        render()
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_ADD).performClick()
        rule.onNodeWithTag("connection-backend-transmission").performClick()
        rule.waitForIdle()
        assertEquals(RemoteBackendKind.TRANSMISSION, selectedKind)
        rule.onNodeWithText("Transmission RPC URL").assertExists()
        rule.onNodeWithTag("connection-backend-rtorrent").performClick()
        rule.waitForIdle()
        assertEquals(RemoteBackendKind.RTORRENT, selectedKind)
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").assertExists()
        assertEquals(0, rTorrentAttempts)
        assertEquals(0, remoteAttempts)
    }
}
