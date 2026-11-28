package io.github.gamesbyian.chunks.media

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.SimilarTransfersHost
import io.github.gamesbyian.chunks.media.artwork.ArtworkDiskCache
import io.github.gamesbyian.chunks.media.artwork.ArtworkRepository
import io.github.gamesbyian.chunks.media.providers.TvMazeProvider
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.MediaMetadataCacheKey
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The actual Details relation host must remain a read-only consumer of authorized observations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h900dp-xxhdpi")
class SimilarTransfersHostTest {
    @get:Rule val rule = createComposeRule()
    private val dir = Files.createTempDirectory("similar-host").toFile()
    private val http = FakeMediaHttp()
    private val clock = FakeClock()
    private var settings = MediaPrivacySettings(onlineLookupConsent = true, automaticLookup = false)
    private val local = EnvironmentId("local")
    private val remote = EnvironmentId("rtorrent:related")
    private val source = row(local, "a", "Babylon.5.S02E07.mkv")
    private val target = row(remote, "b", "Babylon.5.S02E08.mkv")
    private val attribution = MediaAttribution("TVmaze", "CC BY-SA 4.0", null)

    private fun row(env: EnvironmentId, hashDigit: String, title: String) =
        UnifiedTransferObservation(UnifiedTransferKey.of(env, hashDigit.repeat(40)), title, title,
            null, null, null, null, null, null)

    private fun episode(id: String, number: Int) =
        MediaCandidate(MediaMetadataCacheKey("tvmaze", "show", id), MediaKind.TV_EPISODE,
            "Babylon 5", season = 2, episode = number, attribution = attribution)

    private fun snapshot(row: UnifiedTransferObservation, availability: EnvironmentAvailability = EnvironmentAvailability.LIVE) =
        EnvironmentTransferSnapshot(row.key.environment, availability, availability == EnvironmentAvailability.LIVE, 1234L,
            if (availability == EnvironmentAvailability.LOCKED) emptyList() else listOf(row))

    private fun controller(): TransferMediaController {
        val tv = TvMazeProvider()
        val service = MediaEnrichmentService(listOf(tv), http,
            FileMediaQueryCache(File(dir, "query.json")), { clock.now }, clock::sleep)
        return TransferMediaController(service,
            ArtworkRepository(http, ArtworkDiskCache(File(dir, "art")),
                hostPolicyFor = { tv.info.artworkHostPolicy }, memoryBytes = 4_000_000),
            { FileTransferMediaAssignmentStore(File(dir, "assign.json")) }, { settings },
            { MediaNetworkState(true, false) }, CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined, Dispatchers.Unconfined, { clock.now },
            automationStoreFactory = { FileMediaAutomationPreferences(File(dir, "automation.json")) })
    }

    @Test fun openingUnidentifiedDetailsDoesNotAuthorizeProviderLookupEvenWhenAutomaticLookupIsEnabled() {
        settings = settings.copy(automaticLookup = true)
        val c = controller()
        c.onSettingsChanged()
        rule.setContent {
            ChunksTheme {
                CompositionLocalProvider(LocalTransferMediaController provides c) {
                    SimilarTransfersHost(source, listOf(snapshot(source), snapshot(target)),
                        { it.value }, { ChunksColors.Cyan }, {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Similar transfers").assertDoesNotExist()
        assertTrue("Similarity itself must not start an identification or artwork request", http.requests.isEmpty())
    }

    @Test fun targetCorrectionsAndScopeChangesInvalidateRowsWithoutNewNetworkRequests() {
        val store = FileTransferMediaAssignmentStore(File(dir, "assign.json"))
        store.put(TransferMediaAssignment(MediaTransferKey.of(source.key), MediaAssignmentState.CHOSEN_BY_USER, episode("475", 7)))
        store.put(TransferMediaAssignment(MediaTransferKey.of(target.key), MediaAssignmentState.CHOSEN_BY_USER, episode("475", 8)))
        val c = controller()
        // Simulates the other transfer's explicit Identify editor; it has no automatic authority.
        c.observe(MediaTransferKey.of(target.key), target.originalName, isAuthorized = { false })
        val inventory = mutableStateOf(listOf(snapshot(source), snapshot(target)))
        var opened: UnifiedTransferKey? = null
        rule.setContent {
            ChunksTheme {
                CompositionLocalProvider(LocalTransferMediaController provides c) {
                    SimilarTransfersHost(source, inventory.value,
                        { it.value }, { ChunksColors.Cyan }, { opened = it })
                }
            }
        }
        rule.onNodeWithText("Nearby episode").assertExists()
        rule.onNodeWithContentDescription("Nearby episode", substring = true).performClick()
        assertEquals(target.key, opened)

        rule.runOnIdle { c.choose(MediaTransferKey.of(target.key), episode("57926", 8)) }
        rule.onNodeWithText("Similar transfers").assertDoesNotExist()

        rule.runOnIdle { c.choose(MediaTransferKey.of(target.key), episode("475", 12)) }
        rule.onNodeWithText("Same series").assertExists()
        rule.runOnIdle { c.choose(MediaTransferKey.of(target.key), episode("475", 8)) }
        rule.onNodeWithText("Nearby episode").assertExists()

        rule.runOnIdle {
            settings = settings.copy(similarSameEnvironmentOnly = true)
            c.onSettingsChanged()
        }
        rule.onNodeWithText("Similar transfers").assertDoesNotExist()
        rule.runOnIdle {
            settings = settings.copy(similarSameEnvironmentOnly = false)
            c.onSettingsChanged()
        }
        rule.onNodeWithText("Nearby episode").assertExists()
        rule.runOnIdle { inventory.value = listOf(snapshot(source), snapshot(target, EnvironmentAvailability.LOCKED)) }
        rule.onNodeWithText("Similar transfers").assertDoesNotExist()
        assertTrue(http.requests.isEmpty())
    }
}
