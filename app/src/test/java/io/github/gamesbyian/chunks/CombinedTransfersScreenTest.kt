package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import io.github.gamesbyian.chunks.model.UnifiedSortDirection
import io.github.gamesbyian.chunks.model.UnifiedSortKey
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
class CombinedTransfersScreenTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val aliasScope = "https://box.example/rutorrent2/plugins/httprpc/action.php\u0000ian"
    private val otherScope = "https://other.example/RPC2\u0000seed"
    private val other = CombinedSavedProfile(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverId(
        "https://other.example/RPC2", "seed").value, "seed @ other.example/RPC2", otherScope)
    private val rows = listOf(
        RemoteTransfer("1".repeat(40), "Big Archive", 900, 900, true, "/d", 0, 10),
        RemoteTransfer("2".repeat(40), "Small Episode", 10, 5, false, "/d", null, null))

    private val commands = ArrayList<Pair<String, Boolean>>()
    private var settles = 0
    private val gate = CombinedServerLifecycle(begin = { true }, settled = { settles++ },
        setStarted = { hash, on -> commands += hash to on; io.github.gamesbyian.chunks.rpc.LifecycleOutcome.Verified(on) })

    private fun show(stale: Boolean = false, observedAtEpochMillis: Long = System.currentTimeMillis()) = rule.setContent {
        ChunksTheme { Surface(color = ChunksColors.Background) {
            CombinedTransfersScreen(
                server = CombinedServerSource(aliasScope, rows, stale = stale, fullView = true,
                    observedAtEpochMillis = observedAtEpochMillis, lifecycle = gate),
                savedProfiles = listOf(other), onOpenPhone = {}, onOpenServer = {}, onBack = {})
        } }
    }

    @Test fun detailFocusResolvesExactServerAndHashWithoutLifecycleCommands() {
        show()
        val target = "Details for Big Archive on " + serverEnvironmentLabel(aliasScope)
        rule.onNodeWithContentDescription(target).performScrollTo().performClick()
        rule.onNodeWithText("Transfer details").assertExists()
        rule.onNodeWithText("Environment: " + serverEnvironmentLabel(aliasScope)).assertExists()
        rule.onNodeWithText("Exact infohash: " + "1".repeat(40)).assertExists()
        assertTrue("read-only details must not issue any lifecycle commands", commands.isEmpty())
    }

    @Test fun corruptSavedDetailIdentityCannotResolveOrGuessAnEnvironment() {
        assertNull(restoreCombinedDetailTarget(null))
        assertNull(restoreCombinedDetailTarget(emptyList()))
        assertNull(restoreCombinedDetailTarget(listOf("server-one")))
        assertNull(restoreCombinedDetailTarget(listOf("server-one", "1".repeat(40), "extra")))
        assertNull(restoreCombinedDetailTarget(listOf("server-one", "garbage")))
        val exact = restoreCombinedDetailTarget(listOf("server-one", "A".repeat(40)))
        assertEquals("server-one", exact?.environment?.value)
        assertEquals("a".repeat(40), exact?.infoHash)
    }

    @Test fun showsEveryEnvironmentHonestlyWithoutStartingThePhoneEngine() {
        show()
        rule.onNodeWithText("All environments").assertExists()
        // Lazy rows below the fold are composed only after scrolling to them.
        fun seen(text: String) {
            rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction())
                .performScrollToNode(androidx.compose.ui.test.hasText(text))
            rule.onNodeWithText(text).assertExists()
        }
        seen("On this phone: Engine not running, open On this phone · 0 transfers")
        seen("Server · seed @ other.example/RPC2: Not connected · 0 transfers")
        seen(serverEnvironmentLabel(aliasScope) + ": Live · 2 transfers")
        seen("Showing 2 · some environments are missing or out of date")
        seen("Big Archive")
        seen("Started · 100% · 900 B · ↓ 0 B/s · ↑ 10 B/s")
        seen("Small Episode")
        seen("Stopped · 50% · 10 B · ↓ not reported · ↑ not reported")
        rule.onRoot().captureRoboImage("build/chunks-visual/combined-all.png")
        // A read-only view must not bring up the phone engine for a remote-only user.
        assertNull(LocalEngineHolder.current())
    }

    @Test fun sortDirectionScopeAndGroupingPersistAcrossReopen() {
        show()
        rule.onNodeWithText("Size").performClick()
        rule.onNodeWithContentDescription("Sorted by Size, Largest first. Reverse order").performClick()
        rule.onNodeWithContentDescription("Sorted by Size, Smallest first. Reverse order").assertExists()
        // Only the connected server: phone and the offline profile drop out of the status lines.
        rule.onAllNodesWithText(serverEnvironmentLabel(aliasScope))[0].performClick()
        rule.onNodeWithText("On this phone: Engine not running, open On this phone · 0 transfers").assertDoesNotExist()
        rule.onNodeWithText("Showing 2").assertExists()
        rule.onNodeWithText("Group by environment").performScrollTo()
        rule.onNodeWithContentDescription("Group by environment").assertExists()
        rule.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        rule.onNodeWithText(serverEnvironmentLabel(aliasScope) + " · 2 shown").assertExists()
        val saved = CombinedViewPreferences(context).load()
        assertEquals(UnifiedSortKey.SIZE, saved.sortKey)
        assertEquals(UnifiedSortDirection.ASCENDING, saved.direction)
        assertTrue(saved.scope is EnvironmentViewScope.Only)
        assertEquals(1, (saved.scope as EnvironmentViewScope.Only).ids.size)
        assertTrue(saved.groupByEnvironment)
    }

    @Test fun combinedFilterIsAppliedToUnionAndPersistsForNextOpening() {
        show()
        rule.onNodeWithText("Filter transfers").assertExists()
        rule.onNodeWithText("Stopped").performClick()
        rule.onNodeWithText("Small Episode").assertExists()
        rule.onNodeWithText("Big Archive").assertDoesNotExist()
        rule.onNodeWithText("Showing 1", substring = true).assertExists()
        assertEquals(io.github.gamesbyian.chunks.model.UnifiedTransferFilter.STOPPED,
            CombinedViewPreferences(context).load().filter)
        rule.onNodeWithText("All transfers").performClick()
        rule.onNodeWithText("Big Archive").assertExists()
        assertEquals(io.github.gamesbyian.chunks.model.UnifiedTransferFilter.ALL,
            CombinedViewPreferences(context).load().filter)
    }

    @Test fun staleServerListIsLabelledAsLastKnown() {
        show(stale = true)
        rule.onNodeWithText(serverEnvironmentLabel(aliasScope) + ": Out of date, showing last list · 2 transfers")
            .assertExists()
    }

    @Test fun oldSignedInServerRowsRemainVisibleButAreNotLiveOrActionable() {
        show(observedAtEpochMillis = System.currentTimeMillis() -
            io.github.gamesbyian.chunks.model.EnvironmentSnapshots.SERVER_INVENTORY_FRESH_MS - 1)
        rule.onNodeWithText(serverEnvironmentLabel(aliasScope) + ": Out of date, showing last list · 2 transfers")
            .assertExists()
        rule.onNodeWithText("Select…").performClick()
        rule.onNodeWithContentDescription("Select Big Archive on " + serverEnvironmentLabel(aliasScope))
            .performScrollTo().performClick()
        rule.onNodeWithText("1 selected").performScrollTo()
        rule.onNodeWithText("Stop…").performClick()
        rule.onNodeWithText("Stop selected").performClick()
        rule.onNodeWithText(serverEnvironmentLabel(aliasScope) +
            ": 1 not attempted (Transfer list is out of date; refresh first).").assertExists()
        assertTrue(commands.isEmpty())
        assertEquals("An expired inventory must never take the server lease", 0, settles)
    }

    @Test fun selectedStopIsPreviewedPerEnvironmentAndRunsOnlyOnThatServer() {
        show()
        rule.onNodeWithText("Select…").performClick()
        rule.onNodeWithContentDescription("Select Big Archive on " + serverEnvironmentLabel(aliasScope))
            .performScrollTo().performClick()
        rule.onNodeWithText("1 selected").performScrollTo().assertExists()
        rule.onNodeWithText("Stop…").performClick()
        rule.onNodeWithText("Stop 1 selected", substring = true).assertExists()
        rule.onNodeWithText(serverEnvironmentLabel(aliasScope) + ": 1", substring = true).assertExists()
        assertTrue("nothing runs before confirmation", commands.isEmpty())
        rule.onNodeWithText("Stop selected").performClick()
        rule.waitUntil(5_000) { settles == 1 }
        rule.onNodeWithText(serverEnvironmentLabel(aliasScope) + ": 1 confirmed stopped.").assertExists()
        assertEquals(listOf("1".repeat(40) to false), commands)
        rule.onRoot().captureRoboImage("build/chunks-visual/combined-batch-result.png")
    }

    @Test fun oldAccountBatchConfirmationCannotBeReusedForSameHashOnAnotherAccount() {
        CombinedViewPreferences(context).save(io.github.gamesbyian.chunks.model.UnifiedTransferQuery())
        val sessionA = Any()
        val sessionB = Any()
        val sourceA = CombinedServerSource(aliasScope, rows, stale = false, fullView = true,
            observedAtEpochMillis = System.currentTimeMillis(), lifecycle = gate,
            authenticatedSession = sessionA)
        val bCommands = mutableListOf<Pair<String, Boolean>>()
        var bSettles = 0
        val gateB = CombinedServerLifecycle(begin = { true }, settled = { bSettles++ },
            setStarted = { hash, on ->
                bCommands += hash to on
                io.github.gamesbyian.chunks.rpc.LifecycleOutcome.Verified(on)
            })
        var active by mutableStateOf(sourceA)
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                CombinedTransfersScreen(server = active, savedProfiles = emptyList(),
                    onOpenPhone = {}, onOpenServer = {}, onBack = {})
            } }
        }
        rule.onNodeWithText("Select…").performClick()
        rule.onNodeWithContentDescription("Select Big Archive on " + serverEnvironmentLabel(aliasScope))
            .performScrollTo().performClick()
        rule.onNodeWithText("Stop…").performScrollTo().performClick()
        rule.onNodeWithText("Stop selected").assertExists()
        assertTrue(commands.isEmpty())
        rule.runOnIdle {
            active = CombinedServerSource(otherScope, rows, stale = false, fullView = true,
                observedAtEpochMillis = System.currentTimeMillis(), lifecycle = gateB,
                authenticatedSession = sessionB)
        }
        rule.onNodeWithText("Stop selected").assertDoesNotExist()
        rule.onNodeWithText("1 selected").assertDoesNotExist()
        rule.onNodeWithText("Select…").assertExists()
        assertTrue("No command may follow an account switch without a new review", commands.isEmpty())
        assertTrue(bCommands.isEmpty())

        // Reconnect to the same account also retires the new session's
        // selection even though both its hash and authenticated scope match.
        rule.onNodeWithText("Select…").performClick()
        rule.onNodeWithContentDescription("Select Big Archive on " + serverEnvironmentLabel(otherScope))
            .performScrollTo().performClick()
        rule.onNodeWithText("Stop…").performScrollTo().performClick()
        rule.onNodeWithText("Stop selected").assertExists()
        rule.runOnIdle { active = active.copy(authenticatedSession = Any()) }
        rule.onNodeWithText("Stop selected").assertDoesNotExist()
        rule.onNodeWithText("Select…").assertExists()
        assertEquals(0, bSettles)
        assertTrue(bCommands.isEmpty())
    }

    @Test fun changedSavedAccountScopeRetiresPendingServerSelection() {
        CombinedViewPreferences(context).save(io.github.gamesbyian.chunks.model.UnifiedTransferQuery())
        var profile by mutableStateOf(other)
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                CombinedTransfersScreen(
                    server = CombinedServerSource(aliasScope, rows, stale = false, fullView = true,
                        observedAtEpochMillis = System.currentTimeMillis(), lifecycle = gate,
                        authenticatedSession = "live-session"),
                    savedProfiles = listOf(profile),
                    onOpenPhone = {}, onOpenServer = {}, onBack = {})
            } }
        }
        rule.onNodeWithText("Select…").performClick()
        rule.onNodeWithContentDescription("Select Big Archive on " + serverEnvironmentLabel(aliasScope))
            .performScrollTo().performClick()
        rule.onNodeWithText("Stop…").performScrollTo().performClick()
        rule.onNodeWithText("Stop selected").assertExists()
        rule.runOnIdle {
            // Same saved roster slot, different authenticated endpoint/account.
            profile = profile.copy(aliasScope = "https://rotated.example/RPC2\u0000replacement")
        }
        rule.onNodeWithText("Stop selected").assertDoesNotExist()
        rule.onNodeWithText("1 selected").assertDoesNotExist()
        assertTrue(commands.isEmpty())
    }

    @Test fun environmentColourChoicePersistsAndRestores() {
        show()
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("Environment colours…"))
        rule.onNodeWithText("Environment colours…").performClick()
        val serverId = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverId(
            aliasScope.substringBefore('\u0000'), "ian")
        val stored = EnvironmentColourStore(context)
        rule.waitUntil(5_000) { stored.load().hueOf(serverId) != null }
        val automatic = stored.load().hueOf(serverId)
        assertNotNull(automatic)
        assertNotEquals("phone and server differ automatically", automatic,
            EnvironmentColourStore(context).load().hueOf(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.PHONE))
        rule.onNodeWithContentDescription(serverEnvironmentLabel(aliasScope) + " colour Pink")
            .performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitUntil(5_000) {
            stored.load().hueOf(serverId) == io.github.gamesbyian.chunks.model.EnvironmentHue.PINK
        }
        rule.waitForIdle()
        assertEquals(io.github.gamesbyian.chunks.model.EnvironmentHue.PINK,
            stored.load().hueOf(serverId))
        rule.onRoot().captureRoboImage("build/chunks-visual/combined-colours.png")
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("Restore automatic colour"))
        rule.onNodeWithText("Restore automatic colour").performClick()
        rule.waitUntil(5_000) { stored.load().hueOf(serverId) == automatic }
        assertEquals(automatic, stored.load().hueOf(serverId))
    }
}
