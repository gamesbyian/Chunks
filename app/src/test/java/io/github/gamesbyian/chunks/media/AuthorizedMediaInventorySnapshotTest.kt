package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedMediaInventorySnapshotTest {
    private val phone = EnvironmentId("phone")
    private val remote = EnvironmentId("rtorrent:profile-2")
    private val hash = "a".repeat(40)
    private fun snapshot(env: EnvironmentId, name: String, status: EnvironmentAvailability =
        EnvironmentAvailability.LIVE, whenRead: Long? = 1234L) =
        EnvironmentTransferSnapshot(env, status, true, whenRead,
            listOf(UnifiedTransferObservation(UnifiedTransferKey.of(env, hash), name, name,
                null, null, null, null, null, null)))
    private fun key(env: EnvironmentId) =
        MediaTransferKey.of(UnifiedTransferKey.of(env, hash))

    @Test fun sameHashOnTwoAuthorizedProfilesNeverBorrowsAnotherProfilesConsent() {
        val sources = listOf(snapshot(phone, "Hackers.1995"), snapshot(remote, "Away.We.Go.2009"))
        val scopes = mapOf(phone to "local-engine", remote to "authenticated-account-2")
        val initial = AuthorizedMediaInventorySnapshot.build(sources, scopes)
        assertEquals(2, initial.entries.size)
        assertTrue(initial.stillAuthorizes(key(phone), initial))
        assertTrue(initial.stillAuthorizes(key(remote), initial))
        val otherAccount = AuthorizedMediaInventorySnapshot.build(sources,
            scopes + (remote to "authenticated-account-3"))
        assertTrue(initial.stillAuthorizes(key(phone), otherAccount))
        assertFalse(initial.stillAuthorizes(key(remote), otherAccount))
    }

    @Test fun sourceLockStalenessAndMissingAccountRevokeOnlyTheirKeys() {
        val sources = listOf(snapshot(phone, "Hackers.1995"), snapshot(remote, "Away.We.Go.2009"))
        val scopes = mapOf(phone to "local", remote to "remote-account")
        val start = AuthorizedMediaInventorySnapshot.build(sources, scopes)
        for (status in listOf(EnvironmentAvailability.LOCKED,
            EnvironmentAvailability.OFFLINE, EnvironmentAvailability.STALE)) {
            val current = AuthorizedMediaInventorySnapshot.build(
                listOf(sources.first(), snapshot(remote, "Away.We.Go.2009", status)), scopes)
            assertFalse(start.stillAuthorizes(key(remote), current))
            assertTrue(start.stillAuthorizes(key(phone), current))
        }
        val missing = AuthorizedMediaInventorySnapshot.build(sources, mapOf(phone to "local"))
        assertEquals(1, missing.entries.size)
        assertFalse(start.stillAuthorizes(key(remote), missing))
        assertFalse(start.stillAuthorizes(key(remote),
            AuthorizedMediaInventorySnapshot.build(sources,
                mapOf(phone to "local", remote to ""))))
    }

    @Test fun refreshedSourceReadAndReusedHashWithDifferentNameInvalidateOldRequest() {
        val scopes = mapOf(phone to "local")
        val initial = AuthorizedMediaInventorySnapshot.build(listOf(snapshot(phone, "Hackers.1995")), scopes)
        val reread = AuthorizedMediaInventorySnapshot.build(
            listOf(snapshot(phone, "Hackers.1995", whenRead = 1240L)), scopes)
        val renamed = AuthorizedMediaInventorySnapshot.build(
            listOf(snapshot(phone, "Other.2026")), scopes)
        val gone = AuthorizedMediaInventorySnapshot.build(emptyList(), scopes)
        assertTrue("A newer valid read must not starve in-flight offscreen poster lookup",
            initial.stillAuthorizes(key(phone), reread))
        assertEquals("Unchanged profile/job across fresh reads should not reschedule work",
            initial, reread)
        assertFalse(initial.stillAuthorizes(key(phone), renamed))
        assertFalse(initial.stillAuthorizes(key(phone), gone))
        assertTrue(initial.stillAuthorizes(key(phone), initial))
    }

    @Test fun mixedEnvironmentRowsCannotBorrowTheSourceAccountsAuthorization() {
        val scopes = mapOf(phone to "authenticated-phone")
        val authorized = AuthorizedMediaInventorySnapshot.build(
            listOf(snapshot(phone, "Hackers.1995")), scopes)
        // Source declares itself to be phone, but its second row is a
        // different rTorrent profile with the same infohash. No part of this
        // untrustworthy inventory can authorize external provider HTTP.
        val mixed = EnvironmentTransferSnapshot(phone, EnvironmentAvailability.LIVE, true, 1235L,
            listOf(
                UnifiedTransferObservation(UnifiedTransferKey.of(phone, hash),
                    "Hackers.1995", "Hackers.1995", null, null, null, null, null, null),
                UnifiedTransferObservation(UnifiedTransferKey.of(remote, hash),
                    "Private.Remote.Transfer", "Private.Remote.Transfer",
                    null, null, null, null, null, null)))
        val projection = AuthorizedMediaInventorySnapshot.build(listOf(mixed), scopes)
        assertTrue("Mixed source/profile must fail the whole projection closed",
            projection.entries.isEmpty())
        assertFalse(authorized.stillAuthorizes(key(phone), projection))
        assertFalse(projection.stillAuthorizes(key(remote), projection))
    }

    @Test fun duplicateSameEnvironmentHashCannotSilentlyReplaceTheTitleWitness() {
        val duplicated = EnvironmentTransferSnapshot(phone, EnvironmentAvailability.LIVE, true, 1235L,
            listOf(
                UnifiedTransferObservation(UnifiedTransferKey.of(phone, hash),
                    "First.Title", "First.Title", null, null, null, null, null, null),
                UnifiedTransferObservation(UnifiedTransferKey.of(phone, hash),
                    "Second.Title", "Second.Title", null, null, null, null, null, null)))
        val projection = AuthorizedMediaInventorySnapshot.build(
            listOf(duplicated), mapOf(phone to "local"))
        assertTrue("Conflicting rows must not overwrite the account/title witness",
            projection.entries.isEmpty())
        assertFalse(projection.stillAuthorizes(key(phone), projection))
    }

    @Test fun incompleteOrAmbiguousSourcesCannotAuthorizeInventedJobs() {
        val live = snapshot(phone, "Hackers.1995")
        val duplicates = AuthorizedMediaInventorySnapshot.build(listOf(live, live), mapOf(phone to "local"))
        assertTrue(duplicates.entries.isEmpty())
        assertFalse(duplicates.stillAuthorizes(key(phone), duplicates))
        val noTimestamp = AuthorizedMediaInventorySnapshot.build(
            listOf(snapshot(phone, "Hackers.1995", whenRead = null)), mapOf(phone to "local"))
        assertTrue(noTimestamp.entries.isEmpty())
        val noRemote = AuthorizedMediaInventorySnapshot.build(
            listOf(snapshot(remote, "Away.We.Go.2009")), mapOf(phone to "local"))
        assertTrue(noRemote.entries.isEmpty())
    }
}
