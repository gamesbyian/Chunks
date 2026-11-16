package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Proves, on a local fake rTorrent, the exact locator sequence the protected
 * SeedhostExistingTransferStopStartInstrumentedTest uses: search by exact hash,
 * expand the row (never its nested Details button), then Stop and Start via the
 * expanded row's quick-control button, one RPC each, ending in the original state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ExistingTransferStopStartLocatorTest {
    @get:Rule val rule = createComposeRule()

    /** A clickable whose description STARTS with "<verb> " (the row's quick lifecycle button). */
    private fun lifecycleButton(verb: String) = androidx.compose.ui.test.SemanticsMatcher("description starts with $verb") { node ->
        node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }
            ?.any { it.startsWith("$verb ") } == true &&
            node.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    @Test fun protectedJourneyLocatorsStopAndRestartExactlyTheChosenRow() {
        CombinedViewPreferences(androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()).save(io.github.gamesbyian.chunks.model.UnifiedTransferQuery())
        FakeRTorrent().use { fake ->
            val target = "a".repeat(40)
            val other = "b".repeat(40)
            fake.seed(target, "Seeding Archive", started = true)
            fake.seed(other, "Another Seed", started = true)
            val remote = RTorrentRemote(RemoteCredentials(fake.endpoint, "", ""))
            val live = mutableStateOf(kotlinx.coroutines.runBlocking { remote.listTransfers("main") })
            rule.setContent {
                ChunksTheme { Surface(color = ChunksColors.Background) {
                    CombinedTransfersScreen(
                        server = CombinedServerSource(remote.addAccountScope(), live.value, stale = false, fullView = true,
                            observedAtEpochMillis = System.currentTimeMillis(),
                            lifecycle = CombinedServerLifecycle(begin = { true },
                                settled = { live.value = kotlinx.coroutines.runBlocking { remote.listTransfers("main") } },
                                setStarted = remote::setStarted, probe = remote::probe, ownerSession = remote),
                            authenticatedSession = remote),
                        savedProfiles = emptyList(), onOpenLocal = {}, onOpenServer = {}, onBack = {})
                } }
            }
            rule.onNodeWithText("Search names or hashes").performTextReplacement(target)
            val row = hasContentDescription(" on Server", substring = true) and
                !hasContentDescription("Details for", substring = true)
            rule.waitUntil(10_000) {
                runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(row); true }.getOrDefault(false)
            }
            rule.onAllNodes(row).onFirst().performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodes(lifecycleButton("Stop")).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("Expanding must not open full Details",
                rule.onAllNodes(androidx.compose.ui.test.hasTestTag(ChunksSemantics.DETAILS_SCREEN)).fetchSemanticsNodes().isEmpty())
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(lifecycleButton("Stop"))
            rule.onNode(lifecycleButton("Stop")).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
            rule.waitUntil(15_000) { !fake.jobs.getValue(target).started }
            rule.waitUntil(15_000) {
                runCatching {
                    rule.onNode(hasScrollToIndexAction()).performScrollToNode(lifecycleButton("Start"))
                    rule.onNode(lifecycleButton("Start")).assertIsEnabled(); true
                }.getOrDefault(false)
            }
            rule.onNode(lifecycleButton("Start")).performSemanticsAction(SemanticsActions.OnClick)
            rule.waitUntil(15_000) { fake.jobs.getValue(target).started }
            assertTrue("The other transfer is never touched", fake.calls.none {
                it.first in setOf("d.start", "d.stop", "d.close") && it.second.firstOrNull() == other })
            assertEquals("Exactly one Start for the chosen job", 1,
                fake.calls.count { it.first == "d.start" && it.second.firstOrNull() == target })
        }
    }
}
