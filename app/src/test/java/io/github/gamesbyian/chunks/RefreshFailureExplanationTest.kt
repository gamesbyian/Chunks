package io.github.gamesbyian.chunks

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.rpc.RpcHttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An observed HTTP status is explained honestly instead of as a network error. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class RefreshFailureExplanationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun loginRefusalIsNotCalledANetworkError() {
        val refused = readableFailure(RpcHttpStatusException(401))
        assertTrue(refused.contains("refused this login"))
        assertFalse(refused.contains("Network or proxy"))
        assertTrue(isLoginRefused(RpcHttpStatusException(403)))
        assertFalse(isLoginRefused(RpcHttpStatusException(502)))
        assertTrue(readableFailure(RpcHttpStatusException(502)).contains("HTTP 502"))
        assertTrue(readableFailure(RpcHttpStatusException(429)).contains("limiting requests"))
        assertEquals("Network or proxy error. Check your connection and server settings.",
            readableFailure(java.io.IOException("x")))
    }

    @Test fun onlyARefusedLoginOffersOpenServer() {
        var opened = 0
        rule.setContent { ChunksTheme {
            RefreshProblemNotice("refused", refreshing = false, onRefresh = {}, onOpenServer = { opened++ })
        } }
        rule.onNodeWithText("Open Server").performClick()
        assertEquals(1, opened)
    }

    @Test fun otherFailuresOfferOnlyTheReadOnlyRetry() {
        rule.setContent { ChunksTheme { RefreshProblemNotice("down", refreshing = false, onRefresh = {}) } }
        rule.onNodeWithText("Refresh list again").assertExists()
        rule.onAllNodesWithText("Open Server").assertCountEquals(0)
    }
}
