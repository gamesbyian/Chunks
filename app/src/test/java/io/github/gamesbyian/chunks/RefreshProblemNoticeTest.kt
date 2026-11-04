package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class RefreshProblemNoticeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun failedReadOffersOneReadOnlyRetryThatWaitsWhileRefreshing() {
        var refreshes = 0
        var refreshing by mutableStateOf(false)
        rule.setContent { ChunksTheme {
            RefreshProblemNotice("The server did not accept the connection.", refreshing) {
                refreshes++
                refreshing = true
            }
        } }
        rule.onNodeWithText("The server did not accept the connection.").assertExists()
        rule.onNodeWithText("Refresh list again").performClick()
        assertEquals(1, refreshes)
        rule.onNodeWithText("Refreshing…").assertIsNotEnabled()
    }
}
