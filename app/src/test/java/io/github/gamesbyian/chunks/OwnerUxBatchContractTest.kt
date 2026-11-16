package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.media.*
import io.github.gamesbyian.chunks.model.*
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.*
import org.junit.Test

/** Independent state oracles for the November 15 owner decisions, separate from UI copy. */
class OwnerUxBatchContractTest {
    private fun profile(id: String, name: String = "Wood") = RememberedRemoteProfileRoster.Profile(
        id, name, RemoteCredentials("https://example.test/$id/RPC2", "fixture", "fixture"))

    @Test fun rosterUsesIdsAndStableConnectedFirstPartitionIncludingDuplicateNames() {
        val a = profile("one"); val b = profile("two"); val c = profile("three")
        val roster = unifiedConnectionRoster(listOf(a, b, c, b), b.id, b.label)
        assertEquals(listOf("two", "one", "three"), roster.map { it.id })
        assertEquals(1, roster.count { it.connected })
        assertEquals(3, roster.size)
        assertEquals(3, roster.map { it.disambiguation }.distinct().size)
    }
    @Test fun rememberedCredentialsAndLastVerifiedTimeNeverEstablishLiveSession() {
        val p = profile("one").copy(lastVerifiedAt = System.currentTimeMillis())
        assertFalse(unifiedConnectionRoster(listOf(p), null, null).single().connected)
        val stale = unifiedConnectionRoster(listOf(p), p.id, p.label, sessionVerified = false).single()
        assertFalse(stale.connected); assertTrue(stale.needsAttention)
    }
    @Test fun savedAddAuthenticationRequestRemainsAnExplicitNavigationPromise() {
        val request = SavedAddProfileReconnect()
        assertFalse(request.awaitingAuthentication)
        request.requestAuthentication("exact-profile")
        assertTrue(request.awaitingAuthentication)
        assertFalse(request.consumeAuthenticated("another-profile"))
        assertFalse(request.awaitingAuthentication)
    }
    @Test fun unsavedVerifiedSessionAppearsOnceWithoutClaimingRememberedCredentials() {
        val roster = unifiedConnectionRoster(listOf(profile("saved")), "unsaved", "Temporary")
        assertEquals(2, roster.size)
        assertEquals("unsaved", roster.first().id)
        assertNull(roster.first().profile)
        assertTrue(roster.first().connected)
        assertTrue(unifiedConnectionRoster(emptyList(), null, null).isEmpty())
    }
    @Test fun selectedClientsIncludeOfflineLockedEmptyAndMissingButIgnoreSearchAndRates() {
        val ids = (0..3).map { EnvironmentId("scope-$it") }
        val snapshots = listOf(
            EnvironmentTransferSnapshot(ids[0], EnvironmentAvailability.LIVE, true, 1, emptyList()),
            EnvironmentTransferSnapshot(ids[1], EnvironmentAvailability.OFFLINE, false, null, emptyList()),
            EnvironmentTransferSnapshot(ids[2], EnvironmentAvailability.LOCKED, false, null, emptyList()))
        val all = UnifiedTransferCatalog.query(snapshots, UnifiedTransferQuery(search = "no rows"))
        assertEquals(3, includedClientCount(all))
        val selected = UnifiedTransferCatalog.query(snapshots, UnifiedTransferQuery(
            scope = EnvironmentViewScope.Only(setOf(ids[1], ids[3])), search = "still no rows"))
        assertEquals(2, includedClientCount(selected))
        assertEquals(0, includedClientCount(UnifiedTransferCatalog.query(snapshots,
            UnifiedTransferQuery(scope = EnvironmentViewScope.Only(emptySet())))))
        assertEquals(1, includedClientCount(UnifiedTransferCatalog.query(snapshots,
            UnifiedTransferQuery(scope = EnvironmentViewScope.Only(setOf(ids[0]))))))
    }
    @Test fun categoryPreferencesCoverEveryApprovedFormatAndDoNotForceActualDimensions() {
        for (kind in listOf(MediaKind.MOVIE, MediaKind.TV_SERIES, MediaKind.TV_SEASON,
            MediaKind.TV_EPISODE, MediaKind.BOOK, MediaKind.COMIC)) {
            assertEquals(2f / 3f, kind.preferredArtworkRatio, 0.001f)
            assertEquals(ArtworkRole.POSTER, kind.preferredArtworkRole)
        }
        assertEquals(0.75f, MediaKind.MAGAZINE.preferredArtworkRatio, 0.001f)
        for (kind in listOf(MediaKind.MUSIC_ALBUM, MediaKind.AUDIOBOOK, MediaKind.SOFTWARE))
            assertEquals(1f, kind.preferredArtworkRatio, 0.001f)
        for ((w, h) in listOf(200 to 300, 100 to 100, 300 to 400, 1200 to 200, 150 to 1200)) {
            val (width, height) = artworkBounds(w, h, 60f, 60f)
            assertEquals(w.toFloat() / h, width / height, 0.001f)
            assertTrue(width <= 60.001f && height <= 60.001f)
        }
    }
}
