package io.github.gamesbyian.chunks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilitySnapshot
import io.github.gamesbyian.chunks.model.EnvironmentOperation
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.NewRemoteReadOnlyCapabilities
import io.github.gamesbyian.chunks.model.RemoteBackendIdentity
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RememberedRemoteProtocolProfilesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val vaultPrefs get() = context.getSharedPreferences(
        "chunks_other_remote_profiles_encrypted_v1", Context.MODE_PRIVATE)
    private val legacyPrefs get() = context.getSharedPreferences(
        "remembered-remote-login-v1", Context.MODE_PRIVATE)

    private class TestKeys : RememberedRemoteProtocolProfiles.Keys {
        private var secret: SecretKey? = SecretKeySpec(ByteArray(32) { (it * 7 + 13).toByte() }, "AES")
        var erased = false
        override fun encryptionKey(): SecretKey = secret ?: throw IllegalStateException("Key erased")
        override fun decryptionKey(): SecretKey? = secret
        override fun erase() { erased = true; secret = null }
    }
    private lateinit var keys: TestKeys
    private lateinit var store: RememberedRemoteProtocolProfiles
    private val password = "dont-leak-this-password"

    @Before fun before() {
        vaultPrefs.edit().clear().commit()
        legacyPrefs.edit().clear().commit()
        keys = TestKeys()
        store = RememberedRemoteProtocolProfiles(context, keys)
    }

    @After fun after() {
        vaultPrefs.edit().clear().commit()
        legacyPrefs.edit().clear().commit()
    }

    private fun identity(backend: RemoteBackendKind, credentials: RemoteCredentials, daemon: String?) =
        RemoteBackendIdentity.of(backend, credentials.endpoint, credentials.username, daemon)

    private fun receipt(
        backend: RemoteBackendKind,
        credentials: RemoteCredentials,
        daemon: String? = null,
        observedAt: Long = System.currentTimeMillis()
    ): Pair<EnvironmentTransferSnapshot, EnvironmentCapabilitySnapshot> {
        val id = identity(backend, credentials, daemon)
        return EnvironmentTransferSnapshot(
            id, EnvironmentAvailability.LIVE, true, observedAt, emptyList()
        ) to NewRemoteReadOnlyCapabilities.forBackend(backend, id, observedAt)
    }

    private fun creds(path: String = "transmission/rpc", user: String = "alice") =
        RemoteCredentials("https://seedbox.example/account-a/" + path, user, password)

    private fun remember(
        backend: RemoteBackendKind,
        credentials: RemoteCredentials,
        daemon: String? = null,
        name: String? = null
    ): Boolean {
        val (snapshot, capabilities) = receipt(backend, credentials, daemon)
        val ticket = RemoteCredentialLock.beginConnection()
        return store.saveVerified(backend, credentials, daemon, snapshot, capabilities, ticket, name)
    }

    @Test fun encryptedTypedRoundTripKeepsProtocolAndExactDelugeHostSeparate() {
        val transmission = creds()
        val qb = creds("qbittorrent/")
        val deluge = creds("deluge/json", "")
        assertTrue(remember(RemoteBackendKind.TRANSMISSION, transmission, name = "Movie seedbox"))
        assertTrue(remember(RemoteBackendKind.QBITTORRENT, qb, name = "Secondary"))
        assertTrue(remember(RemoteBackendKind.DELUGE, deluge, "daemon-a", "Deluge alpha"))
        assertTrue(remember(RemoteBackendKind.DELUGE, deluge, "daemon-b", "Deluge beta"))

        val saved = RememberedRemoteProtocolProfiles(context, keys).list()
        assertEquals(4, saved.size)
        assertEquals(4, saved.map { it.id }.toSet().size)
        assertEquals("Deluge beta", saved[0].label)
        assertEquals("daemon-b", saved[0].delugeDaemonHostId)
        assertEquals(RemoteBackendKind.DELUGE, saved[0].backend)
        assertEquals(password, saved[0].credentials.password)
        assertTrue(saved.any { it.backend == RemoteBackendKind.QBITTORRENT })
        assertTrue(saved.any { it.backend == RemoteBackendKind.TRANSMISSION })
        assertFalse(vaultPrefs.getString("roster", "").orEmpty().contains(password))
        assertFalse(saved[0].toString().contains(password))
        assertNull(RememberedRemoteLogin(context).load())
    }

    @Test fun resavingOneExactAccountPreservesItsFriendlyNameAndNeverReplacesAnother() {
        val alice = creds(user = "alice")
        val bob = creds(user = "bob")
        assertTrue(remember(RemoteBackendKind.TRANSMISSION, alice, name = "My name"))
        assertTrue(remember(RemoteBackendKind.TRANSMISSION, bob, name = "Other account"))
        assertTrue(remember(RemoteBackendKind.TRANSMISSION, alice))
        assertEquals(2, store.list().size)
        assertEquals("My name", store.list()[0].label)
        assertNotEquals(store.list()[0].id, store.list()[1].id)
    }

    @Test fun staleWrongIdentityAndUnsafeWriteCapabilitiesNeverSaveCredentials() {
        val c = creds()
        val (snapshot, caps) = receipt(RemoteBackendKind.TRANSMISSION, c)
        val ticket = RemoteCredentialLock.beginConnection()
        assertFalse(store.saveVerified(RemoteBackendKind.QBITTORRENT, c, null, snapshot, caps, ticket))
        val staleAt = System.currentTimeMillis() - 125_000L
        val (stale, staleCapabilities) = receipt(RemoteBackendKind.TRANSMISSION, c, observedAt = staleAt)
        assertFalse(store.saveVerified(RemoteBackendKind.TRANSMISSION, c, null,
            stale, staleCapabilities, ticket))
        val unexpectedWrites = caps.copy(evidence = caps.evidence -
            EnvironmentOperation.SESSION_REMOVE)
        assertFalse(store.saveVerified(RemoteBackendKind.TRANSMISSION, c, null,
            snapshot, unexpectedWrites, ticket))
        assertFalse(store.saveVerified(RemoteBackendKind.RTORRENT, c, null,
            snapshot, caps, ticket))
        assertTrue(store.list().isEmpty())
        assertFalse(vaultPrefs.contains("roster"))
    }

    @Test fun newerConnectTicketInvalidatesAnyEarlierAuthenticatedPersistence() {
        val c = creds()
        val (snapshot, caps) = receipt(RemoteBackendKind.TRANSMISSION, c)
        val old = RemoteCredentialLock.beginConnection()
        RemoteCredentialLock.beginConnection()
        assertFalse(store.saveVerified(RemoteBackendKind.TRANSMISSION, c, null,
            snapshot, caps, old))
        assertFalse(vaultPrefs.contains("roster"))
    }

    @Test fun corruptedCiphertextNeverGetsSilentlyOverwrittenByNewLogin() {
        val c = creds()
        assertTrue(remember(RemoteBackendKind.TRANSMISSION, c))
        vaultPrefs.edit().putString("roster", "not-valid-encrypted-data").commit()
        assertTrue(store.list().isEmpty())
        assertFalse(remember(RemoteBackendKind.TRANSMISSION, creds("another/rpc")))
        assertEquals("not-valid-encrypted-data", vaultPrefs.getString("roster", null))
    }

    @Test fun forgettingOneProtocolLeavesOthersAndDoesNotTouchLegacyVault() {
        val a = creds()
        val b = creds("qbittorrent/")
        assertTrue(remember(RemoteBackendKind.TRANSMISSION, a))
        assertTrue(remember(RemoteBackendKind.QBITTORRENT, b))
        val aId = identity(RemoteBackendKind.TRANSMISSION, a, null).value
        assertTrue(store.rename(aId, "Renamed"))
        assertTrue(store.forget(aId))
        assertEquals(1, store.list().size)
        assertEquals(RemoteBackendKind.QBITTORRENT, store.list().single().backend)
        assertFalse(keys.erased)
        assertFalse(store.forget(aId))
        assertTrue(store.forget(store.list().single().id))
        assertTrue(keys.erased)
        assertFalse(vaultPrefs.contains("roster"))
    }

    @Test fun globalRememberOptOutScrubsTypedCiphertextAndCannotRestoreIt() {
        assertTrue(remember(RemoteBackendKind.TRANSMISSION, creds()))
        assertTrue(vaultPrefs.contains("roster"))
        assertTrue(RememberedRemoteLogin(context).setRememberingEnabled(false))
        assertFalse(vaultPrefs.contains("roster"))
        assertTrue(store.list().isEmpty())
        assertTrue(RememberedRemoteLogin(context).setRememberingEnabled(true))
        assertTrue(store.list().isEmpty())
    }

    @Test fun optOutAndInvalidUserLabelRefuseWrites() {
        val c = creds()
        val (snapshot, caps) = receipt(RemoteBackendKind.TRANSMISSION, c)
        val ticket = RemoteCredentialLock.beginConnection()
        assertFalse(store.saveVerified(RemoteBackendKind.TRANSMISSION, c, null,
            snapshot, caps, ticket, "  "))
        legacyPrefs.edit().putBoolean("remember_enabled", false).commit()
        assertFalse(store.saveVerified(RemoteBackendKind.TRANSMISSION, c, null,
            snapshot, caps, ticket, "Okay"))
        assertFalse(vaultPrefs.contains("roster"))
    }
}
