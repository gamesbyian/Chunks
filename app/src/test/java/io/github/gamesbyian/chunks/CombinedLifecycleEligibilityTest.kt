package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.CombinedLifecycleEligibility.Facts
import io.github.gamesbyian.chunks.CombinedLifecycleEligibility.eligible
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Start/Stop offering in combined Transfers, extracted from the screen (A2611-02). */
class CombinedLifecycleEligibilityTest {
    private val v1 = "ab".repeat(20)
    private val local = Facts(readCurrent = true, completeLiveInventory = true, isLocal = true,
        localPrivacyMayShow = true, localRunning = true, isAuthenticatedServer = false,
        serverLifecyclePresent = false, verifiedServerOwner = false, serverPrivacyMayShow = false,
        serverStale = null, infoHash = v1)
    private val server = local.copy(isLocal = false, localPrivacyMayShow = false, localRunning = false,
        isAuthenticatedServer = true, serverLifecyclePresent = true, verifiedServerOwner = true,
        serverPrivacyMayShow = true, serverStale = false)

    @Test fun positiveControls() {
        assertTrue(eligible(local))
        assertTrue(eligible(server))
    }

    @Test fun anyEnvironmentNeedsACurrentReadAndACompleteLiveInventory() {
        for (f in listOf(local, server)) {
            assertFalse(eligible(f.copy(readCurrent = false)))
            assertFalse(eligible(f.copy(completeLiveInventory = false)))
        }
    }

    @Test fun localNeedsARunningEngineAndVisiblePrivacy() {
        assertFalse(eligible(local.copy(localRunning = false)))
        assertFalse("a locked Local scope is never commandable", eligible(local.copy(localPrivacyMayShow = false)))
    }

    @Test fun theServerNeedsItsVerifiedOwnerFreshStateAndVisiblePrivacy() {
        assertFalse(eligible(server.copy(serverLifecyclePresent = false)))
        assertFalse("another session's lifecycle", eligible(server.copy(verifiedServerOwner = false)))
        assertFalse(eligible(server.copy(serverPrivacyMayShow = false)))
        assertFalse("stale", eligible(server.copy(serverStale = true)))
        assertFalse("unknown freshness is not fresh", eligible(server.copy(serverStale = null)))
    }

    @Test fun onlyExactV1HashesAreCommandableOnTheServer() {
        assertFalse("v2/hybrid identity", eligible(server.copy(infoHash = "ab".repeat(32))))
        assertFalse("upper case is not the canonical identity", eligible(server.copy(infoHash = "AB".repeat(20))))
        assertTrue("Local does not apply the rTorrent v1 rule", eligible(local.copy(infoHash = "ab".repeat(32))))
    }

    @Test fun aRowOfNeitherEnvironmentIsNeverCommandable() {
        assertFalse(eligible(server.copy(isAuthenticatedServer = false)))
    }
}
