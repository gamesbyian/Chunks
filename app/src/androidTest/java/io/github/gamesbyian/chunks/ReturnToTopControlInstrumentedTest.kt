package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReturnToTopControlInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun longRemoteStyleListShowsAccessibleActionAndReturnsWithoutChangingView() {
        var filter = "Only started"
        var firstVisible = -1
        rule.setContent {
            val scroll = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val show by remember(scroll) {
                derivedStateOf {
                    ReturnToTopPolicy.remote(
                        scroll.firstVisibleItemIndex,
                        scroll.firstVisibleItemScrollOffset,
                        scroll.layoutInfo.viewportSize.height,
                        true
                    )
                }
            }
            firstVisible = scroll.firstVisibleItemIndex
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize()) {
                        items((0 until 100).toList()) { index ->
                            Text("Torrent $index", modifier = Modifier.heightIn(min = 70.dp).padding(8.dp))
                        }
                    }
                    ReturnToTopControl(
                        visible = show,
                        onClick = { scope.launch { scroll.scrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
            }
        }
        rule.onNodeWithText("Back to top").assertDoesNotExist()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Torrent 70"))
        rule.onNodeWithText("Back to top").assertExists().performClick()
        rule.waitUntil(5_000) { firstVisible == 0 }
        rule.onNodeWithText("Back to top").assertDoesNotExist()
        rule.onNodeWithText("Torrent 0").assertExists()
        assertEquals("Only started", filter)
    }

    @Test fun controlOnlyComposesWhenEnabledAndHasReadableLabel() {
        var taps = 0
        rule.setContent {
            MaterialTheme {
                ReturnToTopControl(visible = true, onClick = { taps++ })
            }
        }
        rule.onNodeWithText("Back to top").assertExists().performClick()
        assertEquals(1, taps)
    }
}
