package io.github.gamesbyian.chunks

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Key access for one device-bound AES-256-GCM key alias. Shared by every
 * encrypted preference store so that key creation, lookup and erasure are
 * written once (A2611-05). Each store keeps its own alias, preference file,
 * record schema, bounds and fail-closed policy; this type owns none of those.
 *
 * Injection is per store instance. Cross-store opt-out scrubs (for example
 * [RememberedRemoteLogin.setRememberingEnabled]) construct sibling stores
 * with their production Keystore keys.
 */
internal interface SealingKeys {
    /** The existing key, or a newly generated one. Only sealing may create a key. */
    fun encryptionKey(): SecretKey

    /** The existing key, or null. Opening a record never creates a key. */
    fun decryptionKey(): SecretKey?

    /** Delete the key. Callers remove every ciphertext that references it first. */
    fun erase()
}

/**
 * Android Keystore implementation. The generated key specification is the one
 * every store has always used: AES-256, GCM, no padding, randomized encryption
 * required, no user-authentication requirement (manual Connect must work on a
 * restarted app). Changing [alias] or this specification would strand every
 * saved record on upgrade; there is no migration.
 */
internal class AndroidKeystoreSealingKeys(private val alias: String) : SealingKeys {
    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun decryptionKey(): SecretKey? = store().getKey(alias, null) as? SecretKey

    override fun encryptionKey(): SecretKey = synchronized(CREATION_LOCK) {
        // Check-then-generate must be atomic: a second concurrent generation
        // under one alias would replace the key the first caller sealed with.
        // Not every caller holds RemoteCredentialLock (the media credential).
        decryptionKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }

    override fun erase() { store().deleteEntry(alias) }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        val CREATION_LOCK = Any()
    }
}

/**
 * The persisted packing shared by every store: a 12-byte provider-generated
 * IV followed by the AES-GCM ciphertext with its 128-bit tag. Callers apply
 * their own Base64 encoding and size bounds before [open] and after [seal].
 */
internal object SealedBlob {
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Encrypt with a fresh random IV. Throws on any key or cipher failure. */
    fun seal(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        // A different IV length would produce records no reader can open.
        check(iv != null && iv.size == IV_BYTES) { "Unexpected AES-GCM IV length" }
        return iv + cipher.doFinal(plaintext)
    }

    /** Authenticate and decrypt; throws on a short, tampered or wrong-key record. */
    fun open(key: SecretKey, packed: ByteArray): ByteArray {
        require(packed.size > IV_BYTES) { "Sealed record too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES))
        return cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES)
    }
}
