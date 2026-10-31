package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import org.junit.Assert.*
import org.junit.Test

class CombinedAliasProjectionTest {
    private val phone = EnvironmentSnapshots.PHONE
    private val alice = EnvironmentId("a".repeat(64))
    private val bob = EnvironmentId("b".repeat(64))
    private val hash = "c".repeat(40)
    private val phoneScope = "on-this-phone"
    private val aliceScope = "https://server.example/RPC2\u0000alice"
    private val bobScope = "https://server.example/RPC2\u0000bob"

    private fun source(
        id: EnvironmentId,
        availability: EnvironmentAvailability = EnvironmentAvailability.LIVE
    ) = EnvironmentTransferSnapshot(
        id, availability, true, 1234,
        listOf(UnifiedTransferObservation(
            UnifiedTransferKey.of(id, hash), "Original title", "Original title",
            100L, 500_000, false, null, null, null))
    )

    @Test fun sameHashNeverSharesAliasBetweenPhoneOrSeparateAccounts() {
        val sources = listOf(source(phone), source(alice), source(bob))
        val scopes = mapOf(phone to phoneScope, alice to aliceScope, bob to bobScope)
        val requests = CombinedAliasProjection.requests(sources, scopes)
        assertEquals(3, requests.size)
        val values = mapOf(
            CombinedAliasRequest(UnifiedTransferKey.of(phone, hash), phoneScope) to "Phone nickname",
            CombinedAliasRequest(UnifiedTransferKey.of(alice, hash), aliceScope) to "Alice nickname",
            CombinedAliasRequest(UnifiedTransferKey.of(bob, hash), bobScope) to "Bob nickname")
        val projected = CombinedAliasProjection.apply(sources, scopes, values)
        assertEquals(listOf("Phone nickname", "Alice nickname", "Bob nickname"),
            projected.map { it.transfers.single().displayName })
        assertEquals(sources.map { it.transfers.single().key },
            projected.map { it.transfers.single().key })
        assertTrue(projected.all { it.transfers.single().originalName == "Original title" })
    }

    @Test fun oldAccountAliasesNeverReappearAfterProfileScopeSwitch() {
        val sources = listOf(source(alice))
        val old = mapOf(CombinedAliasRequest(UnifiedTransferKey.of(alice, hash), aliceScope) to "Previous account")
        val updated = mapOf(alice to bobScope)
        assertEquals("Original title",
            CombinedAliasProjection.apply(sources, updated, old).single().transfers.single().displayName)
        assertEquals(setOf(CombinedAliasRequest(UnifiedTransferKey.of(alice, hash), bobScope)),
            CombinedAliasProjection.requests(sources, updated))
    }

    @Test fun lockedSourcesNeverRequestOrApplyCachedNames() {
        val locked = listOf(source(alice, EnvironmentAvailability.LOCKED))
        val scopes = mapOf(alice to aliceScope)
        val cached = mapOf(CombinedAliasRequest(UnifiedTransferKey.of(alice, hash), aliceScope) to "Private alias")
        assertTrue(CombinedAliasProjection.requests(locked, scopes).isEmpty())
        assertEquals("Original title", CombinedAliasProjection.apply(locked, scopes, cached)
            .single().transfers.single().displayName)
        assertTrue(CombinedAliasProjection.requests(locked, emptyMap()).isEmpty())
    }

    @Test fun unconfiguredOrOfflineEnvironmentCannotInheritSomeOtherScope() {
        val sources = listOf(source(alice), source(bob, EnvironmentAvailability.OFFLINE))
        val scopes = mapOf(alice to aliceScope)
        val values = mapOf(CombinedAliasRequest(UnifiedTransferKey.of(bob, hash), bobScope) to "Hidden")
        assertEquals(1, CombinedAliasProjection.requests(sources, scopes).size)
        assertTrue(CombinedAliasProjection.apply(sources, scopes, values)
            .all { it.transfers.single().displayName == "Original title" })
    }

    @Test fun staleNamesNeverReplaceRawFactsOnEmptyOrMissingRead() {
        val sources = listOf(source(phone, EnvironmentAvailability.STALE))
        val scopes = mapOf(phone to phoneScope)
        val projected = CombinedAliasProjection.apply(sources, scopes, emptyMap())
        assertEquals("Original title", projected.single().transfers.single().displayName)
        assertEquals(EnvironmentAvailability.STALE, projected.single().availability)
        assertEquals(500_000, projected.single().transfers.single().progressPpm)
    }

    @Test fun zeroLengthAliasScopeCannotBecomeDefaultCrossAccountAuthority() {
        try {
            CombinedAliasRequest(UnifiedTransferKey.of(alice, hash), "")
            fail("A missing server scope must be refused")
        } catch (_: IllegalArgumentException) { }
    }
}
