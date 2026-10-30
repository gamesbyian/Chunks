package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RemoteProbe
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Capability evidence belongs to an authenticated RPC session, not merely
 * an endpoint+username shared by two separate connections.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class CombinedDetailSessionCapabilityTest {
    @get:Rule val rule = createComposeRule()
    private val aliasScope = "https://box.example/rutorrent2/RPC2\u0000ian"
    private val hash = "a".repeat(40)
    private val transfer = RemoteTransfer(hash, "A single download", 4096, 1024,
        true, "/remote", downBytesPerSecond = 0, upBytesPerSecond = null)

    private class Session(val name: String) {
        override fun equals(other: Any?): Boolean =
            other is Session && name == other.name
        override fun hashCode(): Int = name.hashCode()
    }

    private fun source(
        session: Any,
        remoteProbe: suspend () -> RemoteProbe
    ) = CombinedServerSource(
        aliasScope, listOf(transfer), stale = false, fullView = true,
        observedAtEpochMillis = System.currentTimeMillis(),
        lifecycle = CombinedServerLifecycle(
            begin = { error("Details must never lease a mutation") },
            settled = { error("No mutation should settle") },
            setStarted = { _, _ -> error("Details must never send Start/Stop") },
            probe = remoteProbe
        ),
        authenticatedSession = session
    )

    @Test fun oldProbeCannotPopulateNewAuthenticatedSessionWithSameProfile() {
        val first = Session("same remembered endpoint and login")
        val second = Session("same remembered endpoint and login")
        assertEquals(first, second)
        assertFalse(first === second)
        val firstReply = CompletableDeferred<RemoteProbe>()
        val secondReply = CompletableDeferred<RemoteProbe>()
        val firstStarted = AtomicInteger(0)
        val firstResponded = AtomicBoolean(false)
        val secondStarted = AtomicInteger(0)
        val server = mutableStateOf(source(first) {
            firstStarted.incrementAndGet()
            firstReply.await().also { firstResponded.set(true) }
        })
        rule.setContent {
            ChunksTheme { Surface {
                CombinedTransfersScreen(server = server.value, savedProfiles = emptyList(),
                    onOpenPhone = {}, onOpenServer = {}, onBack = {})
            } }
        }
        val label = serverEnvironmentLabel(aliasScope)
        rule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("A single download"))
        rule.onNodeWithContentDescription("Details for A single download on $label")
            .performScrollTo().performClick()
        val check = "Check capabilities for $label"
        rule.onNodeWithContentDescription(check).performScrollTo().performClick()
        rule.waitUntil(5_000) { firstStarted.get() == 1 }
        rule.onNodeWithText("Checking capabilities…").assertExists()

        // Same host/profile and infohash, different live transport instance.
        rule.runOnIdle {
            server.value = source(second) {
                secondStarted.incrementAndGet()
                secondReply.await()
            }
        }
        rule.onNodeWithText("Check server capabilities").performScrollTo().assertExists()
        firstReply.complete(RemoteProbe("old-session", emptySet()))
        rule.waitUntil(5_000) { firstResponded.get() }
        rule.waitForIdle()
        rule.onNodeWithText(
            "Start and stop: Capability has not been checked recently for this exact environment.")
            .performScrollTo().assertExists()
        rule.onNodeWithText("Not advertised by rTorrent old-session.", substring = true)
            .assertDoesNotExist()

        rule.onNodeWithContentDescription(check).performScrollTo().performClick()
        rule.waitUntil(5_000) { secondStarted.get() == 1 }
        secondReply.complete(RemoteProbe("new-session", emptySet()))
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Start and stop: Not advertised by rTorrent new-session.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Start and stop: Not advertised by rTorrent new-session.")
            .performScrollTo().assertExists()
        rule.onNodeWithText("Not advertised by rTorrent old-session.", substring = true)
            .assertDoesNotExist()
        assertEquals(1, firstStarted.get())
        assertEquals(1, secondStarted.get())
    }

    @Test fun savedOrUnboundSessionCannotOfferCapabilityProbe() {
        rule.setContent {
            ChunksTheme { Surface {
                CombinedTransfersScreen(
                    server = CombinedServerSource(aliasScope, listOf(transfer), stale = false,
                        fullView = true, observedAtEpochMillis = System.currentTimeMillis(),
                        lifecycle = CombinedServerLifecycle(
                            begin = { false }, settled = {},
                            setStarted = { _, _ -> LifecycleOutcome.Verified(true) },
                            probe = { error("Unbound session cannot probe") })),
                    savedProfiles = emptyList(), onOpenPhone = {}, onOpenServer = {},
                    onBack = {})
            } }
        }
        val label = serverEnvironmentLabel(aliasScope)
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("A single download"))
        rule.onNodeWithContentDescription("Details for A single download on $label")
            .performScrollTo().performClick()
        rule.onNodeWithText("Check server capabilities").assertDoesNotExist()
    }
}
