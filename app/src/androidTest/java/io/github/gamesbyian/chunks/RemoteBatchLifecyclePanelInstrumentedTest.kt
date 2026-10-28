package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteBatchLifecyclePanelInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private fun row(c: Char, started: Boolean) =
        RemoteTransfer(c.toString().repeat(40), "Public $c", 10, 0, started, "/data")

    @Test fun confirmedBatchStartHaltsAtUncertainReplyWithoutRetry() {
        val calls = Collections.synchronizedList(ArrayList<String>())
        var leases = 0
        var settled = 0
        rule.setContent {
            MaterialTheme {
                RemoteBatchLifecyclePanel(
                    selected = listOf(row('a', false), row('b', true), row('c', false), row('d', false)),
                    enabled = true,
                    onMutationStarted = { leases++; true },
                    onSettled = { settled++ },
                    performLifecycle = { hash, _ ->
                        calls += hash
                        if (hash.startsWith("c")) LifecycleOutcome.Uncertain(true)
                        else LifecycleOutcome.Verified(true)
                    }
                )
            }
        }
        rule.onNodeWithText("Start 3").performClick()
        assertEquals("Review must precede any command", 0, calls.size)
        rule.onNodeWithText("Confirm Start").performClick()
        rule.waitUntil(5_000) { settled == 1 }
        assertEquals(listOf("a".repeat(40), "c".repeat(40)), calls.toList())
        assertEquals(1, leases)
        rule.onNodeWithText("Halted: one reply was lost", substring = true).assertExists()
        rule.onNodeWithText("1 not attempted", substring = true).assertExists()
    }

    @Test fun refusedLeaseSendsNothingAndDisabledPanelCannotStart() {
        var calls = 0
        rule.setContent {
            MaterialTheme {
                RemoteBatchLifecyclePanel(
                    selected = listOf(row('a', false)), enabled = true,
                    onMutationStarted = { false }, onSettled = {},
                    performLifecycle = { _, _ -> calls++; LifecycleOutcome.Verified(true) })
            }
        }
        rule.onNodeWithText("Start 1").performClick()
        rule.onNodeWithText("Confirm Start").performClick()
        rule.onNodeWithText("Nothing was sent", substring = true).assertExists()
        assertEquals(0, calls)
        rule.onNodeWithText("Stop 0").assertIsNotEnabled()
    }
}
