package io.github.gamesbyian.chunks

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.media.AuthorizedMediaInventorySnapshot
import io.github.gamesbyian.chunks.media.MediaTransferKey
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import io.github.gamesbyian.chunks.privacy.TransferLockMode
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native source authorization regression only. Uses a synthetic local scope and
 * a simulated approved session ticket; it never makes a provider HTTP request,
 * opens a private account, or treats the ticket as platform biometric evidence.
 *
 * Real ActivityScenario onStop/onStart must increment the same lifecycle epoch
 * consumed by canonical Transfers, so an in-flight callback may not borrow the
 * subsequent unlock even when the account, hash, and original name are identical.
 */
@RunWith(AndroidJUnit4::class)
class InstalledMediaPrivacyEpochInstrumentedTest {
    private val environment = EnvironmentId("local")
    private val hash = "a".repeat(40)
    private val key = MediaTransferKey.of(UnifiedTransferKey.of(environment, hash))
    private val scope = "chunks-media-privacy-epoch-fixture-only"
    private val name = "Hackers.1995.1080p.mkv"

    private fun source(availability: EnvironmentAvailability) =
        EnvironmentTransferSnapshot(environment, availability, true, 20261110L,
            listOf(UnifiedTransferObservation(UnifiedTransferKey.of(environment, hash),
                name, name, null, null, null, null, null, null)))

    private fun snapshot(availability: EnvironmentAvailability): AuthorizedMediaInventorySnapshot {
        val epoch = "${TransferPrivacyRuntime.lifecycleRevision}/${TransferPrivacyPolicyStore.revision}"
        return AuthorizedMediaInventorySnapshot.build(
            listOf(source(availability)), mapOf(environment to scope), epoch)
    }

    @Test fun realAndroidStopThenNewUnlockNeverResurrectsOldProviderAuthorization() {
        val session = TransferPrivacyRuntime.session
        assertTrue(session.configure(scope, TransferLockMode.EACH_FOREGROUND_RETURN,
            authenticatedForChange = true))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                val grant = requireNotNull(session.beginUnlock(scope))
                assertTrue(session.finishUnlock(grant, deviceAuthenticated = true))
                assertTrue(session.mayView(scope))
                val firstRevision = TransferPrivacyRuntime.lifecycleRevision
                val original = snapshot(EnvironmentAvailability.LIVE)
                assertTrue(original.stillAuthorizes(key, original))

                // Actual framework transitions, not a direct call to the epoch
                // counter: MainActivity.onStop must revoke EACH_FOREGROUND_RETURN.
                activity.moveToState(Lifecycle.State.CREATED)
                assertFalse(session.mayView(scope))
                val backgroundRevision = TransferPrivacyRuntime.lifecycleRevision
                assertNotEquals(firstRevision, backgroundRevision)
                val locked = snapshot(EnvironmentAvailability.LOCKED)
                assertFalse(original.stillAuthorizes(key, locked))
                assertTrue(locked.entries.isEmpty())

                activity.moveToState(Lifecycle.State.RESUMED)
                val secondRevision = TransferPrivacyRuntime.lifecycleRevision
                assertNotEquals(backgroundRevision, secondRevision)
                val newGrant = requireNotNull(session.beginUnlock(scope))
                assertTrue(session.finishUnlock(newGrant, deviceAuthenticated = true))
                assertTrue(session.mayView(scope))
                val reopened = snapshot(EnvironmentAvailability.LIVE)
                // A freshly granted session authorizes the exact job, but old
                // requests from before the real background transition do not.
                assertTrue(reopened.stillAuthorizes(key, reopened))
                assertFalse(original.stillAuthorizes(key, reopened))
            }
        } finally {
            session.lockNow(scope)
            session.configure(scope, TransferLockMode.NEVER, authenticatedForChange = true)
        }
    }
}
