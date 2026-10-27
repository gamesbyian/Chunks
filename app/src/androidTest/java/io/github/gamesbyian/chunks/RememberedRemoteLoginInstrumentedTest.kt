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
        }
        assertFalse(RememberedRemoteLogin(context).hasSavedLogin())
        assertNull(RememberedRemoteLogin(context).load())
    }

    @Test fun corruptedEncryptedBlobCannotBeUsedAsLogin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = RememberedRemoteLogin(context)
        try {
            assertTrue(vault.clear())
            context.getSharedPreferences("remembered-remote-login-v1", 0)
                .edit().putString("ciphertext", "not a valid GCM record").commit()
            assertNull(RememberedRemoteLogin(context).load())
            assertFalse(vault.hasSavedLogin())
        } finally {
            assertTrue(vault.clear())
        }
    }
}
