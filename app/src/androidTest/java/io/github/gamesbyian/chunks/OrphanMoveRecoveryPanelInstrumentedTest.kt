package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.model.TransferMove
import io.github.gamesbyian.chunks.model.TransferMove.Direction
import io.github.gamesbyian.chunks.model.TransferMove.Observation
import io.github.gamesbyian.chunks.model.TransferMove.Stage
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real installed Compose control. No RPC, payload writer or source Remove is injected. */
@RunWith(AndroidJUnit4::class)
class OrphanMoveRecoveryPanelInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store get() = TransferMoveStore(context)
    private val hash = "ae".repeat(20)
    private val scopeA = "https://a.example/RPC2\u0000account-a"
    private val scopeB = "https://b.example/RPC2\u0000account-b"
    private fun move(alias: String) = TransferMove.start(
        Direction.LOCAL_TO_SERVER, hash, "Orphan episode",
        TransferMoveStore.scopeDigest(alias), "seed.example", sourceReviewToken = "native:123"
    ).copy(stage = Stage.SOURCE_REMOVAL_UNCERTAIN)

    @After fun cleanup() {
        // Delete only these test-created exact records. Never clear all journals.
        listOf(scopeA, scopeB).forEach { alias ->
            store.get(Direction.LOCAL_TO_SERVER, hash, TransferMoveStore.scopeDigest(alias))
                ?.let { store.remove(it) }
        }
    }

    @Test fun noTorrentMutationAndTwoIndependentAbsenceReadsBeforeJournalCleanup() {
        val a = move(scopeA)
        val b = move(scopeB)
        assertTrue(store.save(a))
        assertTrue(store.save(b))
        val reads = AtomicInteger()
        val changed = mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                OrphanMoveRecoveryPanel(
                    direction = Direction.LOCAL_TO_SERVER, serverAliasScope = scopeA,
                    visibleSourceHashes = emptyList(), enabled = true, revision = changed.intValue,
                    observeSource = { reads.incrementAndGet(); Observation.Absent },
                    onChanged = { changed.intValue++ }
                )
            }
        }
        rule.onNodeWithText("Unresolved Move records").assertExists()
        rule.onNodeWithText("Clear only this Move reminder").assertDoesNotExist()
        assertEquals(a, store.get(Direction.LOCAL_TO_SERVER, hash, a.serverScopeDigest))
        rule.onNodeWithText("Check original job (read-only)").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Clear only this Move reminder").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, reads.get())
        assertEquals(a, store.get(Direction.LOCAL_TO_SERVER, hash, a.serverScopeDigest))
        rule.onNodeWithText("Clear only this Move reminder").performClick()
        rule.waitUntil(10_000) {
            store.get(Direction.LOCAL_TO_SERVER, hash, a.serverScopeDigest) == null
        }
        assertEquals("Only the two explicit independent observations are allowed", 2, reads.get())
        assertEquals(b, store.get(Direction.LOCAL_TO_SERVER, hash, b.serverScopeDigest))
        // Storage commit precedes the Compose onChanged callback; waiting for
        // durable absence alone races the UI dispatcher after withContext(IO).
        // Require the UI to observe exactly one completion as a separate gate.
        rule.waitUntil(10_000) { changed.intValue == 1 }
        assertEquals(1, changed.intValue)
        // There is no Remove, Add or Stop/Start handler in this UI.
    }

    @Test fun reappearingOriginalJobPreventsJournalCleanupAndKeepsBothAccounts() {
        val a = move(scopeA)
        val b = move(scopeB)
        assertTrue(store.save(a))
        assertTrue(store.save(b))
        val reads = AtomicInteger()
        rule.setContent {
            MaterialTheme {
                OrphanMoveRecoveryPanel(
                    direction = Direction.LOCAL_TO_SERVER, serverAliasScope = scopeA,
                    visibleSourceHashes = emptyList(), enabled = true, revision = 0,
                    observeSource = { if (reads.incrementAndGet() == 1) Observation.Absent
                        else Observation.Present },
                    onChanged = { error("A reappearing source cannot retire a journal") }
                )
            }
        }
        rule.onNodeWithText("Check original job (read-only)").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Clear only this Move reminder").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Clear only this Move reminder").performClick()
        rule.waitUntil(10_000) { reads.get() == 2 }
        assertEquals(a, store.get(Direction.LOCAL_TO_SERVER, hash, a.serverScopeDigest))
        assertEquals(b, store.get(Direction.LOCAL_TO_SERVER, hash, b.serverScopeDigest))
    }

    @Test fun changingAccountInvalidatesPriorAbsenceConfirmationEvenForSameHash() {
        val a = move(scopeA)
        val b = move(scopeB)
        assertTrue(store.save(a))
        assertTrue(store.save(b))
        val active = mutableStateOf(scopeA)
        rule.setContent {
            MaterialTheme {
                OrphanMoveRecoveryPanel(
                    direction = Direction.LOCAL_TO_SERVER, serverAliasScope = active.value,
                    visibleSourceHashes = emptyList(), enabled = true, revision = 0,
                    observeSource = { Observation.Absent }, onChanged = {}
                )
            }
        }
        rule.onNodeWithText("Check original job (read-only)").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Clear only this Move reminder").fetchSemanticsNodes().isNotEmpty()
        }
        rule.runOnUiThread { active.value = scopeB }
        rule.waitForIdle()
        rule.onNodeWithText("Clear only this Move reminder").assertDoesNotExist()
        assertEquals(a, store.get(Direction.LOCAL_TO_SERVER, hash, a.serverScopeDigest))
        assertEquals(b, store.get(Direction.LOCAL_TO_SERVER, hash, b.serverScopeDigest))
    }
}
