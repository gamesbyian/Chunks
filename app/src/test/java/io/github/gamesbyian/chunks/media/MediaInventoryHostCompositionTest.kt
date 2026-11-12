package io.github.gamesbyian.chunks.media

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.gamesbyian.chunks.media.artwork.ArtworkDiskCache
import io.github.gamesbyian.chunks.media.artwork.ArtworkRepository
import io.github.gamesbyian.chunks.media.providers.TvMazeProvider
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1453 composition lifetime: the host provided at the activity root keeps the last fed inventory
 * after the feeding screen leaves the composition, and releases it only when the root leaves.
 * JVM/Robolectric; no provider request is made (automatic lookup off, no network expected).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaInventoryHostCompositionTest {
    @get:Rule val rule = createComposeRule()

    private val dir: File = Files.createTempDirectory("host-media").toFile()
    private val http = FakeMediaHttp()
    private val settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
    private val phone = EnvironmentId("phone")
    private val hash = "d".repeat(40)

    private fun controller(): TransferMediaController {
        val tv = TvMazeProvider()
        val clock = FakeClock()
        val service = MediaEnrichmentService(listOf(tv), http, FileMediaQueryCache(File(dir, "q.json")), { clock.now }, clock::sleep)
        return TransferMediaController(service,
            ArtworkRepository(http, ArtworkDiskCache(File(dir, "art")), hostPolicyFor = { tv.info.artworkHostPolicy }, memoryBytes = 4_000_000),
            { FileTransferMediaAssignmentStore(File(dir, "assign.json")) }, { settings }, { MediaNetworkState(true, false) },
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, Dispatchers.Unconfined, { clock.now })
    }

    private fun snapshot(epoch: String) = AuthorizedMediaInventorySnapshot.build(
        listOf(EnvironmentTransferSnapshot(phone, EnvironmentAvailability.LIVE, true, 1234L,
            listOf(UnifiedTransferObservation(UnifiedTransferKey.of(phone, hash),
                "Hackers.1995.mkv", "Hackers.1995.mkv", null, null, null, null, null, null)))),
        mapOf(phone to "phone"), epoch)

    @Test fun hostOutlivesTheFeedingScreenAndEndsWithTheActivityContent() {
        val c = controller()
        var rootMounted by mutableStateOf(true)
        var screenMounted by mutableStateOf(true)
        var epoch by mutableStateOf("1/0")
        var seen: MediaInventoryHost? = null
        var siblingCompositions = 0
        rule.setContent {
            CompositionLocalProvider(LocalTransferMediaController provides c) {
                if (rootMounted) ProvideMediaInventoryHost(privacyEpoch = { epoch }) {
                    // A screen that never reads the host (Connections, Add, Options) must not
                    // recompose when the privacy generation or the host value changes.
                    SiblingThatIgnoresTheHost { siblingCompositions++ }
                    val host = LocalMediaInventoryHost.current
                    seen = host
                    // Stand-in for CombinedTransfersScreen: feed once when composed.
                    if (screenMounted && host != null) LaunchedEffect(host) { host.feed(snapshot("1/0")) }
                }
            }
        }
        rule.waitForIdle()
        assertNotNull(seen)
        val host = seen!!
        assertEquals(1, host.retained?.entries?.size)

        screenMounted = false
        rule.waitForIdle()
        assertEquals("Leaving Transfers must not drop the authorized inventory", 1, host.retained?.entries?.size)

        val siblingBefore = siblingCompositions
        epoch = "2/0"
        rule.waitForIdle()
        assertNull("A privacy generation change revokes it until the next fresh feed", host.retained)
        assertEquals("Other screens must not recompose on a privacy generation change",
            siblingBefore, siblingCompositions)

        rootMounted = false
        rule.waitForIdle()
        assertNull(host.retained)
        assertTrue("Display and lifetime handling must not contact a provider", http.requests.isEmpty())
    }
}

@androidx.compose.runtime.Composable
private fun SiblingThatIgnoresTheHost(onCompose: () -> Unit) {
    androidx.compose.runtime.SideEffect(onCompose)
    androidx.compose.material3.Text("sibling")
}
