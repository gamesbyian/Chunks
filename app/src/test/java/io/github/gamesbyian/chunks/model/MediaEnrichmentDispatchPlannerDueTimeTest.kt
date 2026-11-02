package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaEnrichmentDispatchPlannerDueTimeTest {
    private val key = MediaMetadataCacheKey("musicbrainz", "recording", "known-mbid")
    private val budget = MediaProviderDispatchBudget("musicbrainz", 1000L, 1, 10)
    private fun due(origin: MediaLookupOrigin, at: Long, references: Int = 1) =
        MediaLookupIntent(key, origin, references, at)
    private fun plan(intents: List<MediaLookupIntent>, now: Long = 10000L,
                     metered: Boolean = false, saver: Boolean = false) =
        MediaEnrichmentDispatchPlanner.plan(
            intents,
            MediaEnrichmentDispatchScope(now, true, metered, saver, true, setOf("musicbrainz")),
            listOf(budget),
            mapOf(key to MediaMetadataCacheDecision.FETCH_ELIGIBLE)
        )

    @Test fun futureUserIntentMustNotPromoteDueBackgroundOverMeteredGate() {
        val intents = listOf(
            due(MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT, at=100L),
            due(MediaLookupOrigin.USER_REQUEST, at=20000L)
        )
        assertTrue(plan(intents, metered=true).isEmpty())
        assertTrue(plan(intents, saver=true).isEmpty())
        assertEquals(listOf(MediaLookupDispatch(key, MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT)),
            plan(intents))
        assertEquals(listOf(MediaLookupDispatch(key, MediaLookupOrigin.USER_REQUEST)),
            plan(intents, now=20000L, metered=true, saver=true))
    }

    @Test fun futureOrInvisibleIntentsAreNeverPromotedByDeduplication() {
        val intents = listOf(
            due(MediaLookupOrigin.USER_REQUEST, at=20000L),
            due(MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT, at=1L, references=0)
        )
        assertTrue(plan(intents).isEmpty())
        assertTrue(plan(intents, metered=true).isEmpty())
        assertEquals(listOf(MediaLookupDispatch(key, MediaLookupOrigin.USER_REQUEST)),
            plan(intents, now=20000L))
    }

    @Test fun dueUserRequestStillWinsOverDueBackgroundRegardlessOfInputOrder() {
        val user = due(MediaLookupOrigin.USER_REQUEST, at=9000L)
        val background = due(MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT, at=100L)
        val expected = listOf(MediaLookupDispatch(key, MediaLookupOrigin.USER_REQUEST))
        assertEquals(expected, plan(listOf(user, background), metered=true))
        assertEquals(expected, plan(listOf(background, user), saver=true))
    }
}
