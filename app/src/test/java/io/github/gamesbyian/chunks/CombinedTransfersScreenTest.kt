package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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

    // Wrapped scope/filter/sort groups can push rows below the first lazy viewport.
    // Reveal actual list items before tapping; never depend on initial composition.
    // The saved view preference loads off the main thread (not an idling
    // resource) and can recompose the list mid-scroll (CI foundation
    // 41888303641 attempt 1). Retry the scroll until the list settles; the
    // assertions that follow are unchanged.
    private fun reveal(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        rule.waitUntil(10_000) {
            runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(matcher) }.isSuccess
        }
    }
    private fun revealText(text: String) = reveal(hasText(text, substring = true))
    private fun revealDescription(description: String) = reveal(hasContentDescription(description))

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
        revealDescription(target)
        rule.onNodeWithContentDescription(target).performClick()
        rule.onNodeWithText("Transfer details").assertExists()
        rule.onNodeWithText("Environment: " + serverEnvironmentLabel(aliasScope)).assertExists()
        rule.onNodeWithText("Exact infohash:", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Show technical details").performScrollTo().performClick()
        rule.onNodeWithText("Exact infohash: " + "1".repeat(40)).performScrollTo().assertExists()
        assertTrue("read-only details must not issue any lifecycle commands", commands.isEmpty())
    }

    @Test fun compactRowsKeepExactScopedDetailsWhileExtraActionsNeedDisclosure() {
        show()
        val label = serverEnvironmentLabel(aliasScope)
        val more = "More actions for Big Archive on " + label
        revealDescription(more)
        rule.onNodeWithContentDescription(more).assertExists()
        rule.onNodeWithText("Open in its list").assertDoesNotExist()
        // Disclosure changes only this exact source/hash row. No Start/Stop RPC.
        rule.onNodeWithContentDescription(more).performClick()
        rule.onNodeWithText("Open in its list").assertExists()
        rule.onNodeWithContentDescription(
            "Hide extra actions for Big Archive on " + label).performClick()
        rule.onNodeWithText("Open in its list").assertDoesNotExist()
        rule.onNodeWithContentDescription("Details for Big Archive on " + label).assertExists()
        assertTrue("Read-only disclosure may never issue lifecycle commands", commands.isEmpty())
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
        // Ordinary list presentation is compact; source-by-source proof is available on request.
        rule.onNodeWithText("On this phone: Engine not running", substring = true).assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/chunks-visual/combined-compact-first-viewport.png")
        revealText("Environment status")
        rule.onNodeWithText("Environment status", substring = true).performClick()
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
        seen("Sharing · 100% · 900 B · ↓ 0 B/s · ↑ 10 B/s")
        seen("Small Episode")
        seen("Stopped · 50% · 10 B · ↓ not reported · ↑ not reported")
        rule.onRoot().captureRoboImage("build/chunks-visual/combined-all.png")
        // A read-only view must not bring up the phone engine for a remote-only user.
        assertNull(LocalEngineHolder.current())
    }

    @Test fun sortDirectionScopeAndGroupingPersistAcrossReopen() {
        show()
        revealText("Change sort & grouping")
        rule.onNodeWithText("Change sort & grouping", substring = true).performClick()
        revealText("Size")
        rule.onNodeWithText("Size").performClick()
        // Isolate selection persistence from the subsequent reverse click:
        // failing here means Size itself was not committed, while failure
        // below isolates the reverse action or its saved readback.
        rule.waitUntil(10_000) {
            val saved = CombinedViewPreferences(context).load()
            saved.sortKey == UnifiedSortKey.SIZE &&
                saved.direction == UnifiedSortDirection.DESCENDING
        }
        // Expanded Add controls move Reverse below the viewport. Deliver a real visible
        // pointer click rather than silently tapping an off-screen lazy node.
        revealDescription("Sorted by Size, Largest first. Reverse order")
        rule.onNodeWithContentDescription("Sorted by Size, Largest first. Reverse order")
            .assertIsDisplayed().performClick()
        // Click delivery and recomposition may finish in separate Robolectric
        // frames. Observe the *actual committed preference* with a bounded
        // deadline, rather than equating scheduler idleness with persistence.
        rule.waitUntil(10_000) {
            CombinedViewPreferences(context).load().direction == UnifiedSortDirection.ASCENDING
        }
        // The persisted sort choice is the source of truth, including after
        // lazy row recycling. The compact summary must disclose the new order.
        assertEquals(UnifiedSortDirection.ASCENDING, CombinedViewPreferences(context).load().direction)
        // Both the direction control and the compact summary include "Smallest first".
        // Require the exact visible summary to avoid an ambiguous multiple-node selector,
        // while preserving independent persisted ASCENDING readback above.
        val directionSummary = "Hide sort & grouping · Size · Smallest first · Together"
        revealText(directionSummary)
        rule.onNodeWithText(directionSummary).assertExists()
        // Disclose the single selected server; the compact summary always identifies active scope.
        revealText("Choose environments & filters")
        rule.onNodeWithText("Choose environments & filters", substring = true).performClick()
        // The view chip shows the compact unique origin; the group heading below
        // retains the exact address-bearing label.
        revealText(compactServerLabel(aliasScope))
        rule.onAllNodesWithText(compactServerLabel(aliasScope))[0].performClick()
        rule.onNodeWithText("On this phone: Engine not running, open On this phone · 0 transfers").assertDoesNotExist()
        revealText("Showing 2")
        rule.onNodeWithText("Showing 2").assertExists()
        revealText("Group by environment")
        rule.onNodeWithContentDescription("Group by environment").assertExists()
        rule.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        revealText(serverEnvironmentLabel(aliasScope) + " · 2 shown")
        rule.onNodeWithText(serverEnvironmentLabel(aliasScope) + " · 2 shown").assertExists()
        val saved = CombinedViewPreferences(context).load()
        assertEquals(UnifiedSortKey.SIZE, saved.sortKey)
        assertEquals(UnifiedSortDirection.ASCENDING, saved.direction)
        assertTrue(saved.scope is EnvironmentViewScope.Only)
        assertEquals(1, (saved.scope as EnvironmentViewScope.Only).ids.size)
        assertTrue(saved.groupByEnvironment)
    }

    @Test fun clearSearchRestoresAllMatchesWithoutChangingSelectedEnvironments() {
        show()
        revealText("Search names or hashes")
        rule.onNodeWithText("Search names or hashes").performTextInput("Small Episode")
        rule.onNodeWithText("Showing 1 of 2", substring = true).assertExists()
        rule.onNodeWithText("New downloads may not match this search.", substring = true)
            .assertExists()
        // This 411dp Robolectric viewport has 16dp list padding on each side:
        // the field itself is 379dp and deliberately chooses the compact
        // TEXT-labelled Clear search action above the input (not a trailing
        // icon contentDescription). On a wider layout the icon is used. Both
        // variants must expose an accessible, 48dp-class real click target.
        val clearAction = hasText("Clear search") or hasContentDescription("Clear search")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(clearAction)
        rule.onNode(clearAction).assertIsDisplayed().performClick()
        rule.onNodeWithText("Showing 2", substring = true).assertExists()
        rule.onNodeWithText("New downloads may not match this search.", substring = true)
            .assertDoesNotExist()
        assertTrue("Viewing and clearing a search must issue no server lifecycle commands",
            commands.isEmpty())
    }

    @Test fun emptySearchHasOneTapRecoveryWithoutMutatingOrResettingSort() {
        CombinedViewPreferences(context).save(io.github.gamesbyian.chunks.model.UnifiedTransferQuery(
            sortKey = UnifiedSortKey.SIZE, direction = UnifiedSortDirection.ASCENDING))
        show()
        rule.onNodeWithText("Search names or hashes").performTextInput("not-in-any-transfer")
        rule.onNodeWithText("Showing 0", substring = true).assertExists()
        revealText("New downloads may not match this search.")
        rule.onNodeWithText("New downloads may not match this search.", substring = true)
            .assertExists()
        revealText("Show all transfers")
        rule.onNodeWithText("Show all transfers").performClick()
        rule.onNodeWithText("Showing 2", substring = true).assertExists()
        // Rows below the recovery button are lazy and may not be composed yet.
        val viewport = rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction())
        viewport.performScrollToNode(androidx.compose.ui.test.hasText("Big Archive"))
        revealText("Big Archive")
        rule.onNodeWithText("Big Archive").assertExists()
        viewport.performScrollToNode(androidx.compose.ui.test.hasText("Small Episode"))
        revealText("Small Episode")
        rule.onNodeWithText("Small Episode").assertExists()
        val recovered = CombinedViewPreferences(context).load()
        assertEquals(io.github.gamesbyian.chunks.model.UnifiedTransferFilter.ALL, recovered.filter)
        assertTrue(recovered.scope is EnvironmentViewScope.All)
        assertEquals(UnifiedSortKey.SIZE, recovered.sortKey)
        assertEquals(UnifiedSortDirection.ASCENDING, recovered.direction)
        assertTrue("View-only recovery must not issue Start/Stop", commands.isEmpty())
    }

    @Test fun entirelyHiddenFilteredSearchExplainsWhyAddMayNotAppear() {
        show()
        revealText("Choose environments & filters")
        rule.onNodeWithText("Choose environments & filters", substring = true).performClick()
        revealText("Stopped")
        rule.onNodeWithText("Stopped").performClick()
        revealText("Search names or hashes")
        rule.onNodeWithText("Search names or hashes").performTextInput("Big Archive")
        revealText("Showing 0")
        rule.onNodeWithText("Showing 0", substring = true).assertExists()
        revealText("New downloads may be hidden by this view.")
        rule.onNodeWithText("New downloads may be hidden by this view.", substring = true)
            .assertExists()
        revealText("Show all transfers")
        rule.onNodeWithText("Show all transfers").performClick()
        revealText("Showing 2")
        rule.onNodeWithText("Showing 2", substring = true).assertExists()
        assertTrue("Recovering an empty view never starts or stops anything", commands.isEmpty())
    }

    @Test fun combinedFilterIsAppliedToUnionAndPersistsForNextOpening() {
        show()
        revealText("Choose environments & filters")
        rule.onNodeWithText("Choose environments & filters", substring = true).performClick()
        rule.onNodeWithText("Filter transfers").assertExists()
        rule.onNodeWithText("Stopped").performClick()
        revealText("Small Episode")
        rule.onNodeWithText("Small Episode").assertExists()
        rule.onNodeWithText("Big Archive").assertDoesNotExist()
        revealText("Showing 1")
        rule.onNodeWithText("Showing 1", substring = true).assertExists()
        revealText("New downloads may be hidden by this view.")
        rule.onNodeWithText("New downloads may be hidden by this view.", substring = true)
            .assertExists()
        assertEquals(io.github.gamesbyian.chunks.model.UnifiedTransferFilter.STOPPED,
            CombinedViewPreferences(context).load().filter)
        revealText("All transfers")
        rule.onNodeWithText("All transfers").performClick()
        revealText("Big Archive")
        rule.onNodeWithText("Big Archive").assertExists()
        rule.onNodeWithText("New downloads may be hidden by this view.", substring = true)
            .assertDoesNotExist()
        assertEquals(io.github.gamesbyian.chunks.model.UnifiedTransferFilter.ALL,
            CombinedViewPreferences(context).load().filter)
    }

    @Test fun staleServerListIsLabelledAsLastKnown() {
        show(stale = true)
        revealText("Environment status")
        rule.onNodeWithText("Environment status", substring = true).performClick()
        // Expanded environment rows live in a LazyColumn and may be outside
        // the composed viewport. Reveal the exact row before asserting it.
        val staleLabel = serverEnvironmentLabel(aliasScope) +
            ": Out of date, showing last list · 2 transfers"
        revealText(staleLabel)
        rule.onNodeWithText(staleLabel).assertExists()
    }

    @Test fun oldSignedInServerRowsRemainVisibleButAreNotLiveOrActionable() {
        show(observedAtEpochMillis = System.currentTimeMillis() -
            io.github.gamesbyian.chunks.model.EnvironmentSnapshots.SERVER_INVENTORY_FRESH_MS - 1)
        revealText("Environment status")
        rule.onNodeWithText("Environment status", substring = true).performClick()
        // Expanded environment rows live in a LazyColumn and may be outside
        // the composed viewport. Reveal the exact row before asserting it.
        val staleLabel = serverEnvironmentLabel(aliasScope) +
            ": Out of date, showing last list · 2 transfers"
        revealText(staleLabel)
        rule.onNodeWithText(staleLabel).assertExists()
        revealText("Select…")
        rule.onNodeWithText("Select…").performClick()
        val selectRow = "Select Big Archive on " + serverEnvironmentLabel(aliasScope)
        revealDescription(selectRow)
        rule.onNodeWithContentDescription(selectRow).performClick()
        revealText("1 selected")
        rule.onNodeWithText("1 selected").performScrollTo()
        revealText("Stop…")
        rule.onNodeWithText("Stop…").performClick()
        revealText("Stop selected")
        rule.onNodeWithText("Stop selected").performClick()
        rule.onNodeWithText(serverEnvironmentLabel(aliasScope) +
            ": 1 not attempted (Transfer list is out of date; refresh first).").assertExists()
        assertTrue(commands.isEmpty())
        assertEquals("An expired inventory must never take the server lease", 0, settles)
    }

    @Test fun selectedStopIsPreviewedPerEnvironmentAndRunsOnlyOnThatServer() {
        show()
        revealText("Select…")
        rule.onNodeWithText("Select…").performClick()
        val selectRow = "Select Big Archive on " + serverEnvironmentLabel(aliasScope)
        revealDescription(selectRow)
        rule.onNodeWithContentDescription(selectRow).performClick()
        revealText("1 selected")
        rule.onNodeWithText("1 selected").performScrollTo().assertExists()
        revealText("Stop…")
        rule.onNodeWithText("Stop…").performClick()
        revealText("Stop 1 selected")
        rule.onNodeWithText("Stop 1 selected", substring = true).assertExists()
        rule.onNodeWithText(serverEnvironmentLabel(aliasScope) + ": 1", substring = true).assertExists()
        assertTrue("nothing runs before confirmation", commands.isEmpty())
        revealText("Stop selected")
        rule.onNodeWithText("Stop selected").performClick()
        rule.waitUntil(5_000) { settles == 1 }
        revealText(serverEnvironmentLabel(aliasScope) + ": 1 confirmed stopped.")
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
        revealText("Select…")
        rule.onNodeWithText("Select…").performClick()
        val selectRow = "Select Big Archive on " + serverEnvironmentLabel(aliasScope)
        revealDescription(selectRow)
        rule.onNodeWithContentDescription(selectRow).performClick()
        revealText("Stop…")
        rule.onNodeWithText("Stop…").performClick()
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
        revealText("Select…")
        rule.onNodeWithText("Select…").performClick()
        val otherRow = "Select Big Archive on " + serverEnvironmentLabel(otherScope)
        revealDescription(otherRow)
        rule.onNodeWithContentDescription(otherRow).performClick()
        revealText("Stop…")
        rule.onNodeWithText("Stop…").performClick()
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
        revealText("Select…")
        rule.onNodeWithText("Select…").performClick()
        val selectRow = "Select Big Archive on " + serverEnvironmentLabel(aliasScope)
        revealDescription(selectRow)
        rule.onNodeWithContentDescription(selectRow).performClick()
        revealText("Stop…")
        rule.onNodeWithText("Stop…").performClick()
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
