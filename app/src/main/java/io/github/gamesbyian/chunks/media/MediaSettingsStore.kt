package io.github.gamesbyian.chunks.media

import android.content.Context
import android.util.Base64
import io.github.gamesbyian.chunks.AndroidKeystoreSealingKeys
import io.github.gamesbyian.chunks.SealedBlob
import io.github.gamesbyian.chunks.SealingKeys

/**
 * Media privacy preferences (plain, app-private) plus the user's *own* optional TMDB credential,
 * encrypted with the shared [SealedBlob] AES-GCM primitive under its own Keystore
 * alias. If the keystore is unavailable the credential is kept only for this process.
 */
class MediaSettingsStore internal constructor(
    context: Context,
    private val keys: SealingKeys
) {
    constructor(context: Context) : this(context, AndroidKeystoreSealingKeys(ALIAS))

    private val prefs = context.applicationContext.getSharedPreferences("chunks_media_settings", Context.MODE_PRIVATE)
    @Volatile private var volatileCredential: String? = null

    fun load(): MediaPrivacySettings = MediaPrivacySettings(
        onlineLookupConsent = prefs.getBoolean(K_CONSENT, true),
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
            // Reading never creates a key: a missing key is simply no credential.
            val key = checkNotNull(keys.decryptionKey()) { "Media credential key unavailable" }
            String(SealedBlob.open(key, bytes), Charsets.UTF_8)
        }.getOrNull()
    }

    /** Returns true when persisted encrypted; false when kept only in memory (or cleared). */
    fun setTmdbCredential(value: String?): Boolean {
        val v = value?.trim()?.takeIf { it.isNotEmpty() }
        if (v == null) { volatileCredential = null; prefs.edit().remove(K_TMDB).apply(); return false }
        return runCatching {
            val out = SealedBlob.seal(keys.encryptionKey(), v.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(K_TMDB, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
            volatileCredential = null
            true
        }.getOrElse { volatileCredential = v; prefs.edit().remove(K_TMDB).apply(); false }
    }

    internal companion object {
        /** Persisted alias; changing it strands the saved provider credential. */
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
