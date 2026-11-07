package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.model.TransferMove
import io.github.gamesbyian.chunks.model.TransferMove.Observation
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchMoveRemovalPanelInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private fun move(n: Int, name: String) = TransferMove.start(TransferMove.Direction.PHONE_TO_SERVER,
        "%040x".format(n), name, "a".repeat(64), "seed.example",
        sourceReviewToken = "native-session:$n").copy(stage = TransferMove.Stage.TARGET_VERIFIED)

    @Test fun removalNeedsSeparateConfirmationAndKeepsJobsWhoseTargetVanished() {
        val moves = listOf(move(1, "Public one"), move(2, "Public two"))
        val removed = Collections.synchronizedList(mutableListOf<String>())
        val finished = Collections.synchronizedList(mutableListOf<String>())
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BatchMoveRemovalPanel(pending = moves, serverScopeDigest = "a".repeat(64),
                        serverLabel = "seed.example", busy = false,
                        observeTarget = { if (it == moves[0].infoHash) Observation.Present else Observation.Absent },
                        removeSource = { removed += it },
                        observeSource = { if (it in removed) Observation.Absent else Observation.Present },
                        save = { true }, forget = { true }, onFinished = { finished += it })
                }
            }
        }
        rule.onNodeWithText("Remove 2 original phone jobs…").performClick()
        rule.onNodeWithText("Downloaded files stay on the phone", substring = true).assertExists()
        assertTrue(removed.isEmpty())
        rule.onNodeWithText("Remove original jobs").performScrollTo().performClick()
        rule.waitUntil(10_000) { finished.isNotEmpty() }
        assertEquals(listOf(moves[0].infoHash), removed.toList())
        assertTrue(finished.single().contains("1 original phone jobs verified absent"))
    }

    @Test fun cancelledInFlightRemovalStillReleasesSharedMutationLease() {
        val moves = listOf(move(1, "Public one"), move(2, "Public two"))
        val visible = mutableStateOf(true)
        val acquired = AtomicInteger()
        val settled = AtomicInteger()
        val enteredRemoteMutation = AtomicBoolean(false)
        val writes = AtomicInteger()
        rule.setContent {
            MaterialTheme {
                if (visible.value) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        BatchMoveRemovalPanel(
                            pending = moves, serverScopeDigest = "a".repeat(64),
                            serverLabel = "seed.example", busy = false,
                            observeTarget = { Observation.Present },
                            removeSource = {
                                writes.incrementAndGet()
                                enteredRemoteMutation.set(true)
                                kotlinx.coroutines.awaitCancellation()
                            },
                            observeSource = { Observation.Unknown },
                            save = { true }, forget = { true },
                            onFinished = {},
                            onStarted = { acquired.incrementAndGet(); true },
                            onSettled = { settled.incrementAndGet() }
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("Remove 2 original phone jobs…").performClick()
        rule.onNodeWithText("Remove original jobs").performScrollTo().performClick()
        rule.waitUntil(10_000) { enteredRemoteMutation.get() }
        // Navigation destroys the remembered coroutine scope while the
        // source command has an unknown outcome. Do not resume that command.
        rule.runOnUiThread { visible.value = false }
        rule.waitUntil(10_000) { settled.get() == 1 }
        assertEquals(1, acquired.get())
        assertEquals(1, settled.get())
        assertEquals("Cancellation must never replay a destructive request", 1, writes.get())
    }

    @Test fun accountSwitchInvalidatesPendingBatchRemovalConfirmation() {
        val moves = listOf(move(1, "Public one"), move(2, "Public two"))
        val activeScope = mutableStateOf("a".repeat(64))
        val removed = Collections.synchronizedList(mutableListOf<String>())
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BatchMoveRemovalPanel(
                        pending = moves, serverScopeDigest = activeScope.value,
                        serverLabel = "seed.example", busy = false,
                        observeTarget = { Observation.Present },
                        removeSource = { removed += it },
                        observeSource = { Observation.Absent },
                        save = { true }, forget = { true }, onFinished = {}
                    )
                }
            }
        }
        rule.onNodeWithText("Remove 2 original phone jobs…").performClick()
        rule.onNodeWithText("Remove original jobs").assertExists()
        rule.runOnUiThread { activeScope.value = "b".repeat(64) }
        rule.waitForIdle()
        // Same infohashes on another account do not preserve source-removal consent.
        rule.onNodeWithText("Remove 2 original phone jobs…").assertIsNotEnabled()
        assertTrue(removed.isEmpty())
    }
}
