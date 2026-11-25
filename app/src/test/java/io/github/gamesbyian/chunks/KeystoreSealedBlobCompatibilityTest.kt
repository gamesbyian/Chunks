package io.github.gamesbyian.chunks

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.media.MediaSettingsStore
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A2611-05: the shared [SealedBlob]/[SealingKeys] primitive must keep every
 * store byte-compatible with records written by earlier releases. Golden
 * records are produced here by an independent javax.crypto encoder with a
 * fixed IV (never through [SealedBlob]), and records the stores write are
 * decoded independently, so a packing, encoding, alias or schema drift in
 * the primitive cannot pass by agreeing with itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeystoreSealedBlobCompatibilityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** Software AES key standing in for a device-bound Keystore entry. */
    private class RecordingKeys(seed: Int = 13) : SealingKeys {
        var secret: SecretKey? = SecretKeySpec(ByteArray(32) { (it * 7 + seed).toByte() }, "AES")
        var created = 0
        var erased = 0
        var onErase: () -> Unit = {}
        val material = secret!!
        override fun encryptionKey(): SecretKey = secret ?: material.also { created++; secret = it }
        override fun decryptionKey(): SecretKey? = secret
        override fun erase() { onErase(); erased++; secret = null }
    }

    private val loginPrefs = RememberedRemoteLogin.PREFS
    private val rosterPrefs = RememberedRemoteProfileRoster.PREFS
    private val protocolPrefs = RememberedRemoteProtocolProfiles.PREFS
    private val mediaPrefs = "chunks_media_settings"

    @Before @After fun reset() {
        listOf(loginPrefs, rosterPrefs, protocolPrefs, mediaPrefs).forEach { prefs(it).edit().clear().commit() }
    }

    private val fixedIv = ByteArray(12) { (0xA0 + it).toByte() }

    /** Independent encoder for the historical `Base64(NO_WRAP, iv || AES-GCM(ct || tag128))` layout. */
    private fun legacySeal(key: SecretKey, plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, fixedIv))
        return Base64.encodeToString(fixedIv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** Independent decoder for a record the refactored stores wrote. */
    private fun legacyOpen(key: SecretKey, encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, packed, 0, 12))
        return String(cipher.doFinal(packed, 12, packed.size - 12), Charsets.UTF_8)
    }

    @Test fun persistedAliasesAndPreferenceFilesAreUnchanged() {
        // Any change strands every saved credential on upgrade; there is no migration.
        assertEquals("io.github.gamesbyian.chunks.remembered-remote-login-v1", RememberedRemoteLogin.KEY_ALIAS)
        assertEquals("remembered-remote-login-v1", RememberedRemoteLogin.PREFS)
        assertEquals("io.github.gamesbyian.chunks.remote-profile-roster-v1", RememberedRemoteProfileRoster.KEY_ALIAS)
        assertEquals("chunks_remote_profiles_encrypted_v1", RememberedRemoteProfileRoster.PREFS)
        assertEquals("io.github.gamesbyian.chunks.other-remote-profile-roster-v1", RememberedRemoteProtocolProfiles.KEY_ALIAS)
        assertEquals("chunks_other_remote_profiles_encrypted_v1", RememberedRemoteProtocolProfiles.PREFS)
        assertEquals("chunks_media_provider_credential_v1", MediaSettingsStore.ALIAS)
    }

    @Test fun sealedBlobUsesFreshTwelveByteIvAndRejectsTamperShortAndWrongKey() {
        val keys = RecordingKeys()
        val plain = "secret".toByteArray()
        val a = SealedBlob.seal(keys.encryptionKey(), plain)
        val b = SealedBlob.seal(keys.encryptionKey(), plain)
        assertEquals(12 + plain.size + 16, a.size)
        assertFalse("IV must be random per seal", a.copyOf(12).contentEquals(b.copyOf(12)))
        assertArrayEquals(plain, SealedBlob.open(keys.material, a))

        val tampered = a.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { SealedBlob.open(keys.material, tampered) }
        assertThrows(IllegalArgumentException::class.java) { SealedBlob.open(keys.material, a.copyOf(12)) }
        val other = RecordingKeys(seed = 99).material
        assertThrows(AEADBadTagException::class.java) { SealedBlob.open(other, a) }
    }

    @Test fun legacyLoginGoldenRecordOpensAndNewSaveKeepsLayout() {
        val keys = RecordingKeys()
        val golden = legacySeal(keys.material, JSONArray().put("https://seed.example/RPC2").put("ann").put("pw-1").toString())
        prefs(loginPrefs).edit().putString("ciphertext", golden).commit()

        val vault = RememberedRemoteLogin(context, keys)
        assertEquals(RemoteCredentials("https://seed.example/RPC2", "ann", "pw-1"), vault.load())

        assertTrue(vault.save(RemoteCredentials("https://seed.example/RPC3", "bo", "pw-2")))
        val written = prefs(loginPrefs).getString("ciphertext", null)!!
        val fields = JSONArray(legacyOpen(keys.material, written))
        assertEquals(listOf("https://seed.example/RPC3", "bo", "pw-2"), (0 until 3).map { fields.getString(it) })
        assertEquals("existing key reused, never regenerated", 0, keys.created)
    }

    @Test fun legacyLoginKeyOutageAndTamperFailClosedWithoutDestroyingCiphertext() {
        val keys = RecordingKeys()
        val golden = legacySeal(keys.material, JSONArray().put("https://seed.example/RPC2").put("ann").put("pw").toString())
        prefs(loginPrefs).edit().putString("ciphertext", golden).commit()
        val vault = RememberedRemoteLogin(context, keys)

        keys.secret = null
        assertNull(vault.load())
        assertFalse("unreadable vault must not be overwritten", vault.save(RemoteCredentials("https://x.example/", "u", "p")))
        assertEquals(golden, prefs(loginPrefs).getString("ciphertext", null))
        assertEquals("opening never creates a key", 0, keys.created)

        keys.secret = keys.material
        val packed = Base64.decode(golden, Base64.NO_WRAP).also { it[20] = (it[20].toInt() xor 0x40).toByte() }
        val tampered = Base64.encodeToString(packed, Base64.NO_WRAP)
        prefs(loginPrefs).edit().putString("ciphertext", tampered).commit()
        assertNull(vault.load())
        assertEquals(tampered, prefs(loginPrefs).getString("ciphertext", null))
    }

    @Test fun legacyLoginClearRemovesCiphertextBeforeErasingKey() {
        val keys = RecordingKeys()
        val vault = RememberedRemoteLogin(context, keys)
        assertTrue(vault.save(RemoteCredentials("https://seed.example/RPC2", "ann", "pw")))
        var ciphertextAtErase: Boolean? = null
        keys.onErase = { ciphertextAtErase = prefs(loginPrefs).contains("ciphertext") }
        assertTrue(vault.clear())
        assertEquals(false, ciphertextAtErase)
        assertEquals(1, keys.erased)
        assertNull(vault.load())
    }

    @Test fun rosterGoldenRowsOfBothHistoricalShapesOpenAndPersistedOutputDecodes() {
        val keys = RecordingKeys()
        val a = RemoteCredentials("https://seed.example/rutorrent/RPC2", "ann", "pw-a")
        val b = RemoteCredentials("https://seed.example/rutorrent2/RPC2", "ann", "pw-b")
        val idA = RememberedRemoteProfileRoster.identityOf(a.endpoint, a.username)
        val idB = RememberedRemoteProfileRoster.identityOf(b.endpoint, b.username)
        val rows = JSONArray()
            .put(JSONArray().put(idA).put("Alpha").put(a.endpoint).put(a.username).put(a.password).put(1_700_000_000_000L))
            .put(JSONArray().put(idB).put("Beta").put(b.endpoint).put(b.username).put(b.password)) // pre-timestamp shape
        val golden = legacySeal(keys.material, rows.toString())
        prefs(rosterPrefs).edit().putString("roster", golden).commit()

        val roster = RememberedRemoteProfileRoster(context, keys)
        val listed = roster.list()
        assertEquals(listOf(idA, idB), listed.map { it.id })
        assertEquals(listOf(a, b), listed.map { it.credentials })
        assertEquals(listOf(1_700_000_000_000L, null), listed.map { it.lastVerifiedAt })
        assertEquals("a read never rewrites an unmigrated record", golden, prefs(rosterPrefs).getString("roster", null))

        assertTrue(roster.rename(idB, "Beta renamed"))
        val decoded = JSONArray(legacyOpen(keys.material, prefs(rosterPrefs).getString("roster", null)!!))
        assertEquals(2, decoded.length())
        assertEquals("Beta renamed", decoded.getJSONArray(1).getString(1))
        assertEquals(b.password, decoded.getJSONArray(1).getString(4))
    }

    @Test fun rosterKeyOutageIsOccupiedNotEmpty() {
        val keys = RecordingKeys()
        val c = RemoteCredentials("https://seed.example/RPC2", "ann", "pw")
        val rows = JSONArray().put(JSONArray().put(RememberedRemoteProfileRoster.identityOf(c.endpoint, c.username))
            .put("Alpha").put(c.endpoint).put(c.username).put(c.password).put(1L))
        val golden = legacySeal(keys.material, rows.toString())
        prefs(rosterPrefs).edit().putString("roster", golden).commit()
        keys.secret = null

        val roster = RememberedRemoteProfileRoster(context, keys)
        assertTrue(roster.list().isEmpty())
        assertFalse(roster.saveVerified(RemoteCredentials("https://other.example/RPC2", "zed", "pw")))
        assertEquals(golden, prefs(rosterPrefs).getString("roster", null))
        assertEquals(0, keys.created)
    }

    @Test fun mediaCredentialGoldenOpensAndMissingKeyIsNotCreatedByRead() {
        val keys = RecordingKeys()
        prefs(mediaPrefs).edit().putString("tmdb_credential_v1", legacySeal(keys.material, "tmdb-token")).commit()
        assertEquals("tmdb-token", MediaSettingsStore(context, keys).tmdbCredential())

        val absent = RecordingKeys().also { it.secret = null }
        assertNull(MediaSettingsStore(context, absent).tmdbCredential())
        assertEquals("reading must not create a key", 0, absent.created)

        assertTrue(MediaSettingsStore(context, keys).setTmdbCredential("  new-token "))
        assertEquals("new-token", legacyOpen(keys.material, prefs(mediaPrefs).getString("tmdb_credential_v1", null)!!))
    }
}
