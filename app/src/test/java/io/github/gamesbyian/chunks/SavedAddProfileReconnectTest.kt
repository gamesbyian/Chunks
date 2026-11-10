package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** No UI text, profile label or matching infohash can substitute for the exact account. */
class SavedAddProfileReconnectTest {
    private val account2 = "https://seed.example/rutorrent/2/RPC2\u0000alice"
    private val account3 = "https://seed.example/rutorrent/3/RPC2\u0000alice"
    private val otherPrincipal = "https://seed.example/rutorrent/2/RPC2\u0000bob"

    @Test fun matchingVerifiedAccountResumesExactlyOnce() {
        val request = SavedAddProfileReconnect()
        request.requestAuthentication(account2)
        assertTrue(request.consumeAuthenticated(account2))
        assertFalse(request.consumeAuthenticated(account2))
    }

    @Test fun sameHostOtherNumberedRouteOrPrincipalNeverResumes() {
        listOf(account3, otherPrincipal).forEach { other ->
            val request = SavedAddProfileReconnect()
            request.requestAuthentication(account2)
            assertFalse(request.consumeAuthenticated(other))
            // Once a different connection succeeded, a later matching
            // account must not revive the old Add navigation promise.
            assertFalse(request.consumeAuthenticated(account2))
        }
    }

    @Test fun failedConnectDoesNotConsumeUntilRealAuthenticatedResult() {
        val request = SavedAddProfileReconnect()
        request.requestAuthentication(account2)
        // No consume call on a refused/timeout Connect.
        assertTrue(request.consumeAuthenticated(account2))
    }

    @Test fun cancelAndReplacementDoNotLeakAcrossNavigation() {
        val request = SavedAddProfileReconnect()
        request.requestAuthentication(account2)
        request.abandon()
        assertFalse(request.consumeAuthenticated(account2))
        request.requestAuthentication(account2)
        request.requestAuthentication(account3)
        assertFalse(request.consumeAuthenticated(account2))
        assertFalse(request.consumeAuthenticated(account3))
    }

    @Test fun savedProfileReselectionPreservesOnlySameAccountPromise() {
        val request = SavedAddProfileReconnect()
        request.requestAuthentication(account2)
        request.abandonIfDifferent(account2)
        assertTrue(request.consumeAuthenticated(account2))
        request.requestAuthentication(account2)
        request.abandonIfDifferent(account3)
        assertFalse(request.consumeAuthenticated(account2))
        request.requestAuthentication(account2)
        request.abandonIfDifferent(otherPrincipal)
        assertFalse(request.consumeAuthenticated(account2))
    }

    @Test fun emptyScopeCannotBeOfferedAsAuthenticatedTarget() {
        val request = SavedAddProfileReconnect()
        assertThrows(IllegalArgumentException::class.java) {
            request.requestAuthentication("")
        }
        assertFalse(request.consumeAuthenticated(account2))
        request.requestAuthentication(account2)
        assertFalse(request.consumeAuthenticated(""))
    }
}
