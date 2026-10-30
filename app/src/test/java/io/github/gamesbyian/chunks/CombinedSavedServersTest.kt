package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger
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
    private val calls = AtomicInteger(0)

    /**
     * Identity changes may be shown as disconnected, privacy-locked or
     * reconnect-required. This test protects the invariant, not one wording:
     * exactly this saved slot has zero rows and may never claim Live.
     */
    private fun hiddenSavedSlot(name: String): Boolean =
        rule.onAllNodes(
            hasText(name + ":", substring = true) and
                hasText("· 0 transfers", substring = true)
        ).fetchSemanticsNodes().isNotEmpty() &&
            rule.onAllNodesWithText(name + ": Live", substring = true)
                .fetchSemanticsNodes().isEmpty()

    private fun show(profiles: List<CombinedSavedProfile>) = rule.setContent {
        ChunksTheme { Surface(color = ChunksColors.Background) {
            CombinedTransfersScreen(server = null, savedProfiles = profiles, onOpenPhone = {},
                onOpenServer = null, onBack = {})
        } }
    }

    @Test fun loadsOtherSavedServersOnlyWhenAskedAndReportsFailures() {
        show(listOf(
            CombinedSavedProfile(id(scopeA), "ann @ a.example/RPC2", scopeA) {
                calls.incrementAndGet(); listOf(RemoteTransfer("a".repeat(40), "Linux ISO", 100, 100, true, "/d", 0, 5))
            },
            CombinedSavedProfile(id(scopeB), "bob @ b.example/RPC2", scopeB) {
                calls.incrementAndGet(); throw java.net.ConnectException("refused")
            }))
        rule.onNodeWithText("Server · ann @ a.example/RPC2: Not connected · 0 transfers").assertExists()
        assertEquals("nothing is fetched until the user asks", 0, calls.get())
        rule.onNodeWithText("Load other saved servers (read-only)").performScrollTo().performClick()
        // Both fetches starting is not both results applied: the listings land
        // from a background coroutine that waitForIdle does not wait for.
        rule.waitUntil(5_000) {
            calls.get() == 2 &&
                rule.onAllNodesWithText("Server · ann @ a.example/RPC2: Live · 1 transfers")
                    .fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText("Server · bob @ b.example/RPC2: Could not load (connection refused) · 0 transfers")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Linux ISO").performScrollTo().assertExists()
        rule.onNodeWithText("Server · ann @ a.example/RPC2: Live · 1 transfers").assertExists()
        rule.onNodeWithText("Server · bob @ b.example/RPC2: Could not load (connection refused) · 0 transfers")
            .assertExists()
        rule.onNodeWithText("Refresh other saved servers").assertExists()
        // Listed-only rows have no live list to open.
        rule.onNodeWithText("Open in its list").assertDoesNotExist()
        rule.onNodeWithText("Listed only. Sign in to this server to open or change it.").assertExists()
    }

    @Test fun lateReadFromPreviousAccountCannotPublishIntoReconfiguredServerSlot() {
        CombinedViewPreferences(androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()).save(io.github.gamesbyian.chunks.model.UnifiedTransferQuery())
        val sameSlot = id(scopeA)
        val pending = kotlinx.coroutines.CompletableDeferred<List<RemoteTransfer>>()
        val entered = java.util.concurrent.atomic.AtomicInteger(0)
        val oldCompleted = java.util.concurrent.atomic.AtomicInteger(0)
        var current by androidx.compose.runtime.mutableStateOf(
            CombinedSavedProfile(sameSlot, "Account slot", scopeA) {
                entered.incrementAndGet()
                try {
                    pending.await()
                } finally {
                    oldCompleted.incrementAndGet()
                }
            }
        )
        val newReads = java.util.concurrent.atomic.AtomicInteger(0)
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                CombinedTransfersScreen(server = null, savedProfiles = listOf(current),
                    onOpenPhone = {}, onOpenServer = null, onBack = {})
            } }
        }

        val name = "Server · Account slot"
        rule.onNodeWithText("Load other saved servers (read-only)").performScrollTo().performClick()
        rule.waitUntil(5_000) { entered.get() == 1 }
        rule.runOnIdle {
            // Simulate a restored/edited profile retaining the same list slot
            // but switching the exact authenticated account behind it.
            current = CombinedSavedProfile(sameSlot, "Account slot", scopeB) {
                newReads.incrementAndGet()
                listOf(RemoteTransfer("b".repeat(40), "New account ISO",
                    2048, 1024, true, "/new"))
            }
        }
        rule.waitForIdle()
        pending.complete(listOf(RemoteTransfer("a".repeat(40),
            "Old account private payload", 100, 100, true, "/old")))
        rule.waitUntil(5_000) { oldCompleted.get() == 1 }
        // A stale completion may finish on another dispatcher. Even after it
        // has had time to return, the view stays unconnected to account A.
        rule.waitUntil(5_000) {
            hiddenSavedSlot(name)
        }
        rule.onNodeWithText("Old account private payload").assertDoesNotExist()
        rule.onNodeWithText("Load other saved servers (read-only)").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            newReads.get() == 1 &&
                rule.onAllNodesWithText(name + ": Live · 1 transfers")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("New account ISO").performScrollTo().assertExists()
        rule.onNodeWithText("Old account private payload").assertDoesNotExist()
        // Returning to the first identity must not resurrect an earlier reply.
        rule.runOnIdle {
            current = CombinedSavedProfile(sameSlot, "Account slot", scopeA) {
                entered.incrementAndGet()
                emptyList()
            }
        }
        rule.waitUntil(5_000) {
            hiddenSavedSlot(name)
        }
        rule.onNodeWithText("New account ISO").assertDoesNotExist()
        assertEquals("No silent read of the new account", 1, newReads.get())
        assertEquals("No automatic replay of the old request", 1, entered.get())
    }

    @Test fun failedRefreshImmediatelyInvalidatesOldLiveSnapshotWithoutDeletingRows() {
        val row = RemoteTransfer("a".repeat(40), "Cached Linux", 100, 80, true, "/d")
        val good = SavedServerListing(listOf(row), observedAtEpochMillis = 10_000)
        assertFalse(savedListingIsStale(good, now = 10_010))
        assertTrue("A backwards device clock must not leave a listing live indefinitely",
            savedListingIsStale(good, now = 9_999))
        assertTrue(savedListingIsStale(good.copy(loading = true), now = 10_010))
        val failed = good.copy(error = "connection refused")
        assertTrue("A failed fresh retry cannot leave a cached server marked Live",
            savedListingIsStale(failed, now = 10_010))
        val source = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.server(
            io.github.gamesbyian.chunks.model.EnvironmentId(id(scopeA)),
            failed.transfers, connected = true,
            stale = savedListingIsStale(failed, now = 10_010),
            locked = false, fullView = true, observedAtEpochMillis = failed.observedAtEpochMillis)
        assertEquals(io.github.gamesbyian.chunks.model.EnvironmentAvailability.STALE,
            source.availability)
        assertEquals("Cached Linux", source.transfers.single().displayName)
        assertFalse(source.canClaimCompleteLiveInventory)
    }

    @Test fun failedRefreshShowsLastKnownRowsAsStaleInsteadOfLive() {
        var attempts = 0
        val label = "Server · ann @ a.example/RPC2"
        show(listOf(CombinedSavedProfile(id(scopeA), "ann @ a.example/RPC2", scopeA) {
            attempts++
            if (attempts == 1)
                listOf(RemoteTransfer("a".repeat(40), "Cached Linux", 100, 80, true, "/d"))
            else throw java.net.ConnectException("refused")
        }))
        rule.onNodeWithText("Load other saved servers (read-only)").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(label + ": Live · 1 transfers").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Refresh other saved servers").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(label + ": Refresh failed (connection refused), showing last list · 1 transfers")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Cached Linux").performScrollTo().assertExists()
        assertEquals(2, attempts)
    }

    @Test fun profileWithoutVerifiableScopeStaysLockedAndIsNeverFetched() {
        show(listOf(CombinedSavedProfile(id(scopeA), "ann @ a.example/RPC2", aliasScope = null) {
            calls.incrementAndGet(); emptyList()
        }))
        rule.onNodeWithText("Server · ann @ a.example/RPC2: Locked, open it to unlock · 0 transfers").assertExists()
        rule.onNodeWithText("Load other saved servers (read-only)").assertDoesNotExist()
        assertEquals(0, calls.get())
    }
}
