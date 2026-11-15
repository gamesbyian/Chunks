package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Returning to Transfers from another root keeps the last read-only saved
 * listing (count + rows) while the next automatic read is pending, instead of
 * resetting the client chip to `?`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CombinedSavedListingRetentionTest {
    @get:Rule val rule = createComposeRule()
    private val scope = "https://wood.example/RPC2\u0000ann"
    private val id = EnvironmentSnapshots.serverId(scope.substringBefore('\u0000'), "ann").value

    @Test fun lastKnownSavedRowsSurviveNavigationWhileTheNextReadIsPending() {
        CombinedViewPreferences(androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()).save(UnifiedTransferQuery())
        val reads = AtomicInteger()
        val second = kotlinx.coroutines.CompletableDeferred<List<RemoteTransfer>>()
        val row = RemoteTransfer("a".repeat(40), "Wood archive", 100, 100, true, "/d", 0, 5)
        val profile = CombinedSavedProfile(id, "Wood", scope, shortLabel = "Wood") {
            if (reads.incrementAndGet() == 1) listOf(row) else second.await()
        }
        val hoisted = mutableStateOf<Map<EnvironmentId, SavedServerListing>>(emptyMap())
        var mounted by mutableStateOf(true)
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                if (mounted) CombinedTransfersScreen(server = null, savedProfiles = listOf(profile),
                    onOpenLocal = {}, onOpenServer = null, onBack = {},
                    savedListingsState = hoisted)
            } }
        }
        val counted = hasContentDescription("Wood, 1 transfers", substring = true)
        rule.waitUntil(10_000) { rule.onAllNodes(counted).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, reads.get())

        // Leave Transfers (e.g. open Options), then come back.
        rule.runOnIdle { mounted = false }
        rule.waitForIdle()
        rule.runOnIdle { mounted = true }
        rule.waitForIdle()
        // The returning screen starts a fresh read, which is still pending...
        rule.waitUntil(10_000) { reads.get() == 2 }
        // ...yet the chip keeps its last-known count and the row stays listed.
        rule.waitUntil(10_000) { rule.onAllNodes(counted).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Wood archive"))
        assertEquals(1, rule.onAllNodesWithText("Wood archive").fetchSemanticsNodes().size)
        second.complete(listOf(row))
        rule.waitForIdle()
        assertEquals("no extra read from re-entry", 2, reads.get())
    }

    @Test fun cancelledInFlightReadsNeverBlockTheNextRead() {
        val a = EnvironmentId("a"); val b = EnvironmentId("b"); val c = EnvironmentId("c")
        val kept = retainedSavedListings(mapOf(
            a to SavedServerListing(observedAtEpochMillis = 5, loading = true, sourceAliasScope = "s"),
            b to SavedServerListing(loading = true, sourceAliasScope = "s"),
            c to SavedServerListing(error = "connection refused", sourceAliasScope = "s")))
        assertFalse(kept.getValue(a).loading)
        assertEquals(5L, kept.getValue(a).observedAtEpochMillis)
        assertNull("an unanswered first read leaves nothing to show", kept[b])
        assertEquals("connection refused", kept.getValue(c).error)
    }
}
