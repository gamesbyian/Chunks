package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class RemoteListContinuityTest {
    @get:Rule val rule = createComposeRule()

    private val first = "https://seed.example/rutorrent/RPC2\u0000alice"
    private val second = "https://seed.example/rutorrent2/RPC2\u0000alice"

    @Test fun eachExactAccountKeepsItsOwnPositionAndSearch() {
        val continuity = RemoteListContinuity()
        val a = continuity.listState(first)
        assertSame("route switches reuse the same list state", a, continuity.listState(first))
        val b = continuity.listState(second)
        assertTrue("another numbered instance never inherits a position", a !== b)
        continuity.rememberQuery(first, "ubuntu")
        assertEquals("ubuntu", continuity.query(first))
        assertEquals("", continuity.query(second))
        continuity.rememberQuery("", "ignored")
        assertEquals("", continuity.query(""))
    }

    @Test fun savedStateHoldsOnlyADigestNotTheEndpointOrUser() {
        val tag = RemoteListContinuity.tagOf(first)
        assertEquals(24, tag.length)
        assertFalse(tag.contains("seed") || tag.contains("alice"))
        assertTrue(tag != RemoteListContinuity.tagOf(second))
    }

    @Test fun positionAndSearchSurviveRecreationForTheSameAccountOnly() {
        val tester = StateRestorationTester(rule)
        var restored: RemoteListContinuity? = null
        tester.setContent {
            val continuity = rememberSaveable(saver = RemoteListContinuity.Saver) {
                RemoteListContinuity()
            }
            restored = continuity
            LazyColumn(Modifier.fillMaxSize(), state = continuity.listState(first)) {
                items(200) { Text("Row $it", Modifier.height(48.dp)) }
            }
        }
        rule.runOnIdle { restored!!.rememberQuery(first, "debian") }
        rule.runOnIdle {
            runBlocking { restored!!.listState(first).scrollToItem(120) }
        }
        rule.onNodeWithText("Row 120").assertExists()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Row 120").assertExists()
        rule.runOnIdle {
            val again = restored!!
            assertEquals(120, again.listState(first).firstVisibleItemIndex)
            assertEquals("debian", again.query(first))
            assertEquals("", again.query(second))
            assertEquals(0, again.listState(second).firstVisibleItemIndex)
        }
    }
}
