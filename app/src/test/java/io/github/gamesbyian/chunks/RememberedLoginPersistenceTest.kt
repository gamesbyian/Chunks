package io.github.gamesbyian.chunks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Invariant controls `credentials-saved-for-superseded-connect`, `credentials-saved-when-opted-out`
 * and `credentials-remember-default-off-on-corrupt-policy` (A2611-08): the credential save is
 * gated by the CURRENT connection generation and the remember policy. Software AES keys stand in
 * for the Keystore, exactly as `KeystoreSealedBlobCompatibilityTest` does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RememberedLoginPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private class SoftKeys(seed: Int) : SealingKeys {
        val secret: SecretKey = SecretKeySpec(ByteArray(32) { (it * 7 + seed).toByte() }, "AES")
        override fun encryptionKey(): SecretKey = secret
        override fun decryptionKey(): SecretKey? = secret
        override fun erase() {}
    }
    private val credentials = RemoteCredentials("https://seedbox.example/RPC2", "alice", "pw-1")

    private fun stores(): Pair<RememberedRemoteLogin, RememberedRemoteProfileRoster> {
        context.getSharedPreferences(RememberedRemoteLogin.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(RememberedRemoteProfileRoster.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        return RememberedRemoteLogin(context, SoftKeys(1)) to RememberedRemoteProfileRoster(context, SoftKeys(2))
    }

    @Test fun onlyTheMostRecentConnectMayPersistAndItDoes() {
        val (vault, roster) = stores()
        val first = RemoteCredentialLock.beginConnection()
        val second = RemoteCredentialLock.beginConnection()
        assertFalse("a superseded Connect never persists",
            RemoteCredentialLock.saveVerifiedPairIfCurrent(vault, roster, credentials, first))
        assertNull(vault.load())
        assertTrue(roster.list().isEmpty())
        assertTrue(RemoteCredentialLock.saveVerifiedPairIfCurrent(vault, roster, credentials, second))
        assertEquals(credentials, vault.load())
        assertEquals(1, roster.list().size)
    }

    @Test fun credentialsAreNeverSavedAfterOptOut() {
        val (vault, roster) = stores()
        assertTrue(vault.setRememberingEnabled(false))
        val ticket = RemoteCredentialLock.beginConnection()
        assertFalse(RemoteCredentialLock.saveVerifiedPairIfCurrent(vault, roster, credentials, ticket))
        assertNull(vault.load())
        assertTrue(roster.list().isEmpty())
    }

    @Test fun aCorruptRememberPolicyDeniesAndNeverEnablesRemembering() {
        val (vault, roster) = stores()
        context.getSharedPreferences(RememberedRemoteLogin.PREFS, Context.MODE_PRIVATE).edit()
            .putString("remember_enabled", "not-a-boolean").commit()
        assertFalse("a corrupt policy must not turn remembering on", vault.rememberingEnabled())
        val ticket = RemoteCredentialLock.beginConnection()
        assertFalse(RemoteCredentialLock.saveVerifiedPairIfCurrent(vault, roster, credentials, ticket))
        assertNotNull(vault) // the vault itself stays usable once the person repairs the preference
    }
}
