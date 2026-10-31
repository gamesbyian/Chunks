package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import org.junit.Assert.*
import org.junit.Test

class SavedProfileIdentityTest {
    private val a = "https://host.example/rutorrent/plugins/httprpc/action.php"
    private val b = "https://host.example/rutorrent2/plugins/httprpc/action.php"
    private val user = "alice"
    private val id = EnvironmentSnapshots.serverId(a, user).value

    @Test fun verifiedExactAccountAndInstanceAreAccepted() {
        assertTrue(SavedProfileIdentity.matches(id, "$a\u0000$user"))
        assertFalse(SavedProfileIdentity.matches(id, "$a\u0000 $user"))
        assertFalse(SavedProfileIdentity.matches(id, "$a\u0000$user "))
        assertFalse(SavedProfileIdentity.matches(id,
            "$a\u0000 $user ".trimEnd()))
        assertTrue(SavedProfileIdentity.matches(id, "  $a  \u0000$user"))
    }

    @Test fun completedConnectMustUseFrozenVerifiedCredentialsNotLaterEditorState() {
        val verified = io.github.gamesbyian.chunks.rpc.RemoteCredentials(
            a, " alice ", "secret")
        var editedUsername = "bob"
        val alias = SavedProfileIdentity.fromVerifiedCredentials(verified)
        editedUsername = "alice"
        assertEquals("$a\u0000 alice ", alias)
        assertEquals(EnvironmentSnapshots.serverId(a, verified.username).value,
            EnvironmentSnapshots.serverIdOfAliasScope(alias)?.value)
        assertNotEquals(EnvironmentSnapshots.serverId(a, editedUsername).value,
            EnvironmentSnapshots.serverIdOfAliasScope(alias)?.value)
    }

    @Test fun forgetLegacyVaultMustNotClearAnotherWhitespaceDistinctPrincipal() {
        val old = io.github.gamesbyian.chunks.rpc.RemoteCredentials(a, "alice", "old-secret")
        val selected = old.copy(username = " alice ", password = "other-secret")
        assertFalse(SavedProfileIdentity.sameAuthenticatedAccount(old, selected))
        assertFalse(SavedProfileIdentity.sameAuthenticatedAccount(null, old))
        assertTrue(SavedProfileIdentity.sameAuthenticatedAccount(old, old.copy(password = "rotated")))
        assertTrue(SavedProfileIdentity.sameAuthenticatedAccount(
            old.copy(endpoint = "  $a  "), old))
    }

    @Test fun sameHostDifferentUsernameMustNotBorrowSavedProfile() {
        assertFalse(SavedProfileIdentity.matches(id, "$a\u0000bob"))
        assertFalse(SavedProfileIdentity.matches(id, "$a\u0000ALICE"))
    }

    @Test fun sameAccountDifferentNumberedInstanceRequiresReconnect() {
        assertFalse(SavedProfileIdentity.matches(id, "$b\u0000$user"))
        assertFalse(SavedProfileIdentity.matches(id, "http://host.example/rutorrent/plugins/httprpc/action.php\u0000$user"))
    }

    @Test fun absentBrokenAndWrongSavedIdentityFailClosed() {
        assertFalse(SavedProfileIdentity.matches(id, null))
        assertFalse(SavedProfileIdentity.matches(id, ""))
        assertFalse(SavedProfileIdentity.matches(id, "$a"))
        assertFalse(SavedProfileIdentity.matches(id, "$a\u0000$user\u0000extra"))
        assertFalse(SavedProfileIdentity.matches("phone", "$a\u0000$user"))
        assertFalse(SavedProfileIdentity.matches("not-a-hash", "$a\u0000$user"))
    }
}
