package io.github.gamesbyian.chunks

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray

/**
 * Independently encrypted roster of at most 12 previously authenticated
 * rTorrent connections. Endpoints, usernames AND passwords share one
 * device-bound Keystore AES-GCM encrypted document, never plaintext prefs.
 *
 * Existing one-profile RememberedRemoteLogin remains backward compatible.
 * Callers save only AFTER authenticated connection and only while user has
 * not opted out; opting out MUST clear both encrypted vaults.
 */
internal class RememberedRemoteProfileRoster(context: Context) {
    companion object {
        const val MAX_PROFILES = 12
        private const val MAX_ENCODED = 196_608

        /** Stable profile ID: SHA-256 of endpoint + NUL + username. Also the seeding environment key. */
        internal fun identityOf(endpoint: String, username: String): String =
            io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverId(endpoint, username).value

        internal fun addressOf(endpoint: String): String = try {
            val uri = URI(endpoint.trim())
            val port = if (uri.port == -1) "" else ":" + uri.port
            (uri.host.orEmpty() + port + uri.rawPath.orEmpty()).take(160)
        } catch (_: Exception) { "rTorrent server" }
    }

    internal data class Profile(
        val id: String,
        val label: String,
        val credentials: RemoteCredentials,
        /** Wall-clock ms of the last authenticated connection; null for older records. */
        val lastVerifiedAt: Long? = null
    ) {
        /**
         * Which exact server address this profile uses, without scheme or
         * credentials: two profiles for one account on one host (for example
         * /rutorrent/ and /rutorrent2/) must never look identical.
         */
        val address: String get() = addressOf(credentials.endpoint)
    }

    private val prefs = context.applicationContext.getSharedPreferences(
        "chunks_remote_profiles_encrypted_v1", Context.MODE_PRIVATE
    )
    private val alias = "io.github.gamesbyian.chunks.remote-profile-roster-v1"
    private val validId = Regex("[a-f0-9]{64}")
    private val legacy = RememberedRemoteLogin(context.applicationContext)

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        val existing = keystore().getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun identity(credentials: RemoteCredentials): String = identityOf(credentials.endpoint, credentials.username)

    private fun valid(credentials: RemoteCredentials): Boolean =
        credentials.endpoint.length in 1..2048 &&
            credentials.username.length <= 512 &&
            credentials.password.length <= 4096 &&
            credentials.endpoint.none { it.code < 32 || it.code == 127 } &&
            credentials.username.none { it.code < 32 || it.code == 127 }

    private fun displayName(credentials: RemoteCredentials): String {
        val server = try { URI(credentials.endpoint).host } catch (_: Exception) { null }
        val host = server?.take(96) ?: "rTorrent server"
        return if (credentials.username.isBlank()) host else
            credentials.username.take(40) + " @ " + host
    }

    /** Corrupt/inaccessible encrypted records fail closed and are cleared. */
    @Synchronized
    fun list(): List<Profile> {
        if (!legacy.rememberingEnabled()) return emptyList()
        val packed = try { prefs.getString("roster", null) }
            catch (_: ClassCastException) { null } ?: return emptyList()
        return try {
            require(packed.length in 20..MAX_ENCODED)
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            require(bytes.size in 29..131_072)
            val savedKey = keystore().getKey(alias, null) as? SecretKey
                ?: throw IllegalStateException("Profile encryption key unavailable")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, savedKey,
                GCMParameterSpec(128, bytes, 0, 12))
            val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
            val rows = JSONArray(String(plain, Charsets.UTF_8))
            require(rows.length() <= MAX_PROFILES)
            val output = ArrayList<Profile>()
            val seen = HashSet<String>()
            for (i in 0 until rows.length()) {
                val entry = rows.getJSONArray(i)
                // Five fields before last-verified times were recorded.
                require(entry.length() == 5 || entry.length() == 6)
                val id = entry.getString(0)
                val label = entry.getString(1)
                val credentials = RemoteCredentials(entry.getString(2),
                    entry.getString(3), entry.getString(4))
                val verifiedAt = if (entry.length() == 6) entry.getLong(5).takeIf { it > 0 } else null
                require(validId.matches(id) && valid(credentials) &&
                    identity(credentials) == id &&
                    label.length in 1..100 && label.none { it.code < 32 || it.code == 127 } &&
                    seen.add(id))
                output += Profile(id, label, credentials, verifiedAt)
            }
            output
        } catch (_: Exception) {
            prefs.edit().remove("roster").commit()
            emptyList()
        }
    }

    /** Never call until remote authentication has succeeded and opt-out is off. */
    @Synchronized
    fun saveVerified(
        credentials: RemoteCredentials, preferredLabel: String? = null,
        verifiedAt: Long = System.currentTimeMillis()
    ): Boolean {
        if (!legacy.rememberingEnabled() || !valid(credentials)) return false
        val name = preferredLabel?.trim()?.takeIf {
            it.length in 1..100 && it.none { c -> c.code < 32 || c.code == 127 }
        } ?: displayName(credentials)
        val id = identity(credentials)
        val current = list().toMutableList()
        val existing = current.indexOfFirst { it.id == id }
        if (existing >= 0) current.removeAt(existing)
        else if (current.size >= MAX_PROFILES) return false
        current.add(0, Profile(id, name, credentials, verifiedAt))
        return persist(current)
    }

    @Synchronized
    fun forget(id: String): Boolean {
        if (!validId.matches(id)) return false
        val current = list()
        if (current.none { it.id == id }) return false
        return persist(current.filterNot { it.id == id })
    }

    /** Clear ciphertext before removing the key. Never restore stale secrets. */
    @Synchronized
    fun clearAll(): Boolean {
        val erased = prefs.edit().remove("roster").commit()
        if (!erased) return false
        runCatching { keystore().deleteEntry(alias) }
        return true
    }

    private fun persist(profiles: List<Profile>): Boolean {
        require(profiles.size <= MAX_PROFILES)
        if (profiles.isEmpty()) return clearAll()
        return try {
            val rows = JSONArray()
            for (item in profiles) rows.put(JSONArray()
                .put(item.id).put(item.label)
                .put(item.credentials.endpoint).put(item.credentials.username)
                .put(item.credentials.password).put(item.lastVerifiedAt ?: 0L))
            writeRows(rows)
        } catch (_: Exception) { false }
    }

    /** Encrypts and stores [rows] as-is; tests use it to write older record shapes. */
    @androidx.annotation.VisibleForTesting
    internal fun writeRows(rows: JSONArray): Boolean {
        return try {
            val raw = rows.toString().toByteArray(Charsets.UTF_8)
            require(raw.size <= 100_000)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val packed = cipher.iv + cipher.doFinal(raw)
            val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
            require(encoded.length <= MAX_ENCODED)
            prefs.edit().putString("roster", encoded).commit()
        } catch (_: Exception) { false }
    }
}
