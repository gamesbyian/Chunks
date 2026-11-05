package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.MediaMetadataCacheKey
import io.github.gamesbyian.chunks.model.MediaRelationIndex
import io.github.gamesbyian.chunks.model.MediaRelationKind
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRelationBridgeTest {
    private val attr = MediaAttribution("TVmaze", "CC BY-SA 4.0", null)
    private fun key(env: String, n: Int) = UnifiedTransferKey.of(EnvironmentId(env), "%040x".format(n))
    private fun episode(show: String, s: Int, e: Int, title: String = "Babylon 5") =
        MediaCandidate(MediaMetadataCacheKey("tvmaze", "show", show), MediaKind.TV_EPISODE, title, season = s, episode = e, attribution = attr)
    private fun assigned(k: UnifiedTransferKey, c: MediaCandidate, st: MediaAssignmentState = MediaAssignmentState.AUTOMATIC) =
        k to TransferMediaAssignment(MediaTransferKey.of(k), st, c)

    @Test fun confirmedEpisodesBecomeAdjacentAndSameSeriesRelations() {
        val e7 = key("phone", 1); val e8 = key("rtorrent:a", 2); val other = key("phone", 3); val suggested = key("phone", 4)
        val members = MediaRelationBridge.members(listOf(
            assigned(e7, episode("475", 2, 7)), assigned(e8, episode("475", 2, 8), MediaAssignmentState.CHOSEN_BY_USER),
            // Same title, different provider identity (TVmaze 57926): must never relate by title.
            assigned(other, episode("57926", 2, 8)),
            assigned(suggested, episode("475", 2, 9), MediaAssignmentState.SUGGESTED)))
        assertEquals(3, members.size)
        val index = MediaRelationIndex.build(members)
        val related = index.similar(e7, setOf(e7, e8, other, suggested))
        assertEquals(listOf(e8), related.map { it.key })
        assertTrue(related.single().evidence.any { it.kind == MediaRelationKind.ADJACENT_EPISODE || it.kind == MediaRelationKind.SAME_SERIES })
    }

    @Test fun unconfirmedOrCategoryOnlyContributesNothing() {
        val k = key("phone", 9)
        assertNull(MediaRelationBridge.members(k, null))
        assertNull(MediaRelationBridge.members(k, TransferMediaAssignment(MediaTransferKey.of(k), MediaAssignmentState.CATEGORY_ART_BY_USER,
            episode("475", 1, 1))))
        val movie = MediaCandidate(MediaMetadataCacheKey("tmdb", "movie", "949"), MediaKind.MOVIE, "Heat", 1995, attribution = attr)
        assertEquals("tmdb:movie:949", MediaRelationBridge.members(k, TransferMediaAssignment(MediaTransferKey.of(k),
            MediaAssignmentState.AUTOMATIC, movie))!!.members.single().workId)
    }
}
