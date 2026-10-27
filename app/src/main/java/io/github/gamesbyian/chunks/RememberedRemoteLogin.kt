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
 * One opt-out saved rTorrent profile, encrypted with a device-bound Android
 * Keystore AES-GCM key. The credential record never enters source, backups,
 * exported components, or cleartext preferences. No biometric requirement:
 * background-free manual Connect remains possible on a restarted app.
 *
 * A lost/inaccessible Keystore key is a fail-closed logged-out state. Chunks
 * never falls back to a hardcoded key or unencrypted password storage.
 */
internal class RememberedRemoteLogin(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "remembered-remote-login-v1", Context.MODE_PRIVATE
    )
    private val alias = "io.github.gamesbyian.chunks.remembered-remote-login-v1"

    @Synchronized fun hasSavedLogin(): Boolean = prefs.contains("ciphertext")

    @Synchronized fun load(): RemoteCredentials? {
        val text = prefs.getString("ciphertext", null) ?: return null
        return try {
            require(text.length in 16..32768)
            val packed = Base64.decode(text, Base64.NO_WRAP)
            require(packed.size in 13..16384)
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
            // Corrupt, outdated or device-key-inaccessible data must not be
            // reused or silently transformed into a blank-password login.
            prefs.edit().remove("ciphertext").commit()
            null
        }
    }

    /** Return true only after encrypted state is durably committed. */
    @Synchronized fun save(credentials: RemoteCredentials): Boolean {
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

    /** Clear encrypted profile first; remove its key if the store is empty. */
    @Synchronized fun clear(): Boolean {
        if (!prefs.edit().remove("ciphertext").commit()) return false
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
