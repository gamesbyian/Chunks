package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalCommandResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * Recheck is one explicit, 48dp, single-flight action; outcomes are truthful.
 * The engine behaviour itself is proved with real libtorrent in
 * LocalTorrentEngineTest.recheckOfAStoppedJobReadsPiecesAndStaysStoppedUntilAnOrdinaryStart.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class LocalStoppedRecheckControlTest {
    @get:Rule val rule = createComposeRule()

    /** Production's observation pump reopens the shared fence with a fresh readback. */
    @Before fun freshLocalReadback() {
        val fence = LocalMutationFence.revision
        fence.acceptObservation(fence.snapshot(), emptyList<Any>())
    }

    @Test fun oneTapRunsOneCheckAndIsDisabledWhileChecking() {
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        rule.setContent { ChunksTheme { LocalStoppedRecheckControl("a1".repeat(20)) {
            calls.incrementAndGet(); release.await(10, TimeUnit.SECONDS)
            LocalCommandResult(LocalCommandOutcome.VERIFIED, null)
        } } }
        val button = rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_RECHECK)
        button.assertIsEnabled().assertHeightIsAtLeast(48.dp)
        button.performClick()
        rule.waitUntil(5_000) { calls.get() == 1 }
        button.assertIsNotEnabled()
        rule.onNodeWithText("stops again by itself", substring = true).assertExists()
        release.countDown()
        rule.waitUntil(5_000) { runCatching { button.assertIsEnabled() }.isSuccess }
        assertEquals(1, calls.get())
        rule.onNodeWithText("Check finished", substring = true).assertExists()
    }

    @Test fun checkAfterAStoppedAddWaitsForThatAddsChangeLockThenRunsOnce() {
        val fence = LocalMutationFence.revision
        assertTrue("the Add holds the shared lock", fence.beginMutation())
        val hash = "c3".repeat(20)
        val calls = AtomicInteger()
        assertTrue(LocalRecheckRuns.start(hash, awaitFenceMillis = 10_000) {
            calls.incrementAndGet(); LocalCommandResult(LocalCommandOutcome.VERIFIED, null)
        })
        Thread.sleep(400)
        assertEquals("no check while the Add is unsettled", 0, calls.get())
        fence.settledMutation()
        Thread.sleep(300)
        assertEquals("nor before the Add's fresh readback", 0, calls.get())
        fence.acceptObservation(fence.snapshot(), emptyList<Any>())
        val deadline = System.currentTimeMillis() + 5_000
        while (LocalRecheckRuns.running[hash] == true && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, calls.get())
        assertTrue(LocalRecheckRuns.outcomes[hash].orEmpty().startsWith("Check finished"))
        fence.acceptObservation(fence.snapshot(), emptyList<Any>())
    }

    @Test fun aBoundedWaitGivesUpWithoutChecking() {
        val fence = LocalMutationFence.revision
        assertTrue(fence.beginMutation())
        val hash = "d4".repeat(20)
        val calls = AtomicInteger()
        try {
            assertTrue(LocalRecheckRuns.start(hash, awaitFenceMillis = 300) {
                calls.incrementAndGet(); null
            })
            val deadline = System.currentTimeMillis() + 5_000
            while (LocalRecheckRuns.running[hash] == true && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(0, calls.get())
            assertTrue(LocalRecheckRuns.outcomes[hash].orEmpty().contains("Nothing was checked"))
        } finally {
            fence.settledMutation()
            fence.acceptObservation(fence.snapshot(), emptyList<Any>())
        }
    }

    @Test fun outcomeSurvivesDetailsHidingTheControlDuringTheCheck() {
        // Details shows local operations only while stopped; libtorrent
        // unpauses the job for the check, so the control leaves composition.
        val hash = "b2".repeat(20)
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        var shown by mutableStateOf(true)
        val engine: (String) -> LocalCommandResult? = {
            calls.incrementAndGet(); release.await(10, TimeUnit.SECONDS)
            LocalCommandResult(LocalCommandOutcome.VERIFIED, null)
        }
        rule.setContent { ChunksTheme { if (shown) LocalStoppedRecheckControl(hash, engine) } }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_RECHECK).performClick()
        rule.waitUntil(5_000) { calls.get() == 1 }
        shown = false
        rule.waitForIdle()
        release.countDown()
        rule.waitUntil(5_000) { LocalRecheckRuns.running[hash] != true }
        shown = true
        rule.onNodeWithText("Check finished", substring = true).assertExists()
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_RECHECK).assertIsEnabled()
        assertEquals("one run, not restarted by remounting", 1, calls.get())
    }

    @Test fun outcomesAreTruthful() {
        assertTrue(recheckOutcomeText(false, null).contains("Nothing was checked"))
        assertTrue(recheckOutcomeText(true, LocalCommandResult(LocalCommandOutcome.UNCERTAIN, null))
            .contains("still running"))
        assertTrue(recheckOutcomeText(true, LocalCommandResult(LocalCommandOutcome.REFUSED, null))
            .contains("only while it is stopped"))
        assertTrue(recheckOutcomeText(true, null).contains("no longer on this device"))
    }
}
