package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.model.TransferMove
import io.github.gamesbyian.chunks.model.TransferMove.Observation
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Move Transfer UI with fake ports: job-only, separately confirmed, never auto-retried. */
@RunWith(AndroidJUnit4::class)
class TransferMoveControlInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "b63eaa9720ee797667ef58ea35ef7d81ab5d1c16"
    private val scope = "https://seed.example/RPC2\u0000user"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clean() {
        context.getSharedPreferences("chunks_transfer_moves_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun show(
        target: () -> Observation, source: () -> Observation,
        removals: AtomicInteger, starts: AtomicInteger, removeFails: Boolean = false
    ) = rule.setContent {
        MaterialTheme {
            Column {
                TransferMoveControl(
                    direction = TransferMove.Direction.PHONE_TO_SERVER,
                    infoHash = hash, name = "Big Buck Bunny", serverAliasScope = scope, busy = false,
                    ports = TransferMovePorts(
                        observeTarget = { target() }, observeSource = { source() },
                        removeSource = { removals.incrementAndGet(); if (removeFails) error("lost reply") },
                        targetUnavailableReason = "Connect"),
                    onStartMove = { starts.incrementAndGet() })
            }
        }
    }

    private fun waitFor(text: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun moveVerifiesTargetThenRemovesOnlyTheSourceJobAfterSecondConfirmation() {
        val removals = AtomicInteger(); val starts = AtomicInteger()
        var targetSeen: Observation = Observation.Absent
        var sourceSeen: Observation = Observation.Present
        show({ targetSeen }, { sourceSeen }, removals, starts)
        rule.onNodeWithText("Move Transfer to server…").performClick()
        rule.onNodeWithText("Moves the torrent job, not its files", substring = true).assertExists()
        rule.onNodeWithText("Review server Add").performClick()
        assertEquals(1, starts.get())
        rule.onNodeWithText("Check the server").performClick()
        waitFor("Not on the server yet")
        assertEquals(0, removals.get())
        targetSeen = Observation.Present
        rule.onNodeWithText("Check the server").performClick()
        waitFor("Remove original job from the phone…")
        rule.onNodeWithText("Remove original job from the phone…").performClick()
        rule.onNodeWithText("Downloaded files stay on the phone", substring = true).assertExists()
        assertEquals(0, removals.get())
        sourceSeen = Observation.Absent
        rule.onNodeWithText("Remove original job").performScrollTo().performClick()
        waitFor("Moved: the job now lives on the server")
        assertEquals(1, removals.get())
        assertNull(TransferMoveStore(context).get(TransferMove.Direction.PHONE_TO_SERVER, hash))
    }

    @Test fun lostRemovalReplyBecomesUncertainAndIsNeverRepeatedAutomatically() {
        val removals = AtomicInteger(); val starts = AtomicInteger()
        var sourceSeen: Observation = Observation.Unknown
        val store = TransferMoveStore(context)
        store.save(TransferMove.start(TransferMove.Direction.PHONE_TO_SERVER, hash, "Big Buck Bunny",
            TransferMoveStore.scopeDigest(scope), "seed.example")
            .copy(stage = TransferMove.Stage.TARGET_VERIFIED))
        show({ Observation.Present }, { sourceSeen }, removals, starts, removeFails = true)
        rule.onNodeWithText("Remove original job from the phone…").performClick()
        rule.onNodeWithText("Remove original job").performScrollTo().performClick()
        waitFor("could not confirm whether the original job was removed")
        assertEquals(1, removals.get())
        rule.onNodeWithText("Check the original job").performClick()
        waitFor("Still unable to check")
        sourceSeen = Observation.Absent
        rule.onNodeWithText("Check the original job").performClick()
        waitFor("Moved:")
        assertEquals("observation, not a second request, resolved it", 1, removals.get())
    }
}
