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
        EnvironmentAvailability.LIVE, whenRead: Long? = 1234L, complete: Boolean = true) =
        EnvironmentTransferSnapshot(env, status, complete, whenRead,
            listOf(UnifiedTransferObservation(UnifiedTransferKey.of(env, hash), name, name,
                null, null, null, null, null, null)))
    private fun key(env: EnvironmentId) =
        MediaTransferKey.of(UnifiedTransferKey.of(env, hash))

    private fun build(sources: List<EnvironmentTransferSnapshot>,
                      scopes: Map<EnvironmentId, String>,
                      epoch: String = "privacy-session-1") =
        AuthorizedMediaInventorySnapshot.build(sources, scopes, epoch)

    @Test fun sameHashOnTwoAuthorizedProfilesNeverBorrowsAnotherProfilesConsent() {
        val sources = listOf(snapshot(phone, "Hackers.1995"), snapshot(remote, "Away.We.Go.2009"))
        val scopes = mapOf(phone to "local-engine", remote to "authenticated-account-2")
        val initial = build(sources, scopes)
        assertEquals(2, initial.entries.size)
        assertTrue(initial.stillAuthorizes(key(phone), initial))
        assertTrue(initial.stillAuthorizes(key(remote), initial))
        val otherAccount = build(sources,
            scopes + (remote to "authenticated-account-3"))
        assertTrue(initial.stillAuthorizes(key(phone), otherAccount))
        assertFalse(initial.stillAuthorizes(key(remote), otherAccount))
    }

    @Test fun sourceLockStalenessAndMissingAccountRevokeOnlyTheirKeys() {
        val sources = listOf(snapshot(phone, "Hackers.1995"), snapshot(remote, "Away.We.Go.2009"))
        val scopes = mapOf(phone to "local", remote to "remote-account")
        val start = build(sources, scopes)
        for (status in listOf(EnvironmentAvailability.LOCKED,
            EnvironmentAvailability.OFFLINE, EnvironmentAvailability.STALE)) {
            val current = build(
                listOf(sources.first(), snapshot(remote, "Away.We.Go.2009", status)), scopes)
            assertFalse(start.stillAuthorizes(key(remote), current))
            assertTrue(start.stillAuthorizes(key(phone), current))
        }
        val missing = build(sources, mapOf(phone to "local"))
        assertEquals(1, missing.entries.size)
        assertFalse(start.stillAuthorizes(key(remote), missing))
        assertFalse(start.stillAuthorizes(key(remote),
            build(sources,
                mapOf(phone to "local", remote to ""))))
    }

    @Test fun refreshedSourceReadAndReusedHashWithDifferentNameInvalidateOldRequest() {
        val scopes = mapOf(phone to "local")
        val initial = build(listOf(snapshot(phone, "Hackers.1995")), scopes)
        val reread = build(
            listOf(snapshot(phone, "Hackers.1995", whenRead = 1240L)), scopes)
        val renamed = build(
            listOf(snapshot(phone, "Other.2026")), scopes)
        val gone = build(emptyList(), scopes)
        assertTrue("A newer valid read must not starve in-flight offscreen poster lookup",
            initial.stillAuthorizes(key(phone), reread))
        assertEquals("Unchanged profile/job across fresh reads should not reschedule work",
            initial, reread)
        assertFalse(initial.stillAuthorizes(key(phone), renamed))
        assertFalse(initial.stillAuthorizes(key(phone), gone))
        assertTrue(initial.stillAuthorizes(key(phone), initial))
    }

    @Test fun relockReopenSameAccountNeverResurrectsPreviousProviderCallback() {
        val sources = listOf(snapshot(phone, "Hackers.1995"))
        val scopes = mapOf(phone to "local-engine")
        val first = build(sources, scopes, epoch = "foreground-1/policy-4")
        val locked = build(listOf(snapshot(phone, "Hackers.1995",
            EnvironmentAvailability.LOCKED)), scopes, epoch = "background-2/policy-4")
        assertFalse(first.stillAuthorizes(key(phone), locked))

        // Same exact account, hash, and filename, but a new unlock generation.
        // The old HTTP callback must never inherit this new user's authorization.
        val reopened = build(sources, scopes, epoch = "foreground-3/policy-4")
        assertFalse(first.stillAuthorizes(key(phone), reopened))
        assertTrue(reopened.stillAuthorizes(key(phone), reopened))
        assertFalse("Stored policy revision must revoke outstanding disclosure",
            reopened.stillAuthorizes(key(phone),
                build(sources, scopes, epoch = "foreground-3/policy-5")))
        assertEquals("A new complete read of the same account must not starve work",
            reopened, build(listOf(snapshot(phone, "Hackers.1995",
                whenRead = 9999L)), scopes, epoch = "foreground-3/policy-4"))
    }

    @Test fun canceledProviderCallCannotBorrowLaterUnlockOfSameAccountAndHash() =
        kotlinx.coroutines.runBlocking {
            val scopes = mapOf(phone to "local-engine")
            val inventory = listOf(snapshot(phone, "Hackers.1995"))
            val original = build(inventory, scopes, epoch = "foreground-100/policy-4")
            val current = java.util.concurrent.atomic.AtomicReference(original)
            val firstEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val releaseOldHttp = kotlinx.coroutines.CompletableDeferred<Unit>()
            val writes = mutableListOf<String>()
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            val lease = MediaInventoryEnrichmentSession(
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
                enrich = { rows, allowed ->
                    val attempt = attempts.incrementAndGet()
                    if (attempt == 1) {
                        firstEntered.complete(Unit)
                        // The old real HTTP read can complete even after a cancel.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            releaseOldHttp.await()
                        }
                    }
                    if (allowed(rows.single().first)) {
                        writes.add(if (attempt == 1) "stale-provider-write" else "fresh-provider-write")
                    }
                })
            lease.replace(original.entries) { id -> original.stillAuthorizes(id, current.get()) }
            firstEntered.await()
            val locked = build(listOf(snapshot(phone, "Hackers.1995",
                EnvironmentAvailability.LOCKED)), scopes, epoch = "background-101/policy-4")
            current.set(locked)
            lease.replace(locked.entries) { id -> locked.stillAuthorizes(id, current.get()) }
            // The same original title and hash can return after fresh device unlock.
            // Its new right of disclosure must not authenticate the old HTTP result.
            val reopened = build(inventory, scopes, epoch = "foreground-102/policy-4")
            current.set(reopened)
            lease.replace(reopened.entries) { id -> reopened.stillAuthorizes(id, current.get()) }
            releaseOldHttp.complete(Unit)
            assertEquals("The prior process/generation cannot write into the new grant",
                listOf("fresh-provider-write"), writes)
            assertEquals("Newly authorized job is considered exactly once", 2, attempts.get())
            lease.close()
        }

    @Test fun incompleteOrAmbiguousSourcesCannotAuthorizeInventedJobs() {
        val live = snapshot(phone, "Hackers.1995")
        val duplicates = build(listOf(live, live), mapOf(phone to "local"))
        assertTrue(duplicates.entries.isEmpty())
        assertFalse(duplicates.stillAuthorizes(key(phone), duplicates))
        val noTimestamp = build(
            listOf(snapshot(phone, "Hackers.1995", whenRead = null)), mapOf(phone to "local"))
        assertTrue(noTimestamp.entries.isEmpty())
        val noRemote = build(
            listOf(snapshot(remote, "Away.We.Go.2009")), mapOf(phone to "local"))
        assertTrue(noRemote.entries.isEmpty())
        // A LIVE, timestamped but filtered or identity-corrupt rTorrent inventory
        // cannot prove the underlying exact job roster for provider transmission.
        val full = build(
            listOf(snapshot(remote, "The.Office.S03")), mapOf(remote to "account-2"))
        val partial = build(
            listOf(snapshot(remote, "The.Office.S03", complete = false)),
            mapOf(remote to "account-2"))
        assertEquals(1, full.entries.size)
        assertTrue("An incomplete live source must not disclose an offscreen title",
            partial.entries.isEmpty())
        assertFalse("Becoming incomplete revokes already queued provider requests",
            full.stillAuthorizes(key(remote), partial))
        val partialPhone = build(
            listOf(snapshot(phone, "Hackers.1995", complete = false)), mapOf(phone to "local"))
        assertTrue(partialPhone.entries.isEmpty())
    }
}
