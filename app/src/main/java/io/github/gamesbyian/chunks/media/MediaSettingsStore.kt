package io.github.gamesbyian.chunks.media

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Media privacy preferences (plain, app-private) plus the user's *own* optional TMDB credential,
 * encrypted with an AndroidKeyStore AES-GCM key (same pattern as RememberedRemoteLogin, separate
 * alias). If the keystore is unavailable the credential is kept only for this process.
 */
class MediaSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("chunks_media_settings", Context.MODE_PRIVATE)
    @Volatile private var volatileCredential: String? = null

    fun load(): MediaPrivacySettings = MediaPrivacySettings(
        onlineLookupConsent = prefs.getBoolean(K_CONSENT, false),
        automaticLookup = prefs.getBoolean(K_AUTO, true),
        disabledProviders = prefs.getStringSet(K_DISABLED, emptySet())?.toSet() ?: emptySet(),
        enabledProviders = prefs.getStringSet(K_ENABLED, emptySet())?.toSet() ?: emptySet(),
        showArtwork = prefs.getBoolean(K_SHOW, true),
        downloadArtwork = prefs.getBoolean(K_DOWNLOAD, true),
        artworkOnMeteredNetwork = prefs.getBoolean(K_METERED, false),
        largeListArtwork = prefs.getBoolean(K_LARGE_LIST, false),
        showSimilarTransfers = prefs.getBoolean(K_SIMILAR, true),
        similarSameEnvironmentOnly = prefs.getBoolean(K_SIMILAR_SAME_ENV, false))

    fun save(s: MediaPrivacySettings) {
        prefs.edit().putBoolean(K_CONSENT, s.onlineLookupConsent)
            .putBoolean(K_AUTO, s.automaticLookup)
            .putStringSet(K_DISABLED, s.disabledProviders).putStringSet(K_ENABLED, s.enabledProviders)
            .putBoolean(K_SHOW, s.showArtwork).putBoolean(K_DOWNLOAD, s.downloadArtwork)
            .putBoolean(K_METERED, s.artworkOnMeteredNetwork).putBoolean(K_LARGE_LIST, s.largeListArtwork)
            .putBoolean(K_SIMILAR, s.showSimilarTransfers).putBoolean(K_SIMILAR_SAME_ENV, s.similarSameEnvironmentOnly).apply()
    }

    fun tmdbCredential(): String? {
        volatileCredential?.let { return it }
        val packed = prefs.getString(K_TMDB, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    /** Returns true when persisted encrypted; false when kept only in memory (or cleared). */
    fun setTmdbCredential(value: String?): Boolean {
        val v = value?.trim()?.takeIf { it.isNotEmpty() }
        if (v == null) { volatileCredential = null; prefs.edit().remove(K_TMDB).apply(); return false }
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(v.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(K_TMDB, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
            volatileCredential = null
            true
        }.getOrElse { volatileCredential = v; prefs.edit().remove(K_TMDB).apply(); false }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "chunks_media_provider_credential_v1"
        const val K_CONSENT = "online_lookup_consent"
        const val K_AUTO = "automatic_lookup"
        const val K_DISABLED = "disabled_providers"
        const val K_ENABLED = "enabled_providers"
        const val K_SHOW = "show_artwork"
        const val K_DOWNLOAD = "download_artwork"
        const val K_METERED = "artwork_on_metered"
        const val K_LARGE_LIST = "large_list_artwork"
        const val K_SIMILAR = "show_similar_transfers"
        const val K_SIMILAR_SAME_ENV = "similar_same_environment_only"
        const val K_TMDB = "tmdb_credential_v1"
    }
}
