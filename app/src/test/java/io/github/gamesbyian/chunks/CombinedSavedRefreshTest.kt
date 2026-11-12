package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class CombinedSavedRefreshTest {
    @get:Rule val rule = createComposeRule()

    @Test fun retryOneFailedServerDoesNotReloadTheOthersAt200Percent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        CombinedViewPreferences(context).save(UnifiedTransferQuery())
        val first = AtomicInteger()
        val second = AtomicInteger()
        fun profile(number: Int, reads: AtomicInteger) = CombinedSavedProfile(
            EnvironmentSnapshots.serverId("https://box.example/rutorrent$number/RPC2", "reader").value,
            "Server $number", "https://box.example/rutorrent$number/RPC2\u0000reader",
            listOnly = { reads.incrementAndGet(); throw java.net.SocketTimeoutException() })
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChunksTheme { Surface {
                    CombinedTransfersScreen(null, listOf(profile(2, first), profile(3, second)),
                        onOpenLocal = {}, onOpenServer = null, onBack = {})
                } }
            }
        }
        fun reveal(text: String) {
            rule.waitUntil(10_000) {
                runCatching {
                    rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
                }.isSuccess
            }
        }
        reveal("Environment status · 3 sources")
        rule.onNodeWithText("Environment status · 3 sources").performClick()
        reveal("Load Server · Server 2 transfers")
        rule.onNodeWithContentDescription("Load transfers on Server · Server 2; read only").performClick()
        rule.waitUntil(10_000) { first.get() == 1 }
        reveal("Server · Server 2: Could not load (did not answer in time) · 0 transfers")
        reveal("Load Server · Server 2 transfers")
        rule.onNodeWithContentDescription("Load transfers on Server · Server 2; read only").performClick()
        rule.waitUntil(10_000) { first.get() == 2 }
        assertEquals(0, second.get())
    }
}
