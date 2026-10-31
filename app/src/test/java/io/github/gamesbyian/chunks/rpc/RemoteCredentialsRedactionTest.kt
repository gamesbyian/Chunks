package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCredentialsRedactionTest {
    @Test fun debugStringNeverIncludesAuthenticationOrPrivateEndpointParts() {
        val credentials = RemoteCredentials(
            "https://host.example/private-slot/rutorrent2/RPC2",
            "private-username", "ultra-secret-password"
        )
        val printed = credentials.toString()
        assertEquals("RemoteCredentials(<redacted>)", printed)
        for (secret in listOf("private-slot", "rutorrent2", "private-username",
            "ultra-secret-password", "host.example")) {
            assertFalse("Leaked $secret in data-class debug string", printed.contains(secret))
        }
        // Data-class semantics used by Connect, vaults and account snapshots
        // remain unchanged. Only accidental debug serialization is redacted.
        val updated = credentials.copy(password = "new-password")
        assertEquals(credentials.endpoint, updated.endpoint)
        assertEquals(credentials.username, updated.username)
        assertEquals("new-password", updated.password)
        assertFalse(credentials == updated)
        assertTrue(credentials == credentials.copy())
    }
}
