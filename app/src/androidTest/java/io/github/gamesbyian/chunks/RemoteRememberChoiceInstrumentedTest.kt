package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Keystore and preference commits, not mocked credentials. */
@RunWith(AndroidJUnit4::class)
class RemoteRememberChoiceInstrumentedTest {
    @Test fun obsoleteConnectDeclineCannotClearNewerVerifiedCredentials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val previous = vault.rememberingEnabled()
        val alice = RemoteCredentials("https://box.invalid/RPC2", "alice", "alice-secret")
        val bob = RemoteCredentials("https://box.invalid/rutorrent2/RPC2", "bob", "bob-secret")
        try {
            assertTrue(vault.setRememberingEnabled(true))
            assertTrue(roster.clearAll())
            assertTrue(vault.clear())
            val earlier = RemoteCredentialLock.beginConnection()
            val later = RemoteCredentialLock.beginConnection()
            assertTrue(RemoteCredentialLock.saveVerifiedPairIfCurrent(vault, roster, bob, later))
            assertFalse(RemoteRememberChoice.declineIfCurrent(vault, earlier))
            assertTrue(vault.rememberingEnabled())
            assertEquals(bob, vault.load())
            assertEquals(listOf(bob), roster.list().map { it.credentials })
            assertTrue(RemoteRememberChoice.declineIfCurrent(vault, later))
            assertFalse(vault.rememberingEnabled())
            assertNull(vault.load())
            assertTrue(roster.list().isEmpty())
            assertFalse(RemoteRememberChoice.declineIfCurrent(vault, later))
            assertTrue(vault.setRememberingEnabled(true))
            val latest = RemoteCredentialLock.beginConnection()
            assertTrue(RemoteCredentialLock.saveVerifiedPairIfCurrent(vault, roster, alice, latest))
            assertFalse(RemoteRememberChoice.declineIfCurrent(vault, earlier))
            assertEquals(alice, vault.load())
            assertEquals(listOf(alice), roster.list().map { it.credentials })
        } finally {
            roster.clearAll()
            vault.clear()
            vault.setRememberingEnabled(previous)
        }
    }
}
