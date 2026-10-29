package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.model.BatchPhoneCopy
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteBatchPhoneCopyPanelInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun copyToPhoneNeedsConfirmationAndPassesExactlyTheSelection() {
        val runs = Collections.synchronizedList(mutableListOf<List<BatchPhoneCopy.Item>>())
        val selected = listOf(RemoteTransfer("AA".repeat(20), "Public one", 10, 0, false, "/srv"),
            RemoteTransfer("BB".repeat(20), "Public two", 10, 10, true, "/srv"))
        rule.setContent {
            MaterialTheme {
                RemoteBatchPhoneCopyPanel(selected, enabled = true, run = { items -> runs += items; "2 added to the phone" })
            }
        }
        rule.onNodeWithText("Copy Transfer to phone (2)…").performClick()
        rule.onNodeWithText("nothing downloads until you start it", substring = true).assertExists()
        assertTrue(runs.isEmpty())
        rule.onNodeWithText("Confirm copy to phone").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("2 added to the phone").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(selected.map { it.hash }), runs.map { list -> list.map { it.infoHash } })
    }
}
