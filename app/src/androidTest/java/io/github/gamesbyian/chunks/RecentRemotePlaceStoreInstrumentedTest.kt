package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentRemotePlaceStoreInstrumentedTest {
    @Test fun successfulCustomPlacesSurviveInstancesAndNeverCrossAccounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RecentRemotePlaceStore(context)
        val alice = "https://test.invalid/RPC2\\u0000alice-recent-test"
        val bob = "https://test.invalid/RPC2\\u0000bob-recent-test"
        val distinct = (0 until 12).map { "/home/alice/series-$it" }
        assertTrue(store.places(bob).isEmpty())
        for (path in distinct) assertTrue(store.recordVerifiedCustom(alice, path))
        val reopened = RecentRemotePlaceStore(context)
        assertEquals(RecentRemotePlaceStore.MAX_RECENT, reopened.places(alice).size)
        assertEquals(distinct.reversed().take(RecentRemotePlaceStore.MAX_RECENT),
            reopened.places(alice))
        assertTrue(reopened.places(bob).isEmpty())
        assertTrue(store.recordVerifiedCustom(alice, distinct[5]))
        assertEquals(distinct[5], reopened.places(alice).first())
        assertEquals(8, reopened.places(alice).distinct().size)
    }

    @Test fun dangerousPathsCannotEnterTheRecentList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RecentRemotePlaceStore(context)
        val scope = "https://test.invalid/RPC2\\u0000reject-recent-test"
        val before = store.places(scope)
        for (path in listOf("", "relative", "/", "/a/../b", "/a//b",
                "/a/;d.erase", "/a/=command")) {
            try {
                store.recordVerifiedCustom(scope, path)
                fail("Accepted invalid path")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(before, RecentRemotePlaceStore(context).places(scope))
    }
}
