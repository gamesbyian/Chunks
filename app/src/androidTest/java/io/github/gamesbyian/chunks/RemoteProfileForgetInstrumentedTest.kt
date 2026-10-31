package io.github.gamesbyian.chunks

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android Keystore and durable preference commits, no fake credential vault. */
@RunWith(AndroidJUnit4::class)
class RemoteProfileForgetInstrumentedTest {
    @Test fun forgetOnlyMatchingPrincipalAndRefuseCorruptedRosterWithoutLegacyLoss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val oldPolicy = vault.rememberingEnabled()
        val endpoint = "https://box.invalid/RPC2"
        val exact = RemoteCredentials(endpoint, "alice", "exact-secret")
        val spaced = RemoteCredentials(endpoint, " alice ", "spaced-secret")
        val rosterPrefs = context.getSharedPreferences(
            "chunks_remote_profiles_encrypted_v1", Context.MODE_PRIVATE)
        try {
            assertTrue(vault.setRememberingEnabled(true))
            assertTrue(roster.clearAll())
            assertTrue(vault.clear())
            val ticket = RemoteCredentialLock.beginConnection()
            assertTrue(RemoteCredentialLock.saveVerifiedPairIfCurrent(vault, roster, spaced, ticket))
            assertTrue(roster.saveVerified(exact))
            val exactProfile = requireNotNull(roster.list().find { it.credentials.username == "alice" })
            assertTrue(RemoteProfileForget.forget(roster, vault, exactProfile))
            assertEquals(spaced, vault.load())
            assertEquals(listOf(spaced), roster.list().map { it.credentials })

            val unreadable = "unreadable-roster-retain-bytes"
            assertTrue(rosterPrefs.edit().putString("roster", unreadable).commit())
            val original = RememberedRemoteProfileRoster.Profile(
                RememberedRemoteProfileRoster.identityOf(spaced.endpoint, spaced.username),
                "Old profile", spaced)
            assertFalse(RemoteProfileForget.forget(roster, vault, original))
            assertEquals(unreadable, rosterPrefs.getString("roster", null))
            assertEquals(spaced, vault.load())
            // A failed Forget must not revoke the current principal's login.
            assertTrue(roster.clearAll())
            assertTrue(roster.saveVerified(spaced))
            val oldTicket = RemoteCredentialLock.beginConnection()
            assertTrue(RemoteProfileForget.forget(roster, vault, roster.list().single()))
            assertNull(vault.load())
            assertTrue(roster.list().isEmpty())
            assertFalse(RemoteCredentialLock.saveVerifiedPairIfCurrent(vault, roster, spaced, oldTicket))
        } finally {
            roster.clearAll()
            vault.clear()
            vault.setRememberingEnabled(oldPolicy)
        }
    }
}
