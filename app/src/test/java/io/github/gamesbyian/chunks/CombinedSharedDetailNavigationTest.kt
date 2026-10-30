package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Combined navigation must open read-only details for exact environment+hash.
 * Saved listings have no owning signed-in UI, no command executor and no
 * capability probe; they still deserve an honest useful detail view.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class CombinedSharedDetailNavigationTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val hash = "a".repeat(40)
    private val connectedScope = "https://source.example/rutorrent2/RPC2\u0000ian"
    private val savedScope = "https://source.example/rutorrent3/RPC2\u0000ian"
    private val savedId = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverId(
        savedScope.substringBefore('\u0000'), "ian").value
    private val savedLabel = "Instance three"
    private val connected = RemoteTransfer(hash, "Instance two download", 2048, 1024,
        true, "/two", 0, null)
    private val saved = RemoteTransfer(hash, "Instance three download", 4096, 2048,
        false, "/three", null, 55)

    @Test fun sameHashOnTwoProfilesShowsTheCorrectOwningDetailsWithoutCommands() {
        CombinedViewPreferences(context).save(UnifiedTransferQuery())
        val requests = AtomicInteger(0)
        val commands = mutableListOf<String>()
        val lifecycle = CombinedServerLifecycle(
            begin = { commands += "leased"; false },
            settled = { commands += "settled" },
            setStarted = { _, _ -> commands += "mutated"; LifecycleOutcome.Verified(true) },
            probe = { commands += "probed"; error("No implicit server probing") }
        )
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                ChunksTheme {
                    Surface {
                        CombinedTransfersScreen(
                            server = CombinedServerSource(connectedScope, listOf(connected),
                                false, true, System.currentTimeMillis(), lifecycle),
                            savedProfiles = listOf(CombinedSavedProfile(savedId,
                                savedLabel, savedScope, listOnly = {
                                    requests.incrementAndGet()
                                    listOf(saved)
                                })),
                            onOpenPhone = {}, onOpenServer = {}, onBack = {})
                    }
                }
            }
        }

        val connectedName = serverEnvironmentLabel(connectedScope)
        rule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Instance two download"))
        // TalkBack activates the button's semantic action directly. A simulated
        // center-coordinate click against an offscreen LazyColumn row at 200%
        // can instead hit the next button as the viewport remeasures.
        rule.onNodeWithContentDescription("Details for Instance two download on $connectedName")
            .performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.onNodeWithText("Transfer details").assertExists()
        rule.onNodeWithText("Environment: $connectedName").assertExists()
        rule.onNodeWithText("Instance two download").assertExists()
        rule.onNodeWithText("Instance three download").assertDoesNotExist()
        rule.onNodeWithText("Exact infohash: $hash").assertExists()
        rule.onNodeWithText("Download rate: 0 B/s").assertExists()
        rule.onNodeWithText("Upload rate: not reported").assertExists()
        rule.onNodeWithText("Start and stop: Capability has not been checked recently for this exact environment.")
            .performScrollTo().assertExists()
        rule.onNodeWithText("Back to transfers").performClick()
        // Open detail never changed filter, login, selection or underlying engine.
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Filter transfers"))
        rule.onNodeWithText("Filter transfers").assertExists()

        rule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Load other saved servers (read-only)"))
        rule.onNodeWithText("Load other saved servers (read-only)").performClick()
        rule.waitUntil(10_000) { requests.get() == 1 }
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Instance three download"))
        rule.onNodeWithContentDescription(
            "Details for Instance three download on Server · $savedLabel")
            .performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.onNodeWithText("Environment: Server · $savedLabel").assertExists()
        rule.onNodeWithText("Instance three download").assertExists()
        rule.onNodeWithText("Instance two download").assertDoesNotExist()
        rule.onNodeWithText("Exact infohash: $hash").assertExists()
        rule.onNodeWithText("Download rate: not reported").assertExists()
        rule.onNodeWithText("Upload rate: 55 B/s").assertExists()
        // A saved profile is read-only here: no switch to signed-in server list.
        rule.onNodeWithText("Open Server · $savedLabel transfers").assertDoesNotExist()
        rule.onNodeWithText("Capabilities in this environment").performScrollTo().assertExists()
        assertEquals("Explicit saved-list fetch only", 1, requests.get())
        assertTrue("Details never mutate, probe or start the phone engine", commands.isEmpty())
        assertNull(LocalEngineHolder.current())
    }

    @Test fun staleSourceCanOpenLastKnownDetailsButCannotImplyCurrentState() {
        CombinedViewPreferences(context).save(UnifiedTransferQuery())
        rule.setContent {
            ChunksTheme { Surface {
                CombinedTransfersScreen(
                    server = CombinedServerSource(connectedScope, listOf(connected),
                        stale = true, fullView = true, observedAtEpochMillis = System.currentTimeMillis()),
                    savedProfiles = emptyList(), onOpenPhone = {}, onOpenServer = {}, onBack = {})
            } }
        }
        val origin = serverEnvironmentLabel(connectedScope)
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Instance two download"))
        rule.onNodeWithContentDescription("Details for Instance two download on $origin")
            .performScrollTo().performClick()
        rule.onNodeWithText("Observation: The last transfer observation is out of date.", substring = true)
            .assertExists()
        rule.onNodeWithText("Instance two download").assertExists()
        rule.onNodeWithText("Back to transfers").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(
            hasText(origin + ": Out of date, showing last list · 1 transfers"))
        rule.onNodeWithText(origin + ": Out of date, showing last list · 1 transfers").assertExists()
    }
}
