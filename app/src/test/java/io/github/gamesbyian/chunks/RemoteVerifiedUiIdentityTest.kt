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

    @Test fun removalLabelPreservesAccountWhitespaceAndLongInstancePathWithoutSecrets() {
        val prefix = "https://box.example/" + "x".repeat(200)
        val first = requireNotNull(RemoteVerifiedUiIdentity.removalScopeLabel("$prefix/one\u0000 alice "))
        val second = requireNotNull(RemoteVerifiedUiIdentity.removalScopeLabel("$prefix/two\u0000alice"))
        assertTrue(first.contains("$prefix/one"))
        assertTrue(second.contains("$prefix/two"))
        assertTrue(first.contains("\\u0020alice\\u0020"))
        assertFalse(first == second)
        // The production transport refuses embedded credentials and queries;
        // a malformed scope must not render a token or password in the UI.
        val unsafe = RemoteVerifiedUiIdentity.removalScopeLabel(
            "https://box.example/RPC2?token=secret\u0000alice")
        assertEquals(null, unsafe)
    }
}
