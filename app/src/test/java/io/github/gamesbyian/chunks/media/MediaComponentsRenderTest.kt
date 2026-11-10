package io.github.gamesbyian.chunks.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.media.providers.MusicBrainzProvider
import io.github.gamesbyian.chunks.media.providers.OpenLibraryProvider
import io.github.gamesbyian.chunks.media.providers.TmdbProvider
import io.github.gamesbyian.chunks.media.providers.TvMazeProvider
import io.github.gamesbyian.chunks.media.ui.MediaCategoryTile
import io.github.gamesbyian.chunks.media.ui.MediaIdentifyPanel
import io.github.gamesbyian.chunks.media.ui.MediaMetadataSummary
import io.github.gamesbyian.chunks.media.ui.MediaPrivacySettingsPanel
import io.github.gamesbyian.chunks.media.ui.TransferMediaArtwork
import io.github.gamesbyian.chunks.model.MediaMetadataCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** JVM renders for visual review (build/chunks-visual/media-*.png) plus semantic checks. Not device proof. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1600dp-xxhdpi")
class MediaComponentsRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun key(n: Int) = MediaTransferKey("phone|" + "%040x".format(n))
    private fun state(n: Int, name: String) = TransferMediaUiState(key(n), MediaRecognizer.recognize(name))
    private val attribution = MediaAttribution("TVmaze", "CC BY-SA 4.0", null)
    private val b5 = MediaCandidate(MediaMetadataCacheKey("tvmaze", "show", "475"), MediaKind.TV_EPISODE, "Babylon 5", 1993,
        season = 2, episode = 7, episodeTitle = "Soul Mates", creators = listOf("TNT"), attribution = attribution)

    private fun fakePoster(): Bitmap {
        val b = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 384f, 384f, 0xFF1B2A6B.toInt(), 0xFFE3A82F.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(250f, 120f, 60f, Paint().apply { color = 0xFFFFF4D0.toInt() })
        return b
    }

    private fun matched(n: Int, name: String, candidate: MediaCandidate, st: MediaAssignmentState = MediaAssignmentState.AUTOMATIC) =
        state(n, name).copy(assignment = TransferMediaAssignment(key(n), st, candidate), artwork = fakePoster())

    @Test fun categoryTileGallery() {
        rule.setContent {
            ChunksTheme { Surface {
                Column(Modifier.background(ChunksColors.Background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MediaKind.entries.chunked(5).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { MediaCategoryTile(it, 72.dp) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MediaKind.entries.take(9).forEach { MediaCategoryTile(it, 40.dp) } }
                }
            } }
        }
        rule.onRoot().captureRoboImage("build/chunks-visual/media-category-tiles.png")
    }

    @androidx.compose.runtime.Composable
    private fun DemoRow(s: TransferMediaUiState, title: String, progress: Float) {
        Row(Modifier.fillMaxWidth().background(ChunksColors.Surface).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TransferMediaArtwork(s, 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = ChunksColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("${(progress * 100).toInt()}% · 2.1 MB/s", style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
            }
        }
    }

    private fun rows() = listOf(
        matched(1, "Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.mkv", b5) to "Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.mkv",
        state(2, "Radiohead - OK Computer (1997) [FLAC]").copy(recognition = MediaRecognizer.recognize("Radiohead - OK Computer (1997) [FLAC]",
            listOf(MediaFileHint("01.flac", 10)))) to "Radiohead - OK Computer (1997) [FLAC]",
        state(3, "ubuntu-24.04-desktop-amd64.iso") to "ubuntu-24.04-desktop-amd64.iso",
        state(4, "Something.XXX.1080p.mp4").copy(artwork = fakePoster()) to "Private transfer",
        state(5, "random_stuff") to "random_stuff")

    @Test fun transferRowsAt412() {
        rule.setContent { ChunksTheme { Surface { Column(Modifier.background(ChunksColors.Background).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows().forEachIndexed { i, (s, t) -> DemoRow(s, t, 0.2f * (i + 1)) }
        } } } }
        rule.onRoot().captureRoboImage("build/chunks-visual/media-rows-412dp.png")
        // Artwork is decorative: no node carries the tile's content description; the row name is the label.
        rule.onAllNodesWithText("MOVIE").fetchSemanticsNodes().isEmpty().let { assertTrue(it) }
    }

    @Test @Config(qualifiers = "w320dp-h1400dp-xhdpi", fontScale = 2.0f)
    fun transferRowsAt320With200PercentText() {
        rule.setContent { ChunksTheme { Surface { Column(Modifier.background(ChunksColors.Background).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows().forEachIndexed { i, (s, t) -> DemoRow(s, t, 0.2f * (i + 1)) }
        } } } }
        rule.onRoot().captureRoboImage("build/chunks-visual/media-rows-320dp-200pct.png")
    }

    @Test fun detailSummaryStates() {
        var changed = 0
        rule.setContent { ChunksTheme { Surface { Column(Modifier.background(ChunksColors.Background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MediaMetadataSummary(matched(1, "Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.mkv", b5), onChange = { changed++ })
            MediaMetadataSummary(matched(5, "Blade.Runner.1982.The.Final.Cut.REMASTERED.1080p.BluRay.mkv", MediaCandidate(
                MediaMetadataCacheKey("tmdb", "movie", "78"), MediaKind.MOVIE, "Blade Runner", 1982, attribution = MediaAttribution("TMDB", "TMDB", null)))
                .copy(poster = Bitmap.createScaledBitmap(fakePoster(), 260, 390, true)), onChange = {})
            MediaMetadataSummary(matched(2, "Radiohead - OK Computer (1997) [FLAC]", MediaCandidate(MediaMetadataCacheKey("musicbrainz", "release-group", "b1392450-e666-3926-a536-22c65f834433"),
                MediaKind.MUSIC_ALBUM, "OK Computer", 1997, creators = listOf("Radiohead"), attribution = MediaAttribution("MusicBrainz", "CC0", null)),
                MediaAssignmentState.CHOSEN_BY_USER), onChange = {})
            MediaMetadataSummary(state(3, "The.Office.S03.720p.WEB-DL").copy(assignment = TransferMediaAssignment(key(3), MediaAssignmentState.SUGGESTED,
                suggestions = listOf(b5))), onChange = {})
            MediaMetadataSummary(state(4, "random_stuff").copy(assignment = TransferMediaAssignment(key(4), MediaAssignmentState.COULDNT_IDENTIFY)), onChange = {})
        } } } }
        rule.onRoot().captureRoboImage("build/chunks-visual/media-detail-summary.png")
        rule.onAllNodesWithText("Change match or artwork…").fetchSemanticsNodes().let { assertEquals(3, it.size) }
        rule.onNodeWithText("Couldn't identify").assertExists()
        rule.onNodeWithText("Chosen by you").assertExists()
        rule.onNodeWithText("Suggested").assertExists()
        rule.onAllNodesWithText("Change match or artwork…")[0].assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, changed)
    }

    @Test fun identifyPanelConsentOffThenSuggestions() {
        var enabled = 0; var chosen: MediaCandidate? = null; var category: MediaKind? = null
        val office = { id: String, year: Int -> MediaCandidate(MediaMetadataCacheKey("tvmaze", "show", id), MediaKind.TV_SERIES, "The Office", year,
            creators = listOf(if (id == "526") "NBC" else "BBC Two"), attribution = attribution) }
        val suggested = state(3, "The.Office.S03.720p.WEB-DL").copy(assignment = TransferMediaAssignment(key(3), MediaAssignmentState.SUGGESTED,
            suggestions = listOf(office("526", 2005), office("1292", 2001)), attempts = listOf(ProviderAttempt("tvmaze", "Found 10"))))
        var consent by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent { ChunksTheme { Surface { Column(Modifier.background(ChunksColors.Background).padding(12.dp).verticalScroll(rememberScrollState())) {
            MediaIdentifyPanel(suggested, MediaPrivacySettings(onlineLookupConsent = consent), onEnableLookup = { enabled++ }, onLookup = {},
                onSearch = { _, _, _ -> }, onChoose = { chosen = it }, onUseCategoryArt = { category = it }, onReset = {})
        } } } }
        rule.onRoot().captureRoboImage("build/chunks-visual/media-identify-consent-off.png")
        rule.onNodeWithText("Turn on online lookup…").performClick(); assertEquals(1, enabled)
        consent = true
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/media-identify-suggestions.png")
        rule.onNodeWithText("BBC Two", substring = true).performClick()
        assertEquals("1292", chosen!!.ref.externalId)
        rule.onNodeWithContentDescription("Use Book art").performClick(); assertEquals(MediaKind.BOOK, category)
        rule.onNodeWithText("Look up again").assertHeightIsAtLeast(48.dp)
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading).and(SemanticsMatcher.expectValue(SemanticsProperties.Text,
            listOf(androidx.compose.ui.text.AnnotatedString("Possible matches"))))).assertExists()
    }

    @Test fun privacySettingsPanel() {
        var latest by androidx.compose.runtime.mutableStateOf(MediaPrivacySettings())
        val providers = listOf(TvMazeProvider(), TmdbProvider { null }, MusicBrainzProvider(), OpenLibraryProvider()).map { it.info }
        rule.setContent { ChunksTheme { Surface { Column(Modifier.background(ChunksColors.Background).padding(12.dp).verticalScroll(rememberScrollState())) {
            MediaPrivacySettingsPanel(latest, providers, configured = { false }, onChange = { latest = it }, onSetProviderKey = { _, _ -> },
                cacheSummary = "Cached: 0.0 MB artwork · 0 lookups", onClearData = {})
        } } } }
        rule.onRoot().captureRoboImage("build/chunks-visual/media-settings.png")
        rule.onNodeWithText("Online lookup").performClick()
        assertTrue(latest.onlineLookupConsent)
        // Third-party consent is independent of the previously chosen automation setting.
        rule.onNodeWithText("Identify automatically").performClick()
        assertEquals(false, latest.automaticLookup)
        rule.onNodeWithText("Online lookup").performClick()
        assertEquals(false, latest.onlineLookupConsent)
        rule.onNodeWithText("Online lookup").performClick()
        assertTrue(latest.onlineLookupConsent)
        assertEquals(false, latest.automaticLookup)
        rule.onNodeWithText("Add your key…").assertExists()
    }
}
