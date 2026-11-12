package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** rTorrent parity for the local device's "added but hidden by this view" explanation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class RemoteAddedHiddenNoticeTest {
    @get:Rule val rule = createComposeRule()

    private val scope = "https://seed.example/rutorrent/RPC2\u0000alice"
    private val other = "https://seed.example/rutorrent2/RPC2\u0000alice"
    private val hash = "A".repeat(40)
    private val view = RemoteAddedRecord.viewKey("debian", "SEEDING")
    private val record = RemoteAddedRecord(scope, hash, view)

    @Test fun onlyAnObservedVerifiedHashHiddenByTheSameViewOnTheSameAccount() {
        val inventory = listOf(hash.lowercase(), "b".repeat(40))
        assertTrue(record.hiddenIn(scope, view, inventory, listOf("b".repeat(40))))
        assertFalse("shown rows need no notice",
            record.hiddenIn(scope, view, inventory, inventory))
        assertFalse("an unobserved hash cannot be claimed as added",
            record.hiddenIn(scope, view, listOf("b".repeat(40)), emptyList()))
        assertFalse("another numbered instance never inherits the notice",
            record.hiddenIn(other, view, inventory, emptyList()))
        assertFalse("changing search or filter retires the notice",
            record.hiddenIn(scope, RemoteAddedRecord.viewKey("", "SEEDING"), inventory, emptyList()))
    }

    @Test fun noticeNamesTheExactServerAndShowAllOnlyClearsTheView() {
        var cleared = 0
        rule.setContent {
            ChunksTheme {
                LocalAddedHiddenNotice(onShowAll = { cleared++ },
                    showAllLabel = "Show all transfers on Seedhost")
            }
        }
        rule.onNodeWithText("hidden by the current search or filter", substring = true).assertExists()
        rule.onNodeWithText("Show all transfers on Seedhost").performClick()
        assertEquals(1, cleared)
    }
}
