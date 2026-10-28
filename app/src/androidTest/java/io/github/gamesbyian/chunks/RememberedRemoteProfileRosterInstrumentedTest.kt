package io.github.gamesbyian.chunks

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RememberedRemoteProfileRosterInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val alice = RemoteCredentials("https://seedbox.invalid/rpc2",
        "Alice", "passphrase-one-only-in-encrypted-payload")
    private val bob = RemoteCredentials("https://seedbox.invalid/rpc2",
        "alice", "other-password-different-case")
    private val other = RemoteCredentials("https://other.invalid/rpc2",
        "Alice", "other-host-secret")
    private var originalPreference = true
    @Before fun enableRememberingForTest() {
        val login = RememberedRemoteLogin(context)
        originalPreference = login.rememberingEnabled()
        assertTrue(login.setRememberingEnabled(true))
    }
    @After fun restoreRemembering() {
        RememberedRemoteProfileRoster(context).clearAll()
        RememberedRemoteLogin(context).setRememberingEnabled(originalPreference)
    }

    @Test fun encryptedProfilesSurviveReopenWithoutPlaintextPrefsOrAccountCollision() {
        val vault = RememberedRemoteProfileRoster(context)
        vault.clearAll()
        try {
            assertTrue(vault.saveVerified(alice))
            assertTrue(vault.saveVerified(bob))
            assertTrue(vault.saveVerified(other))
            val loaded = RememberedRemoteProfileRoster(context).list()
            assertEquals(3, loaded.size)
            assertEquals(3, loaded.map { it.id }.toSet().size)
            assertEquals(other.password, loaded[0].credentials.password)
            assertEquals(bob.password, loaded[1].credentials.password)
            assertEquals(alice.password, loaded[2].credentials.password)
            val prefs = context.getSharedPreferences(
                "chunks_remote_profiles_encrypted_v1", Context.MODE_PRIVATE)
            val serialized = prefs.all.entries.joinToString("|") { it.key + "=" + it.value }
            assertFalse(serialized.contains("passphrase-one"))
            assertFalse(serialized.contains("seedbox.invalid"))
            assertFalse(serialized.contains("Alice"))
            assertTrue(vault.forget(loaded[1].id))
            assertEquals(2, RememberedRemoteProfileRoster(context).list().size)
            assertTrue(vault.saveVerified(alice.copy(password = "rotated-password")))
            assertEquals(2, vault.list().size)
            assertEquals("rotated-password", vault.list().first().credentials.password)
        } finally { vault.clearAll() }
    }

    @Test fun corruptedCiphertextFailsClosedAndNeverFallsBackToPlaintext() {
        val vault = RememberedRemoteProfileRoster(context)
        vault.clearAll()
        try {
            assertTrue(vault.saveVerified(alice))
            val prefs = context.getSharedPreferences(
                "chunks_remote_profiles_encrypted_v1", Context.MODE_PRIVATE)
            assertTrue(prefs.edit().putString("roster", "corrupted-garbage").commit())
            assertTrue(RememberedRemoteProfileRoster(context).list().isEmpty())
            assertFalse(prefs.contains("roster"))
        } finally { vault.clearAll() }
    }

    @Test fun durableOptOutBlocksRosterReadsAndWritesAcrossRestart() {
        val roster = RememberedRemoteProfileRoster(context)
        roster.clearAll()
        assertTrue(roster.saveVerified(alice))
        val login = RememberedRemoteLogin(context)
        assertTrue(login.setRememberingEnabled(false))
        assertTrue(RememberedRemoteProfileRoster(context).list().isEmpty())
        assertFalse(roster.saveVerified(bob))
        assertTrue(roster.clearAll())
        assertTrue(login.setRememberingEnabled(true))
        assertTrue(roster.list().isEmpty())
    }

    @Test fun rosterCapsEntriesAndOptOutClearsAllCredentials() {
        val vault = RememberedRemoteProfileRoster(context)
        vault.clearAll()
        try {
            repeat(RememberedRemoteProfileRoster.MAX_PROFILES) { n ->
                assertTrue(vault.saveVerified(RemoteCredentials(
                    "https://host$n.invalid/RPC2", "user", "pass$n")))
            }
            assertEquals(12, vault.list().size)
            assertFalse(vault.saveVerified(RemoteCredentials(
                "https://extra.invalid/RPC2", "user", "extra")))
            assertEquals(12, vault.list().size)
            assertTrue(vault.clearAll())
            assertTrue(vault.list().isEmpty())
        } finally { vault.clearAll() }
    }
}
