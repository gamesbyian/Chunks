package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalProfileScopeTest {
    @Test fun duplicateFriendlyNamesCannotSelectTheWrongAuthenticatedAccount() {
        // Profile labels and torrent hashes may match; the immutable profile ID cannot.
        val first = "rtorrent:account-a"
        val second = "rtorrent:account-b"
        val known = setOf(first, second)
        val one = savedProfileViewScope(first, known)
        val two = savedProfileViewScope(second, known)
        assertEquals(EnvironmentViewScope.Only(setOf(EnvironmentId(first))), one)
        assertEquals(EnvironmentViewScope.Only(setOf(EnvironmentId(second))), two)
        assertNotEquals(one, two)
    }

    @Test fun removedOrUnknownProfileNeverFallsBackToAllEnvironments() {
        val known = setOf("rtorrent:account-a")
        assertNull(savedProfileViewScope("rtorrent:account-b", known))
        assertNull(savedProfileViewScope("Server named account-a", known))
        assertNull(savedProfileViewScope("", known))
        assertNull(savedProfileViewScope(" ", known))
    }

    @Test fun exactScopeRemainsNonMutatingAcrossProfileSetChanges() {
        val account = "rtorrent:account-a"
        val selected = savedProfileViewScope(account, setOf(account, "rtorrent:account-b"))
        assertEquals(EnvironmentViewScope.Only(setOf(EnvironmentId(account))), selected)
        // Forgetting the requested profile prevents any new read authority;
        // it must not silently turn into an unrestricted All scope.
        assertNull(savedProfileViewScope(account, setOf("rtorrent:account-b")))
    }
}
