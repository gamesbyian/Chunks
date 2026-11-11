package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
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

    private fun show(stale: Boolean = false, observedAtEpochMillis: Long = System.currentTimeMillis(),
        authenticatedSession: Any? = null) = rule.setContent {
        ChunksTheme { Surface(color = ChunksColors.Background) {
            CombinedTransfersScreen(
                server = CombinedServerSource(aliasScope, rows, stale = stale, fullView = true,
                    observedAtEpochMillis = observedAtEpochMillis, lifecycle = gate,
                    authenticatedSession = authenticatedSession),
                savedProfiles = listOf(other), onOpenPhone = {}, onOpenServer = {}, onBack = {})
        } }
    }

    @Test fun defaultCombinedTransfersDefersSearchAndSortUntilRequested() {
        show()
        rule.onNodeWithText("Search names or hashes").assertExists()
        rule.onNodeWithText("Change sort & grouping", substring = true).assertDoesNotExist()
        revealText("Filters")
        rule.onNodeWithText("Filters", substring = true).performClick()
        revealText("Search names or hashes")
        rule.onNodeWithText("Change sort & grouping", substring = true).assertExists()
        assertTrue(commands.isEmpty())
    }

    @Test fun freshCanonicalWelcomeRoutesAddAndConnectionsWithoutInventoryChrome() {
        var add = 0
        var connections = 0
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                CombinedTransfersScreen(
                    server = null, savedProfiles = emptyList(),
                    onOpenPhone = {}, onOpenServer = null, onBack = {},
                    onConnections = { connections++ },
                    enableFirstRunWelcome = true,
                    onWelcomeAdd = { add++ },
                    showNavigationFooter = false
                )
            } }
        }
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Welcome to Chunks").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Welcome to Chunks").assertExists()
        rule.onNodeWithText("Search names or hashes").assertDoesNotExist()
        rule.onNodeWithText("All environments").assertDoesNotExist()
        rule.onNodeWithText("Batch & tools").assertDoesNotExist()
        rule.onNodeWithText("Add a torrent").performClick()
        rule.onNodeWithText("Connections").performClick()
        assertEquals(1, add)
        assertEquals(1, connections)
        assertTrue("first-run welcome never writes torrents", commands.isEmpty())
    }

    @Test fun vaultOnlyRememberedRemoteDoesNotMasqueradeAsFreshInstall() {
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                CombinedTransfersScreen(
                    server = null, savedProfiles = emptyList(),
                    onOpenPhone = {}, onOpenServer = null, onBack = {},
                    enableFirstRunWelcome = true,
                    hasRememberedRemoteLogin = true,
                    showNavigationFooter = false
                )
            } }
        }
        // A verified old account may still be in the encrypted vault before
        // its roster becomes readable. Preserve the truthful catalog/offline
        // presentation, even when local engine inventory is genuinely empty.
        rule.onNodeWithText("Welcome to Chunks").assertDoesNotExist()
        rule.onNodeWithText("Search names or hashes").assertExists()
        rule.onNodeWithText("Transfers").assertExists()
        assertTrue("vault-only browse must not issue rTorrent writes", commands.isEmpty())
    }

    @Test fun explicitAllEnvironmentsClearsPriorSavedProfileViewWithoutRpcCommand() {
        val currentId = requireNotNull(io.github.gamesbyian.chunks.model.EnvironmentSnapshots
            .serverIdOfAliasScope(aliasScope)).value
        var pendingFocus by mutableStateOf<String?>(currentId)
        var showAllRevision by androidx.compose.runtime.mutableLongStateOf(0L)
        var consumedRevision by androidx.compose.runtime.mutableLongStateOf(0L)
        var mounted by mutableStateOf(true)
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                if (mounted) CombinedTransfersScreen(
                    server = CombinedServerSource(aliasScope, rows, stale = false,
                        fullView = true, observedAtEpochMillis = System.currentTimeMillis(),
                        lifecycle = gate),
                    savedProfiles = listOf(other),
                    onOpenPhone = {}, onOpenServer = {}, onBack = {},
                    scopeFocusRequest = pendingFocus,
                    onScopeFocusConsumed = { pendingFocus = null },
                    showAllEnvironmentsRevision =
                        showAllRevision.takeIf { it > consumedRevision } ?: 0L,
                    onShowAllEnvironmentsConsumed = { delivered ->
                        consumedRevision = maxOf(consumedRevision, delivered)
                    }
                )
            } }
        }
        revealText("Show all transfers")
        rule.onNodeWithText("Show all transfers").assertExists()
        rule.runOnIdle { showAllRevision++ }
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Show all transfers").fetchSemanticsNodes().isEmpty()
        }
        revealText("Big Archive")
        revealText("Small Episode")
        rule.waitUntil(15_000) { consumedRevision == showAllRevision }
        // Reenter via an exact saved profile after an earlier explicit All tap.
        // The old counter must not replay and defeat this new view-only request.
        rule.runOnIdle { mounted = false }
        rule.waitForIdle()
        rule.runOnIdle {
            pendingFocus = currentId
            mounted = true
        }
        revealText("Show all transfers")
        assertTrue("old All request must stay consumed on remount", consumedRevision == showAllRevision)
        assertTrue("view scope reset can never dispatch an rTorrent mutation", commands.isEmpty())
    }

    @Test fun rootTransfersTapClosesFullDetailsWithoutIssuingLifecycleCommand() {
        var catalogRevision by mutableStateOf(0L)
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                CombinedTransfersScreen(
                    server = CombinedServerSource(aliasScope, rows, stale = false,
                        fullView = true, observedAtEpochMillis = System.currentTimeMillis(),
                        lifecycle = gate),
                    savedProfiles = listOf(other),
                    onOpenPhone = {}, onOpenServer = {}, onBack = {},
                    returnToCatalogRevision = catalogRevision
                )
            } }
        }
        val details = "Details for Big Archive on " + serverEnvironmentLabel(aliasScope)
        revealDescription(details)
        rule.onNodeWithContentDescription(details).performClick()
        rule.onNodeWithText("Transfer details").assertExists()
        rule.runOnIdle { catalogRevision++ }
        rule.waitForIdle()
        rule.onNodeWithText("Transfer details").assertDoesNotExist()
        assertTrue("returning to Transfers is read-only", commands.isEmpty())
    }

    @Test fun detailFocusResolvesExactServerAndHashWithoutLifecycleCommands() {
        show()
        val origin = serverEnvironmentLabel(aliasScope)
        val details = "Details for Big Archive on " + origin
        revealDescription(details)
        rule.onNodeWithContentDescription(details).performClick()
        rule.onNodeWithText("Transfer details").assertExists()
        rule.onNodeWithText("Environment: " + origin).assertExists()
        rule.onNodeWithText("Exact infohash:", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Show technical details").performScrollTo().performClick()
        rule.onNodeWithText("Exact infohash: " + "1".repeat(40)).performScrollTo().assertExists()
        assertTrue("read-only details must not issue lifecycle commands", commands.isEmpty())
    }

    @Test fun compactRowOpensFullDetailsWithoutInlineExpansionOrMutation() {
        show()
        val label = serverEnvironmentLabel(aliasScope)
        val action = "Details for Big Archive on " + label
        revealDescription(action)
        rule.onNodeWithContentDescription(action).assertExists()
        rule.onNodeWithText("All details").assertDoesNotExist()
        rule.onNodeWithText("Open in its list").assertDoesNotExist()
        // The first tap opens the dedicated, exact-account Details destination.
        rule.onNodeWithContentDescription(action).performClick()
        rule.onNodeWithText("Transfer details").assertExists()
        rule.onNodeWithText("Size: 900 B", substring = true).assertExists()
        rule.onNodeWithText("Open " + label + " transfers").assertExists()
        rule.onNodeWithText("Back to transfers").performScrollTo().performClick()
        revealDescription(action)
        rule.onNodeWithContentDescription(action).assertExists()
        rule.onNodeWithText("Transfer details").assertDoesNotExist()
        assertTrue("Details navigation cannot issue lifecycle commands", commands.isEmpty())
    }

    @Test fun liveExactDetailsReviewSelectsOneJobWithoutDispatchingLifecycle() {
        show(authenticatedSession = Any())
        val action = "Details for Big Archive on " + serverEnvironmentLabel(aliasScope)
        revealDescription(action)
        rule.onNodeWithContentDescription(action).performClick()
        rule.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.PaneTitle, "Transfer details")).assertExists()
        rule.onNodeWithText("Review Start / Stop").assertExists()
        assertTrue("Details remains read-only until the user's explicit review", commands.isEmpty())
        rule.onNodeWithText("Review Start / Stop").performScrollTo().performClick()
        revealText("1 selected")
        rule.onNodeWithText("1 selected").assertExists()
        revealText("Start…")
        rule.onNodeWithText("Start…").assertExists()
        assertTrue("Review selection cannot dispatch a lifecycle RPC", commands.isEmpty())
    }

    @Test fun unsignedInDetailsNeverOffersAnAuthenticatedLifecycleReview() {
        show()
        val action = "Details for Big Archive on " + serverEnvironmentLabel(aliasScope)
        revealDescription(action)
        rule.onNodeWithContentDescription(action).performClick()
        rule.onNodeWithText("Review Start / Stop").assertDoesNotExist()
        assertTrue("A read-only saved or disconnected profile must never issue a command",
            commands.isEmpty())
    }

    @Test fun staleOrUnauthenticatedDetailsCannotOfferLifecycleReview() {
        show(stale = true, authenticatedSession = Any())
        val action = "Details for Big Archive on " + serverEnvironmentLabel(aliasScope)
        revealDescription(action)
        rule.onNodeWithContentDescription(action).performClick()
        rule.onNodeWithText("Review Start / Stop").assertDoesNotExist()
        assertTrue(commands.isEmpty())
    }

    @Test fun lifecycleReviewScrollIndexTracksEachOptionalDisclosure() {
        assertEquals(3, combinedBatchItemIndex(false, false, false))
        assertEquals(4, combinedBatchItemIndex(true, false, false))
        assertEquals(6, combinedBatchItemIndex(false, true, false))
        assertEquals(7, combinedBatchItemIndex(false, true, true))
        assertEquals(8, combinedBatchItemIndex(true, true, true))
    }

    @Test fun deviceAndServerScopeShortcutsStayInCanonicalListAndOpenSameDetails() {
        show()
        revealText("Only this device")
        rule.onNodeWithText("Only this device").performClick()
        rule.onNodeWithText("Big Archive").assertDoesNotExist()
        revealText("Viewing:")
        rule.onNodeWithText("Viewing:", substring = true).assertExists()
        revealText("Only connected server")
        rule.onNodeWithText("Only connected server").performClick()
        // Scope change remains visible even when the filter chips are closed.
        revealText("Viewing:")
        rule.onNodeWithText("Viewing:", substring = true).assertExists()
        val action = "Details for Big Archive on " + serverEnvironmentLabel(aliasScope)
        revealDescription(action)
        rule.onNodeWithContentDescription(action).performClick()
        rule.onNodeWithText("Transfer details").assertExists()
        assertTrue("Switching a list scope never executes a lifecycle command", commands.isEmpty())
    }

    @Test fun phoneOnlyCatalogKeepsExplicitRecoveryWhenNativeEngineIsAbsent() {
        // A view filter must never start the phone engine. If there are no
        // rows, provide an explicit entry to the existing device controls.
        var openedDevice = 0
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                CombinedTransfersScreen(
                    server = null, savedProfiles = emptyList(),
                    onOpenPhone = { openedDevice++ }, onOpenServer = null,
                    onBack = {})
            } }
        }
        if (LocalEngineHolder.current() == null) {
            revealText("Open device transfer controls")
            rule.onNodeWithText("Open device transfer controls").performClick()
            assertEquals(1, openedDevice)
        }
        assertTrue("Scope selection or recovery navigation sent no remote commands", commands.isEmpty())
    }

    @Test fun phoneEmptyCopyRequiresAuthoritativeCurrentInventory() {
        assertEquals("No transfers on this device yet. Review a torrent to add one.",
            phoneOnlyEmptyInventoryMessage(true))
        assertEquals("No verified transfer list is available yet. Saved transfers may need " +
            "to be restored in device controls.",
            phoneOnlyEmptyInventoryMessage(false))
    }

    @Test fun phoneOnlyEmptyCatalogOffersReviewWithoutStartingOrGuessingInventory() {
        CombinedViewPreferences(context).save(io.github.gamesbyian.chunks.model.UnifiedTransferQuery())
        assertNull("Fixture must not silently start the phone engine", LocalEngineHolder.current())
        var addReviews = 0
        var phoneControls = 0
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                CombinedTransfersScreen(
                    server = null, savedProfiles = emptyList(),
                    onOpenPhone = { phoneControls++ }, onOpenServer = null,
                    onAddToPhone = { addReviews++ }, onBack = {})
            } }
        }
        revealText("Review a new torrent on this device")
        rule.onNodeWithText("No verified transfer list is available yet.", substring = true)
            .assertExists()
        rule.onNodeWithText("Review a new torrent on this device").performClick()
        assertEquals(1, addReviews)
        assertEquals(0, phoneControls)
        assertNull("Review navigation does not start the native engine", LocalEngineHolder.current())
        assertTrue("Review navigation does not issue lifecycle commands", commands.isEmpty())
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
        seen("On this phone: Engine not running; open transfers on this device · 0 transfers")
        seen("Server · seed @ other.example/RPC2: Not connected · 0 transfers")
        seen(serverEnvironmentLabel(aliasScope) + ": Live · 2 transfers")
        seen("Showing 2 · some environments are missing or out of date")
        seen("Big Archive")
        seen("Sharing · 100% · ↓ 0 B/s · ↑ 10 B/s · Ratio not reported")
        seen("Small Episode")
        seen("Stopped · 50% · ↓ not reported · ↑ not reported · Ratio not reported")
        rule.onRoot().captureRoboImage("build/chunks-visual/combined-all.png")
        // A read-only view must not bring up the phone engine for a remote-only user.
        assertNull(LocalEngineHolder.current())
    }

    @Test fun sortDirectionScopeAndGroupingPersistAcrossReopen() {
        show()
        revealText("Filters")
        rule.onNodeWithText("Filters", substring = true).performClick()
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
        // Refinement is already disclosed; use the existing exact-source scope controls.
        // The view chip shows the compact unique origin; the group heading below
        // retains the exact address-bearing label.
        revealText(compactServerLabel(aliasScope))
        rule.onAllNodesWithText(compactServerLabel(aliasScope))[0].performClick()
        rule.onNodeWithText("On this phone: Engine not running; open transfers on this device · 0 transfers").assertDoesNotExist()
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
        rule.onNodeWithText("Search names or hashes").assertExists()
        revealText("Filters")
        rule.onNodeWithText("Filters", substring = true).performClick()
        revealText("Search names or hashes")
        rule.onNodeWithText("Search names or hashes").performTextInput("Small Episode")
        revealText("Showing 1 of 2")
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
        revealText("Filters")
        rule.onNodeWithText("Filters", substring = true).performClick()
        revealText("Search names or hashes")
        rule.onNodeWithText("Search names or hashes").performTextInput("not-in-any-transfer")
        revealText("Showing 0")
        rule.onNodeWithText("Showing 0", substring = true).assertExists()
        revealText("New downloads may not match this search.")
        rule.onNodeWithText("New downloads may not match this search.", substring = true)
            .assertExists()
        revealText("Show all transfers")
        rule.onNodeWithText("Show all transfers").performClick()
        revealText("Showing 2")
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
        revealText("Filters")
        rule.onNodeWithText("Filters", substring = true).performClick()
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
        revealText("Filters")
        rule.onNodeWithText("Filters", substring = true).performClick()
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
        // The first-fold count is recycled after scrolling. The expanded
        // source diagnostics must themselves explain what is out of date.
        revealText("Some environments are missing or out of date.")
        rule.onNodeWithText("Some environments are missing or out of date.",
            substring = true).assertExists()
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
        revealText("Batch tools")
        rule.onNodeWithText("Batch tools").performClick()
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
        revealText("Batch tools")
        rule.onNodeWithText("Batch tools").performClick()
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
        revealText("Batch tools")
        rule.onNodeWithText("Batch tools").performClick()
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
        revealText("Batch tools")
        rule.onNodeWithText("Batch tools").performClick()
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
        revealText("Environment colours…")
        rule.onNodeWithText("Environment colours…").performClick()
        val serverId = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverId(
            aliasScope.substringBefore('\u0000'), "ian")
        val stored = EnvironmentColourStore(context)
        rule.waitUntil(5_000) { stored.load().hueOf(serverId) != null }
        val automatic = stored.load().hueOf(serverId)
        assertNotNull(automatic)
        assertNotEquals("phone and server differ automatically", automatic,
            EnvironmentColourStore(context).load().hueOf(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.PHONE))
        // A completed preference write does not mean its lazy controls have
        // entered the current viewport. Await the real control before one tap.
        revealDescription(serverEnvironmentLabel(aliasScope) + " colour Pink")
        rule.onNodeWithContentDescription(serverEnvironmentLabel(aliasScope) + " colour Pink")
            .performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitUntil(5_000) {
            stored.load().hueOf(serverId) == io.github.gamesbyian.chunks.model.EnvironmentHue.PINK
        }
        rule.waitForIdle()
        assertEquals(io.github.gamesbyian.chunks.model.EnvironmentHue.PINK,
            stored.load().hueOf(serverId))
        rule.onRoot().captureRoboImage("build/chunks-visual/combined-colours.png")
        revealText("Restore automatic colour")
        rule.onNodeWithText("Restore automatic colour").performClick()
        rule.waitUntil(5_000) { stored.load().hueOf(serverId) == automatic }
        assertEquals(automatic, stored.load().hueOf(serverId))
    }
}
