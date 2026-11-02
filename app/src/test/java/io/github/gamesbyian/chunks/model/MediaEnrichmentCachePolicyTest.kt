package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaEnrichmentCachePolicyTest {
    private val key = MediaMetadataCacheKey("musicbrainz", "recording", "uuid")
    private fun receipt(state: MediaMetadataReceiptState, revision: Long = 1,
                        expires: Long = 2000, retry: Long? = null, schema: Int = 1) =
        MediaMetadataCacheReceipt(key, revision, schema, 1000, expires, state, retry)
    private fun decision(
        policy: MediaEnrichmentCachePolicy, now: Long = 1500, approved: Set<String> = setOf("musicbrainz"),
        consent: Boolean = true, visible: Boolean = true, network: Boolean = true,
        schema: Int = 1, stale: Boolean = false
    ) = policy.decide(key, approved, consent, visible, network, schema, now, stale)

    @Test fun verifiedPositiveIsFreshOnlyWithinExpiryAndCorrectSchema() {
        val cache = MediaEnrichmentCachePolicy.build(listOf(receipt(MediaMetadataReceiptState.VERIFIED)))
        assertEquals(MediaMetadataCacheDecision.FRESH_VERIFIED, decision(cache))
        assertEquals(MediaMetadataCacheDecision.FETCH_ELIGIBLE, decision(cache, now=2000))
        assertEquals(MediaMetadataCacheDecision.FETCH_ELIGIBLE, decision(cache, now=2000, stale=true))
        assertEquals(MediaMetadataCacheDecision.STALE_DISPLAY_ONLY, decision(cache, now=2000, network=false, stale=true))
        assertEquals(MediaMetadataCacheDecision.OFFLINE_MISS, decision(cache, now=2000, network=false))
        assertEquals(MediaMetadataCacheDecision.SCHEMA_QUARANTINE, decision(cache, schema=2))
    }

    @Test fun optOutUnapprovedSourceAndHiddenMemberAlwaysForbidEvenFreshCache() {
        val cache = MediaEnrichmentCachePolicy.build(listOf(receipt(MediaMetadataReceiptState.VERIFIED)))
        assertEquals(MediaMetadataCacheDecision.FORBIDDEN, decision(cache, consent=false))
        assertEquals(MediaMetadataCacheDecision.FORBIDDEN, decision(cache, visible=false))
        assertEquals(MediaMetadataCacheDecision.FORBIDDEN, decision(cache, approved=emptySet()))
    }

    @Test fun missingAndNegativeCacheAvoidRepeatedProbesAndRespectOffline() {
        val empty = MediaEnrichmentCachePolicy.build(emptyList())
        assertEquals(MediaMetadataCacheDecision.OFFLINE_MISS, decision(empty, network=false))
        assertEquals(MediaMetadataCacheDecision.FETCH_ELIGIBLE, decision(empty))
        val negative = MediaEnrichmentCachePolicy.build(listOf(receipt(MediaMetadataReceiptState.NOT_FOUND)))
        assertEquals(MediaMetadataCacheDecision.NEGATIVE_CACHE, decision(negative))
        assertEquals(MediaMetadataCacheDecision.OFFLINE_MISS, decision(negative, now=2100, network=false))
        assertEquals(MediaMetadataCacheDecision.FETCH_ELIGIBLE, decision(negative, now=2100))
    }

    @Test fun throttleAndFailuresHaveExplicitRetryGate() {
        val throttle = MediaEnrichmentCachePolicy.build(listOf(receipt(MediaMetadataReceiptState.RATE_LIMITED,
            expires=2000, retry=3000)))
        assertEquals(MediaMetadataCacheDecision.WAIT_FOR_RETRY, decision(throttle, now=2500))
        assertEquals(MediaMetadataCacheDecision.FETCH_ELIGIBLE, decision(throttle, now=3000))
        val failure = MediaEnrichmentCachePolicy.build(listOf(receipt(MediaMetadataReceiptState.TEMPORARY_FAILURE)))
        assertEquals(MediaMetadataCacheDecision.WAIT_FOR_RETRY, decision(failure))
        assertEquals(MediaMetadataCacheDecision.FETCH_ELIGIBLE, decision(failure, now=3000))
    }

    @Test fun malformedResponseAndDuplicateRevisionCannotBecomeVerified() {
        val invalid = MediaEnrichmentCachePolicy.build(listOf(receipt(MediaMetadataReceiptState.UNSUPPORTED_SCHEMA)))
        assertEquals(MediaMetadataCacheDecision.SCHEMA_QUARANTINE, decision(invalid))
        val conflicted = MediaEnrichmentCachePolicy.build(listOf(
            receipt(MediaMetadataReceiptState.VERIFIED), receipt(MediaMetadataReceiptState.NOT_FOUND)))
        assertEquals(MediaMetadataCacheDecision.SCHEMA_QUARANTINE, decision(conflicted))
    }

    @Test fun newestRevisionSupersedesOldSuccessAndIdsNeverCollideAcrossEntityTypes() {
        val superseded = MediaEnrichmentCachePolicy.build(listOf(
            receipt(MediaMetadataReceiptState.VERIFIED), receipt(MediaMetadataReceiptState.NOT_FOUND, revision=2)))
        assertEquals(MediaMetadataCacheDecision.NEGATIVE_CACHE, decision(superseded))
        assertFalse(MediaMetadataCacheKey("tmdb","person","42") == MediaMetadataCacheKey("tmdb","film","42"))
        assertThrows(IllegalArgumentException::class.java) { MediaMetadataCacheKey("tmdb", "person", "") }
    }
}
