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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalAddedHiddenNoticeTest {
    @get:Rule val rule = createComposeRule()
    private val a = "a".repeat(40)
    private val b = "b".repeat(40)

    @Test fun onlyAnObservedButFilteredExactHashCountsAsHidden() {
        assertTrue(LocalAddedVisibility.hiddenByView(a, listOf(a, b), listOf(b)))
        assertTrue("hash case differences are the same transfer",
            LocalAddedVisibility.hiddenByView(a.uppercase(), listOf(a), emptyList()))
        assertFalse("shown", LocalAddedVisibility.hiddenByView(a, listOf(a), listOf(a)))
        assertFalse("not yet observed in the inventory",
            LocalAddedVisibility.hiddenByView(a, listOf(b), emptyList()))
        assertFalse("no acknowledged Add", LocalAddedVisibility.hiddenByView(null, listOf(a), emptyList()))
    }

    @Test fun showAllIsTheOnlyActionAndIsExplicit() {
        var cleared = 0
        rule.setContent { ChunksTheme { LocalAddedHiddenNotice(onShowAll = { cleared++ }) } }
        rule.onNodeWithText("hidden by the current search or filter", substring = true).assertExists()
        rule.onNodeWithText("Show all local transfers").performClick()
        assertEquals(1, cleared)
    }
}
