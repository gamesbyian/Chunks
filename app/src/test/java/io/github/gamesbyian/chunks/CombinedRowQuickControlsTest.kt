package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import io.github.gamesbyian.chunks.model.UnifiedTransferFilter
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Canonical Transfers rows (owner UX): compact by default; tapping a row expands it in place
 * with Start/Stop; Details stays a nested control that never toggles the row. The persistent
 * environment toggle row hides/shows each environment without a Filters step. Commands go
 * through the canonical single-key lifecycle path. JVM evidence only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CombinedRowQuickControlsTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val aliasScope = "https://box.example/rutorrent2/plugins/httprpc/action.php\u0000ian"
    private val otherScope = "https://other.example/RPC2\u0000seed"
    private val other = CombinedSavedProfile(EnvironmentSnapshots.serverId(
        "https://other.example/RPC2", "seed").value, "seed @ other.example/RPC2", otherScope)
    private val rows = listOf(
        RemoteTransfer("1".repeat(40), "Big Archive", 900, 900, true, "/d", 0, 10),
        RemoteTransfer("2".repeat(40), "Small Episode", 10, 5, false, "/d", null, null))
    private val session = RTorrentRemote(RemoteCredentials(aliasScope.substringBefore('\u0000'), "ian", "dummy"))
    private val commands = ArrayList<Pair<String, Boolean>>()
    private val label get() = serverEnvironmentLabel(aliasScope)

    @Before fun reset() {
        CombinedViewPreferences(context).save(io.github.gamesbyian.chunks.model.UnifiedTransferQuery())
    }

    private fun show() = rule.setContent {
        ChunksTheme { Surface(color = ChunksColors.Background) {
            CombinedTransfersScreen(
                server = CombinedServerSource(aliasScope, rows, stale = false, fullView = true,
                    observedAtEpochMillis = System.currentTimeMillis(),
                    lifecycle = CombinedServerLifecycle(begin = { true }, settled = {},
                        setStarted = { hash, on -> commands += hash to on
                            io.github.gamesbyian.chunks.rpc.LifecycleOutcome.Verified(on) },
                        ownerSession = session),
                    authenticatedSession = session),
                savedProfiles = listOf(other), onOpenLocal = {}, onOpenServer = {}, onBack = {})
        } }
    }

    private fun reveal(matcher: androidx.compose.ui.test.SemanticsMatcher) = rule.waitUntil(10_000) {
        runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(matcher) }.isSuccess
    }

    @Test fun tapExpandsInPlaceAndStartStopUseTheCanonicalCommandPath() {
        FakeRTorrent().use { fake ->
            fake.seed("2".repeat(40), "Small Episode", started = false)
            fake.seed("1".repeat(40), "Big Archive", started = true)
            val remote = RTorrentRemote(RemoteCredentials(fake.endpoint, "", ""))
            val scope = remote.addAccountScope()
            val live = androidx.compose.runtime.mutableStateOf(runBlockingList(remote))
            val fakeLabel = serverEnvironmentLabel(scope)
            rule.setContent {
                ChunksTheme { Surface(color = ChunksColors.Background) {
                    CombinedTransfersScreen(
                        server = CombinedServerSource(scope, live.value, stale = false, fullView = true,
                            observedAtEpochMillis = System.currentTimeMillis(),
                            lifecycle = CombinedServerLifecycle(begin = { true },
                                settled = { live.value = runBlockingList(remote) },
                                setStarted = remote::setStarted, probe = remote::probe, ownerSession = remote),
                            authenticatedSession = remote),
                        savedProfiles = emptyList(), onOpenLocal = {}, onOpenServer = {}, onBack = {})
                } }
            }
            val stoppedRow = "Small Episode on $fakeLabel"
            reveal(hasContentDescription(stoppedRow))
            assertTrue("Rows are compact by default",
                rule.onAllNodesWithTag(ChunksSemantics.TRANSFER_ROW_QUICK_CONTROLS).fetchSemanticsNodes().isEmpty())
            rule.onNodeWithContentDescription(stoppedRow).performClick()
            reveal(hasContentDescription("Start Small Episode"))
            rule.onNodeWithContentDescription("Start Small Episode").performClick()
            try { rule.waitUntil(15_000) { fake.jobs.getValue("2".repeat(40)).started } } catch (t: Throwable) {
                val texts = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().flatMap { root ->
                    fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                        listOfNotNull(n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString()) + n.children.flatMap { walk(it) }
                    walk(root) }
                throw AssertionError("DEBUG rpc=" + fake.methods + " texts=" + texts, t)
            }
            assertEquals("Exactly one Start for the exact job, never a retry",
                1, fake.calls.count { it.first == "d.start" && it.second.firstOrNull() == "2".repeat(40) })
            assertTrue("Never touches another job", fake.calls.none {
                it.first in setOf("d.start", "d.stop", "d.close") && it.second.firstOrNull() == "1".repeat(40) })
            // The authoritative refreshed state now offers Stop for that job.
            reveal(hasContentDescription("Stop Small Episode"))
            rule.onNodeWithContentDescription("Stop Small Episode").assertExists()

            // A running transfer offers Stop, not Start, and a second tap collapses it.
            val runningRow = "Big Archive on $fakeLabel"
            reveal(hasContentDescription(runningRow))
            rule.onNodeWithContentDescription(runningRow).performClick()
            reveal(hasContentDescription("Stop Big Archive"))
            rule.onNodeWithContentDescription(runningRow).performClick()
            rule.waitForIdle()
            assertTrue(rule.onAllNodes(hasContentDescription("Stop Big Archive")).fetchSemanticsNodes().isEmpty())
            assertTrue("Expanding and collapsing never commands",
                fake.calls.none { it.first == "d.stop" || it.first == "d.close" })
        }
    }

    private fun runBlockingList(remote: RTorrentRemote) =
        kotlinx.coroutines.runBlocking { remote.listTransfers("main") }

    @Test fun nestedDetailsOpensDetailsWithoutTogglingOrCommanding() {
        show()
        val details = "Details for Big Archive on $label"
        reveal(hasContentDescription(details))
        rule.onNodeWithContentDescription(details).performClick()
        rule.onNodeWithText("Transfer details").assertExists()
        assertTrue(commands.isEmpty())
    }

    @Test fun environmentTogglesShowHideEachEnvironmentPersistAndComposeWithFilters() {
        show()
        reveal(hasTestTag(ChunksSemantics.TRANSFERS_ENVIRONMENT_TOGGLES))
        // All available environments are shown by default.
        rule.onNodeWithContentDescription("Showing $label").assertExists()
        rule.onNodeWithContentDescription("Showing " + LOCAL_ENVIRONMENT_LABEL, substring = true).assertExists()
        // Hide this server: its rows leave the list immediately, no Apply step.
        rule.onNodeWithContentDescription("Showing $label").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasContentDescription("Big Archive on $label")).fetchSemanticsNodes().isEmpty())
        val serverId = requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(aliasScope))
        rule.waitUntil(5_000) {
            (CombinedViewPreferences(context).load().scope as? EnvironmentViewScope.Only)?.ids ==
                setOf(EnvironmentSnapshots.LOCAL, io.github.gamesbyian.chunks.model.EnvironmentId(other.id))
        }
        // A status filter composes with the toggles instead of resetting them.
        reveal(hasText("Filters"))
        rule.onNodeWithText("Filters").performClick()
        reveal(hasText("Started"))
        rule.onNodeWithText("Started").performClick()
        rule.waitUntil(5_000) { CombinedViewPreferences(context).load().filter == UnifiedTransferFilter.STARTED }
        assertTrue(serverId !in ((CombinedViewPreferences(context).load().scope as EnvironmentViewScope.Only).ids))
        // Hide everything: an explicit, explained empty view.
        rule.onNodeWithContentDescription("Showing " + LOCAL_ENVIRONMENT_LABEL, substring = true).performClick()
        rule.onNodeWithContentDescription("Showing Server · " + other.label, substring = true).performClick()
        reveal(hasText("No environments are selected", substring = true))
        assertEquals(EnvironmentViewScope.Only(emptySet()), CombinedViewPreferences(context).load().scope)
        // Showing every environment again returns to dynamic All.
        rule.onNodeWithContentDescription("Hidden: " + LOCAL_ENVIRONMENT_LABEL, substring = true).performClick()
        rule.onNodeWithContentDescription("Hidden: Server · " + other.label, substring = true).performClick()
        rule.onNodeWithContentDescription("Hidden: $label").performClick()
        rule.waitUntil(5_000) { CombinedViewPreferences(context).load().scope == EnvironmentViewScope.All }
        assertTrue(commands.isEmpty())
    }

    @Test fun detailsRenameAppliesASuggestionPersistsAndRestoresWithoutTouchingTheTorrent() {
        val hash = "3".repeat(40)
        val release = "Away We Go 2009 1080p BluRay x264-GeneMige.mkv"
        TransferAliasStore(context).save(aliasScope, hash, null)
        val movieRows = listOf(RemoteTransfer(hash, release, 900, 900, true, "/d", 0, 0))
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                CombinedTransfersScreen(
                    server = CombinedServerSource(aliasScope, movieRows, stale = false, fullView = true,
                        observedAtEpochMillis = System.currentTimeMillis(),
                        lifecycle = CombinedServerLifecycle(begin = { true }, settled = {},
                            setStarted = { h, on -> commands += h to on
                                io.github.gamesbyian.chunks.rpc.LifecycleOutcome.Verified(on) },
                            ownerSession = session),
                        authenticatedSession = session),
                    savedProfiles = emptyList(), onOpenLocal = {}, onOpenServer = {}, onBack = {})
            } }
        }
        val details = "Details for $release on $label"
        reveal(hasContentDescription(details))
        rule.onNodeWithContentDescription(details).performClick()
        rule.onNodeWithText("Change list name").performScrollTo().performClick()
        rule.onNodeWithText("Show name suggestions", substring = true).performScrollTo().performClick()
        rule.onNodeWithContentDescription("Use list-name suggestion: Away We Go (2009)")
            .performScrollTo().performClick()
        rule.onNodeWithText("Save name").performScrollTo().performClick()
        rule.waitUntil(5_000) { TransferAliasStore(context).get(aliasScope, hash) == "Away We Go (2009)" }
        // The list now shows the display name; the original release name stays inspectable.
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Original torrent name: $release"))
            .fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Edit list name").performScrollTo().performClick()
        rule.onNodeWithText("Use original torrent name").performScrollTo().performClick()
        rule.waitUntil(5_000) { TransferAliasStore(context).get(aliasScope, hash) == null }
        assertTrue("A display rename never commands or renames the torrent", commands.isEmpty())
    }
}
