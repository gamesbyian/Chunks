package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaEnrichmentDispatchPlannerTest {
    private fun key(provider: String, id: String) = MediaMetadataCacheKey(provider,"work",id)
    private fun intent(provider: String, id: String, origin: MediaLookupOrigin = MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT,
                       refs: Int = 1) = MediaLookupIntent(key(provider,id),origin,refs)
    private fun budget(provider: String, interval: Long = 1000L, last: Long? = null,
                       retry: Long? = null, daily: Int = 100, inFlight: Int = 0) =
        MediaProviderDispatchBudget(provider,interval,1,daily,last,retry,inFlight)
    private fun scope(online: Boolean = true, consent: Boolean = true, metered: Boolean = false,
                      saver: Boolean = false, approved: Set<String> = setOf("musicbrainz","openlibrary")) =
        MediaEnrichmentDispatchScope(10000,online,metered,saver,consent,approved)
    private fun plan(intents: List<MediaLookupIntent>,scope: MediaEnrichmentDispatchScope = scope(),
                     budgets: List<MediaProviderDispatchBudget> = listOf(budget("musicbrainz"),budget("openlibrary")),
                     allowed: Set<MediaMetadataCacheKey> = intents.map { it.key }.toSet()) =
        MediaEnrichmentDispatchPlanner.plan(intents,scope,budgets,
            allowed.associateWith { MediaMetadataCacheDecision.FETCH_ELIGIBLE })

    @Test fun offlineRevocationAndUnapprovedProvidersNeverDispatch() {
        val pending=listOf(intent("musicbrainz","a"))
        assertTrue(plan(pending,scope(online=false)).isEmpty())
        assertTrue(plan(pending,scope(consent=false)).isEmpty())
        assertTrue(plan(pending,scope(approved=emptySet())).isEmpty())
        assertTrue(plan(pending,allowed=emptySet()).isEmpty())
    }

    @Test fun missingSourceVisibilityBlocksEvenPreviouslyQueuedCandidate() {
        val pending=listOf(intent("musicbrainz","a",refs=0))
        assertTrue(plan(pending).isEmpty())
    }

    @Test fun userRequestedLookupCanRunOnMeteredAndPowerSaverButBackgroundWaits() {
        val pending=listOf(intent("musicbrainz","a"),intent("openlibrary","b",MediaLookupOrigin.USER_REQUEST))
        assertEquals(listOf(key("openlibrary","b")),
            plan(pending,scope(metered=true,saver=true)).map{it.key})
    }

    @Test fun oneProviderOneSelectionAndDistinctProviderFairness() {
        val pending=listOf(intent("musicbrainz","a"),intent("musicbrainz","b"),intent("openlibrary","b"))
        val chosen=plan(pending)
        assertEquals(2,chosen.size)
        assertEquals(1,chosen.count{it.key.provider=="musicbrainz"})
        assertEquals(1,chosen.count{it.key.provider=="openlibrary"})
    }

    @Test fun typedDuplicateIdAcrossTwoSourcesConsumesOneCallAndUserPriorityWins() {
        val pending=listOf(intent("musicbrainz","a"),
            intent("musicbrainz","a",MediaLookupOrigin.USER_REQUEST),intent("musicbrainz","b"))
        val chosen=plan(pending)
        assertEquals(1,chosen.size)
        assertEquals(key("musicbrainz","a"),chosen.single().key)
        assertEquals(MediaLookupOrigin.USER_REQUEST,chosen.single().origin)
    }

    @Test fun perProviderCooldownQuotaInFlightAndBackwardsClockGate() {
        val pending=listOf(intent("musicbrainz","a"))
        assertTrue(plan(pending,budgets=listOf(budget("musicbrainz",last=9501))).isEmpty())
        assertTrue(plan(pending,budgets=listOf(budget("musicbrainz",last=11000))).isEmpty())
        assertTrue(plan(pending,budgets=listOf(budget("musicbrainz",retry=10001))).isEmpty())
        assertTrue(plan(pending,budgets=listOf(budget("musicbrainz",daily=0))).isEmpty())
        assertTrue(plan(pending,budgets=listOf(budget("musicbrainz",inFlight=1))).isEmpty())
        assertEquals(1,plan(pending,budgets=listOf(budget("musicbrainz",last=9000))).size)
    }

    @Test fun freshCacheAndNegativeCacheMustBeFilteredBeforeDispatch() {
        val intents=listOf(intent("musicbrainz","a"),intent("openlibrary","b"))
        assertEquals(listOf(key("openlibrary","b")),plan(intents,allowed=setOf(key("openlibrary","b"))).map{it.key})
    }

    @Test fun oldestWithinPriorityWinsWithoutStarvingBackgroundQueue() {
        val queued=listOf(
            intent("musicbrainz","new").copy(queuedAtEpochMs=9000),
            intent("musicbrainz","old").copy(queuedAtEpochMs=1000)
        )
        assertEquals(key("musicbrainz","old"),plan(queued).single().key)
        val future=queued.map { it.copy(queuedAtEpochMs=20000) }
        assertTrue(plan(future).isEmpty())
    }

    @Test fun neverAcceptMalformedBudgetOrUnboundedBatch() {
        val pending=listOf(intent("musicbrainz","a"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MediaEnrichmentDispatchPlanner.plan(pending,scope(),listOf(budget("musicbrainz")),emptyMap(),0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MediaEnrichmentDispatchPlanner.plan(pending,scope(),listOf(budget("musicbrainz"),budget("musicbrainz")),emptyMap())
        }
    }
}
