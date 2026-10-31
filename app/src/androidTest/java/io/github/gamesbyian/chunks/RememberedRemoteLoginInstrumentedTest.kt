package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android Keystore, not an in-memory fake encryption key. */
@RunWith(AndroidJUnit4::class)
class RememberedRemoteLoginInstrumentedTest {
    @Test fun keystoreCiphertextSurvivesNewStoreInstanceUntilExplicitForget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = RememberedRemoteLogin(context)
        val priorPolicy = vault.rememberingEnabled()
        assertTrue(vault.setRememberingEnabled(true))
        assertTrue(vault.clear())
        val credentials = RemoteCredentials(
            "https://example.org/hosted%20account/rutorrent/", "viewer", "secret \u2603 password"
        )
        try {
            assertTrue(vault.save(credentials))
            assertEquals(credentials, RememberedRemoteLogin(context).load())
            val raw = context.getSharedPreferences("remembered-remote-login-v1", 0)
                .getString("ciphertext", null)
            assertNotNull(raw)
            assertFalse(raw!!.contains(credentials.password))
            assertFalse(raw.contains(credentials.username))
            assertFalse(raw.contains(credentials.endpoint))
        } finally {
            assertTrue(vault.clear())
            assertTrue(vault.setRememberingEnabled(priorPolicy))
        }
        assertFalse(RememberedRemoteLogin(context).hasSavedLogin())
        assertNull(RememberedRemoteLogin(context).load())
    }

    @Test fun optOutPersistsAcrossInstancesAndBlocksAccidentalCredentialSave() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = RememberedRemoteLogin(context)
        val priorPolicy = vault.rememberingEnabled()
        val creds = RemoteCredentials("https://example.org/RPC2", "viewer", "do-not-retain")
        try {
            assertTrue(vault.setRememberingEnabled(true))
            assertTrue(vault.save(creds))
            assertTrue(vault.hasSavedLogin())
            assertTrue(vault.setRememberingEnabled(false))
            val fresh = RememberedRemoteLogin(context)
            assertFalse(fresh.rememberingEnabled())
            assertFalse(fresh.hasSavedLogin())
            assertNull(fresh.load())
            assertFalse(fresh.save(creds))
            assertFalse(fresh.hasSavedLogin())
            assertTrue(fresh.setRememberingEnabled(true))
            assertTrue(RememberedRemoteLogin(context).rememberingEnabled())
            assertNull(fresh.load())
            assertTrue(fresh.save(creds))
            assertEquals(creds, RememberedRemoteLogin(context).load())
        } finally {
            assertTrue(vault.clear())
            assertTrue(vault.setRememberingEnabled(priorPolicy))
        }
    }

    @Test fun corruptedEncryptedBlobCannotBeUsedAsLogin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = RememberedRemoteLogin(context)
        val previousPolicy = vault.rememberingEnabled()
        assertTrue(vault.setRememberingEnabled(true))
        try {
            assertTrue(vault.clear())
            context.getSharedPreferences("remembered-remote-login-v1", 0)
                .edit().putString("ciphertext", "not a valid GCM record").commit()
            assertNull(RememberedRemoteLogin(context).load())
            // The ciphertext remains occupied but unreadable. A transient key
            // failure or malformed record cannot authorize silent deletion.
            assertTrue(vault.hasSavedLogin())
            assertEquals("not a valid GCM record",
                context.getSharedPreferences("remembered-remote-login-v1", 0)
                    .getString("ciphertext", null))
            assertFalse(vault.save(RemoteCredentials("https://example.org/RPC2", "viewer", "new-secret")))
            assertTrue(vault.clear())
            assertFalse(vault.hasSavedLogin())
        } finally {
            assertTrue(vault.clear())
            assertTrue(vault.setRememberingEnabled(previousPolicy))
        }
    }
}
