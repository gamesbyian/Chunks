package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.model.TransferMove
import io.github.gamesbyian.chunks.model.TransferMove.Observation
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchMoveRemovalPanelInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private fun move(n: Int, name: String) = TransferMove.start(TransferMove.Direction.PHONE_TO_SERVER,
        "%040x".format(n), name, "a".repeat(64), "seed.example").copy(stage = TransferMove.Stage.TARGET_VERIFIED)

    @Test fun removalNeedsSeparateConfirmationAndKeepsJobsWhoseTargetVanished() {
        val moves = listOf(move(1, "Public one"), move(2, "Public two"))
        val removed = Collections.synchronizedList(mutableListOf<String>())
        val finished = Collections.synchronizedList(mutableListOf<String>())
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BatchMoveRemovalPanel(moves, "seed.example", busy = false,
                        observeTarget = { if (it == moves[0].infoHash) Observation.Present else Observation.Absent },
                        removeSource = { removed += it },
                        observeSource = { if (it in removed) Observation.Absent else Observation.Present },
                        save = { true }, forget = {}, onFinished = { finished += it })
                }
            }
        }
        rule.onNodeWithText("Remove 2 original phone jobs…").performClick()
        rule.onNodeWithText("Downloaded files stay on this phone", substring = true).assertExists()
        assertTrue(removed.isEmpty())
        rule.onNodeWithText("Remove original jobs").performScrollTo().performClick()
        rule.waitUntil(10_000) { finished.isNotEmpty() }
        assertEquals(listOf(moves[0].infoHash), removed.toList())
        assertTrue(finished.single().contains("1 original phone jobs removed"))
    }
}
