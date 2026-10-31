package io.github.gamesbyian.chunks

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray

/**
 * One process-wide serialization boundary for the legacy encrypted login,
 * roster, and remember/forget policy. Never hold instance monitors across
 * these stores: opposite lock orders can deadlock concurrent Connect/Forget.
 */
internal object RemoteCredentialLock {
    val monitor = Any()
    private var generation = 0L

    /** Read the current generation without claiming a new connection. */
    fun capture(): Long = synchronized(monitor) { generation }

    /**
     * Every new asynchronous Connect has its own generation. Even when two
     * Connect requests start with remembering enabled, only the most recent
     * one can persist credentials after network suspension.
     */
    fun beginConnection(): Long = synchronized(monitor) { ++generation }
    fun current(ticket: Long): Boolean = synchronized(monitor) { ticket == generation }
    fun invalidate() = synchronized(monitor) { generation++ }
}

/**
 * One opt-out saved rTorrent profile, encrypted with a device-bound Android
 * Keystore AES-GCM key. The credential record never enters source, backups,
 * exported components, or cleartext preferences. No biometric requirement:
 * background-free manual Connect remains possible on a restarted app.
 *
 * A lost/inaccessible Keystore key is a fail-closed logged-out state. Chunks
 * never falls back to a hardcoded key or unencrypted password storage.
 */

internal class RememberedRemoteLogin(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        "remembered-remote-login-v1", Context.MODE_PRIVATE
    )
    private val alias = "io.github.gamesbyian.chunks.remembered-remote-login-v1"

    /** Inject a transient key-access failure in device tests without changing the key. */
    @androidx.annotation.VisibleForTesting
    internal var rejectDecryptForTest = false

    /** Default-on only for users who have never explicitly opted out. */
    fun rememberingEnabled(): Boolean = synchronized(RemoteCredentialLock.monitor) {
        prefs.getBoolean("remember_enabled", true)
    }

    /**
     * Opting out is durable across process restarts. Commit both the policy
     * and removal of encrypted credentials in a single preferences transaction.
     * Losing the Keystore key is acceptable once no ciphertext can reference it.
     */
    fun setRememberingEnabled(enabled: Boolean): Boolean = synchronized(RemoteCredentialLock.monitor) {
        // Opt-out first durably revokes BOTH vaults' read permissions.
        // If an older app or a crash left roster ciphertext behind, purge it
        // BEFORE re-enabling; a failed purge must leave remembering disabled.
        val previouslyEnabled = rememberingEnabled()
        if (enabled && !previouslyEnabled &&
            !RememberedRemoteProfileRoster(appContext).clearAll()) return false
        val editor = prefs.edit().putBoolean("remember_enabled", enabled)
        // The one-profile vault must be scrubbed on OFF and on a later
        // OFF→ON transition. Otherwise a legacy interrupted opt-out could
        // resurrect its old login when remembering is re-enabled.
        if (!enabled || !previouslyEnabled) editor.remove("ciphertext")
        if (!editor.commit()) return false
        // Invalidate any pending Connect's right to persist even if a user
        // toggles remembering OFF and back ON before its network call finishes.
        RemoteCredentialLock.invalidate()
        if (!enabled) {
            runCatching { keyStore().deleteEntry(alias) }
            // If a roster scrub fails, the OFF policy is already committed.
            // Re-enabling is blocked until its scrub succeeds.
            return RememberedRemoteProfileRoster(appContext).clearAll()
        }
        return true
    }

    fun hasSavedLogin(): Boolean = synchronized(RemoteCredentialLock.monitor) {
        rememberingEnabled() && prefs.contains("ciphertext")
    }

    fun load(): RemoteCredentials? = synchronized(RemoteCredentialLock.monitor) {
        if (!rememberingEnabled()) return null
        val text = try { prefs.getString("ciphertext", null) }
            catch (_: ClassCastException) { return null } ?: return null
        return try {
            require(text.length in 16..32768)
            val packed = Base64.decode(text, Base64.NO_WRAP)
            require(packed.size in 13..16384)
            if (rejectDecryptForTest) throw IllegalStateException("Injected saved-login key outage")
            val key = keyStore().getKey(alias, null) as? SecretKey
                ?: throw IllegalStateException("Saved encryption key unavailable")
            val decipher = Cipher.getInstance("AES/GCM/NoPadding")
            decipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, packed, 0, 12))
            val decoded = decipher.doFinal(packed, 12, packed.size - 12)
            val fields = JSONArray(String(decoded, Charsets.UTF_8))
            require(fields.length() == 3)
            val endpoint = fields.getString(0)
            val username = fields.getString(1)
            val password = fields.getString(2)
            require(endpoint.isNotBlank() && endpoint.length <= 2048 &&
                username.length <= 512 && password.length <= 4096)
            RemoteCredentials(endpoint, username, password)
        } catch (_: Exception) {
            // Keep unopenable ciphertext untouched. A temporary Keystore
            // failure never authorizes deleting or overwriting the user's
            // only saved login. Explicit Clear or opt-out still purges it.
            null
        }
    }

    /** Return true only after encrypted state is durably committed. */
    fun save(credentials: RemoteCredentials): Boolean = synchronized(RemoteCredentialLock.monitor) {
        if (!rememberingEnabled()) return false
        // Never silently replace an occupied but unreadable vault with a new
        // credential. The user must explicitly clear the inaccessible record.
        if (prefs.contains("ciphertext") && load() == null) return false
        require(credentials.endpoint.length in 1..2048 &&
            credentials.username.length <= 512 && credentials.password.length <= 4096)
        val encoded = JSONArray().put(credentials.endpoint).put(credentials.username)
            .put(credentials.password).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrMakeKey())
        val packed = cipher.iv + cipher.doFinal(encoded)
        return prefs.edit().putString(
            "ciphertext", Base64.encodeToString(packed, Base64.NO_WRAP)
        ).commit()
    }

    /**
     * Save a verified connection only if the policy and explicit Forget state
     * have not changed since Connect began. UI must pass a ticket captured at
     * Connect start and invalidate it on disconnect/replacement.
     */
    fun saveIfCurrent(credentials: RemoteCredentials, ticket: Long): Boolean =
        synchronized(RemoteCredentialLock.monitor) {
            if (!RemoteCredentialLock.current(ticket)) false else save(credentials)
        }

    /** Clear encrypted profile first; remove its key if the store is empty. */
    fun clear(): Boolean = synchronized(RemoteCredentialLock.monitor) {
        if (!prefs.edit().remove("ciphertext").commit()) return false
        RemoteCredentialLock.invalidate()
        return try {
            keyStore().deleteEntry(alias)
            true
        } catch (_: Exception) {
            // No ciphertext remains. A stranded key cannot recreate it.
            true
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrMakeKey(): SecretKey {
        val existing = keyStore().getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
