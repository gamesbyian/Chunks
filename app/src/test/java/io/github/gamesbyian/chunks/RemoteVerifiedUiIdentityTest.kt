package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVerifiedUiIdentityTest {
    private val address = "https://seed.example/RPC2"

    @Test fun aliasUsesFrozenVerifiedPrincipalAndPreservesWhitespace() {
        val verified = RemoteCredentials(address, " alice ", "secret")
        var editorUsername = "bob"
        val scope = RemoteVerifiedUiIdentity.aliasScopeOf(verified)
        editorUsername = "alice"
        assertEquals("$address\u0000 alice ", scope)
        assertFalse(scope.endsWith(editorUsername))
        assertFalse(scope.contains(verified.password))
        assertEquals("https://seed.example/RPC2\u0000 alice ",
            RemoteVerifiedUiIdentity.aliasScopeOf(
                RemoteCredentials("  $address  ", " alice ", "another-secret")))
    }

    @Test fun exactAccountComparisonPreventsDeletingDifferentLegacyLogin() {
        val padded = RemoteCredentials(address, " alice ", "first")
        val plain = RemoteCredentials(address, "alice", "second")
        assertFalse(RemoteVerifiedUiIdentity.samePrincipal(plain, padded))
        assertFalse(RemoteVerifiedUiIdentity.samePrincipal(padded, plain))
        assertFalse(RemoteVerifiedUiIdentity.samePrincipal(
            RemoteCredentials(address, "ALICE", "first"), plain))
        assertTrue(RemoteVerifiedUiIdentity.samePrincipal(
            RemoteCredentials("  $address ", " alice ", "different-password"), padded))
        assertFalse(RemoteVerifiedUiIdentity.samePrincipal(null, padded))
    }

    @Test fun sameUserOnDifferentRpcInstanceIsDifferentAccountBoundary() {
        val first = RemoteCredentials("https://box.example/rutorrent/RPC1", "alice", "a")
        val second = RemoteCredentials("https://box.example/rutorrent/RPC2", "alice", "b")
        assertFalse(RemoteVerifiedUiIdentity.samePrincipal(first, second))
        assertFalse(RemoteVerifiedUiIdentity.samePrincipal(second, first))
        assertFalse(RemoteVerifiedUiIdentity.aliasScopeOf(first) ==
            RemoteVerifiedUiIdentity.aliasScopeOf(second))
    }
}
