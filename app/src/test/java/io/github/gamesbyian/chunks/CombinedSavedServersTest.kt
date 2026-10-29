package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
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
class CombinedSavedServersTest {
    @get:Rule val rule = createComposeRule()
    private val scopeA = "https://a.example/RPC2\u0000ann"
    private val scopeB = "https://b.example/RPC2\u0000bob"
    private fun id(scope: String) = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverId(
        scope.substringBefore('\u0000'), scope.substringAfter('\u0000')).value
    private var calls = 0

    private fun show(profiles: List<CombinedSavedProfile>) = rule.setContent {
        ChunksTheme { Surface(color = ChunksColors.Background) {
            CombinedTransfersScreen(server = null, savedProfiles = profiles, onOpenPhone = {},
                onOpenServer = null, onBack = {})
        } }
    }

    @Test fun loadsOtherSavedServersOnlyWhenAskedAndReportsFailures() {
        show(listOf(
            CombinedSavedProfile(id(scopeA), "ann @ a.example/RPC2", scopeA) {
                calls++; listOf(RemoteTransfer("a".repeat(40), "Linux ISO", 100, 100, true, "/d", 0, 5))
            },
            CombinedSavedProfile(id(scopeB), "bob @ b.example/RPC2", scopeB) {
                calls++; throw java.net.ConnectException("refused")
            }))
        rule.onNodeWithText("Server · ann @ a.example/RPC2: Not connected · 0 transfers").assertExists()
        assertEquals("nothing is fetched until the user asks", 0, calls)
        rule.onNodeWithText("Load other saved servers (read-only)").performScrollTo().performClick()
        rule.waitUntil(5_000) { calls == 2 }
        rule.waitForIdle()
        rule.onNodeWithText("Linux ISO").performScrollTo().assertExists()
        rule.onNodeWithText("Server · ann @ a.example/RPC2: Live · 1 transfers").assertExists()
        rule.onNodeWithText("Server · bob @ b.example/RPC2: Could not load (connection refused) · 0 transfers")
            .assertExists()
        rule.onNodeWithText("Refresh other saved servers").assertExists()
    }

    @Test fun profileWithoutVerifiableScopeStaysLockedAndIsNeverFetched() {
        show(listOf(CombinedSavedProfile(id(scopeA), "ann @ a.example/RPC2", aliasScope = null) {
            calls++; emptyList()
        }))
        rule.onNodeWithText("Server · ann @ a.example/RPC2: Locked, open it to unlock · 0 transfers").assertExists()
        rule.onNodeWithText("Load other saved servers (read-only)").assertDoesNotExist()
        assertEquals(0, calls)
    }
}
