package io.github.gamesbyian.chunks.privacy

import org.junit.Assert.*
import org.junit.Test

class RemoteTransferPrivacyScopeTest {
    @Test fun distinctEndpointsAndUsernamesNeverShareAuthScope() {
        val a = requireNotNull(RemoteTransferPrivacyScope.fromAccount(
            "https://seed.example/RPC2", "alice"))
        val b = requireNotNull(RemoteTransferPrivacyScope.fromAccount(
            "https://seed.example/RPC2", "bob"))
        val c = requireNotNull(RemoteTransferPrivacyScope.fromAccount(
            "https://backup.example/RPC2", "alice"))
        val caseSensitive = requireNotNull(RemoteTransferPrivacyScope.fromAccount(
            "https://seed.example/RPC2", "Alice"))
        assertEquals(4, setOf(a, b, c, caseSensitive).size)
        for (scope in listOf(a, b, c, caseSensitive)) {
            assertTrue(scope.length <= 512)
            assertFalse(scope.contains("seed.example"))
            assertFalse(scope.contains("alice"))
        }
        assertEquals(a, RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(
            "https://seed.example/RPC2\u0000alice"))
        assertEquals(a, RemoteTransferPrivacyScope.fromAccount(
            " https://seed.example/RPC2 ", " alice "))
    }

    @Test fun invalidOrUnconfirmedAliasScopeNeverTurnsIntoUnprotectedRemoteScope() {
        assertNull(RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(""))
        assertNull(RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(
            "https://seed.example/RPC2"))
        assertNull(RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(
            "\u0000alice"))
        assertNull(RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(
            "https://seed.example/RPC2\u0000alice\u0000third"))
        assertNull(RemoteTransferPrivacyScope.fromAccount(
            "https://seed.example/RPC2", "x".repeat(513)))
    }

    @Test fun inMemoryRemoteTicketsNeverUnlockLocalOrAnotherRemoteAccount() {
        val local = "local"
        val a = requireNotNull(RemoteTransferPrivacyScope.fromAccount(
            "https://seed.example/RPC2", "alice"))
        val b = requireNotNull(RemoteTransferPrivacyScope.fromAccount(
            "https://seed.example/RPC2", "bob"))
        val session = TransferPrivacySession { 3_000L }
        for (scope in listOf(local, a, b))
            assertTrue(session.configure(scope, TransferLockMode.ONCE_PER_PROCESS, false))
        assertTrue(session.finishUnlock(requireNotNull(session.beginUnlock(a)), true))
        assertTrue(session.mayView(a))
        assertFalse(session.mayView(b))
        assertFalse(session.mayView(local))
        session.lockNow(a)
        assertFalse(session.mayView(a))
    }
}
