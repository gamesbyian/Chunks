package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.model.ManualCollectionPager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalTransferPageControlsInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun onePageDoesNotRepeatTheAlreadyVisibleListCount() {
        var accidentalActions = 0
        rule.setContent {
            MaterialTheme {
                LocalTransferPageControls(
                    page = ManualCollectionPager.slice((0 until 12).toList(), 0, size = 20),
                    onPrevious = { accidentalActions++ },
                    onNext = { accidentalActions++ }
                )
            }
        }
        rule.onNodeWithText("On-phone transfers: 12 · page 1 of 1").assertDoesNotExist()
        rule.onNodeWithText("Previous phone transfers").assertDoesNotExist()
        rule.onNodeWithText("Next phone transfers").assertDoesNotExist()
        assertEquals(0, accidentalActions)
    }

    @Test fun exactlyOneFullPageHasNoUnnecessaryPagerControls() {
        rule.setContent {
            MaterialTheme {
                LocalTransferPageControls(
                    page = ManualCollectionPager.slice((0 until 20).toList(), 0, size = 20),
                    onPrevious = {},
                    onNext = {}
                )
            }
        }
        rule.onNodeWithText("On-phone transfers: 20 · page 1 of 1").assertDoesNotExist()
        rule.onNodeWithText("Next phone transfers").assertDoesNotExist()
    }

    @Test fun onPhonePagesAreReachableWithNativeButtonsAndCorrectDisabledBoundaries() {
        val items = (0 until 43).toList()
        var selected = 0
        rule.setContent {
            var pageIndex by remember { mutableStateOf(0) }
            val page = ManualCollectionPager.slice(items, pageIndex, size = 20)
            selected = page.pageIndex
            MaterialTheme {
                LocalTransferPageControls(page,
                    onPrevious = { pageIndex = (page.pageIndex - 1).coerceAtLeast(0) },
                    onNext = { pageIndex = page.pageIndex + 1 })
            }
        }
        rule.onNodeWithText("On-phone transfers: 43 · page 1 of 3").assertExists()
        rule.onNodeWithText("Next phone transfers").performClick()
        rule.onNodeWithText("On-phone transfers: 43 · page 2 of 3").assertExists()
        rule.onNodeWithText("Next phone transfers").performClick()
        rule.onNodeWithText("On-phone transfers: 43 · page 3 of 3").assertExists()
        rule.waitForIdle()
        assertEquals(2, selected)
        rule.onNodeWithText("Previous phone transfers").performClick()
        rule.onNodeWithText("On-phone transfers: 43 · page 2 of 3").assertExists()
    }
}
