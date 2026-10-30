package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Same-hash rows in a persistent LazyColumn position must never inherit another
 * account's pending remote operation review after the active profile switches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class RemoteTransferCompositionBoundaryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sameHashDifferentServerDiscardsOldConfirmationWithoutRunningActions() {
        var server by mutableStateOf("https://one.test/RPC2\u0000alice")
        var hash by mutableStateOf("a".repeat(40))
        var unrelatedFrame by mutableStateOf(0)
        var actions = 0
        rule.setContent {
            Column {
                Text("Frame: $unrelatedFrame")
                RemoteTransferCompositionBoundary(server, hash) {
                    var confirming by androidx.compose.runtime.saveable.rememberSaveable {
                        mutableStateOf(false)
                    }
                    Text(if (confirming) "Review pending" else "No review pending")
                    Button(onClick = { confirming = true }) { Text("Review transfer") }
                    Button(onClick = { actions++ }) { Text("Synthetic action") }
                }
            }
        }
        rule.onNodeWithText("Review transfer").performClick()
        rule.onNodeWithText("Review pending").assertExists()
        // Ordinary recomposition must not discard the current account's review.
        rule.runOnIdle { unrelatedFrame++ }
        rule.onNodeWithText("Review pending").assertExists()

        // Another account on the same endpoint with exactly the same hash
        // receives a fresh subtree, not account one's pending confirmation.
        rule.runOnIdle { server = "https://one.test/RPC2\u0000bob" }
        rule.onNodeWithText("No review pending").assertExists()
        assertEquals(0, actions)

        // A new hash on the same server also drops previous transfer state.
        rule.onNodeWithText("Review transfer").performClick()
        rule.runOnIdle { hash = "b".repeat(40) }
        rule.onNodeWithText("No review pending").assertExists()
        assertEquals(0, actions)
    }
}
