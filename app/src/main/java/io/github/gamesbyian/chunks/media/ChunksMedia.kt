package io.github.gamesbyian.chunks.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.gamesbyian.chunks.media.artwork.ArtworkDiskCache
import io.github.gamesbyian.chunks.media.artwork.ArtworkRepository
import io.github.gamesbyian.chunks.media.providers.MusicBrainzProvider
import io.github.gamesbyian.chunks.media.providers.OpenLibraryProvider
import io.github.gamesbyian.chunks.media.providers.TmdbProvider
import io.github.gamesbyian.chunks.media.providers.TvMazeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Process-wide media graph. Construction is cheap (no disk I/O); stores open lazily on the lookup
 * dispatcher. Everything lives in app-private storage: filesDir/media and cacheDir/media-art.
 */
class ChunksMedia private constructor(context: Context) {
    private val app = context.applicationContext
    val settingsStore = MediaSettingsStore(app)
    @Volatile var settings: MediaPrivacySettings = MediaPrivacySettings()
        private set
    @Volatile private var settingsLoaded = false

    private val http = UrlConnectionMediaHttp()
    private val queryCache by lazy { FileMediaQueryCache(File(app.filesDir, "media/queries.json")) }
    private val artworkDisk by lazy { ArtworkDiskCache(File(app.cacheDir, "media-art")) }
    val providers: List<MediaMetadataProvider> = listOf(TvMazeProvider(), TmdbProvider { settingsStore.tmdbCredential() },
        MusicBrainzProvider(), OpenLibraryProvider())
    private val service by lazy { MediaEnrichmentService(providers, http, lazyCache()) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val controller: TransferMediaController by lazy {
        val byId = providers.associateBy { it.info.id }
        TransferMediaController(
            service = service,
            artwork = ArtworkRepository(http, lazyDisk(), hostPolicyFor = { id: String -> byId[id]?.info?.artworkHostPolicy }),
            storeFactory = { FileTransferMediaAssignmentStore(File(app.filesDir, "media/assignments.json")) },
            settings = { currentSettings() },
            network = { networkState() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            lookupDispatcher = Dispatchers.IO.limitedParallelism(1),
            artworkDispatcher = Dispatchers.IO.limitedParallelism(2))
    }

    /** Delegates resolve the lazily-created stores only when first used off the main thread. */
    private fun lazyCache(): MediaQueryCache = object : MediaQueryCache {
        override fun get(key: io.github.gamesbyian.chunks.model.MediaMetadataCacheKey) = queryCache.get(key)
        override fun put(entry: CachedLookup) = queryCache.put(entry)
        override fun clear() = queryCache.clear()
        override val size get() = queryCache.size
    }
    private fun lazyDisk() = artworkDisk

    private fun currentSettings(): MediaPrivacySettings {
        if (!settingsLoaded) { settings = settingsStore.load(); settingsLoaded = true }
        return settings
    }

    /** Call off the main thread. */
    fun loadSettings(): MediaPrivacySettings = currentSettings()

    fun updateSettings(s: MediaPrivacySettings) {
        val withdrawn = settings.onlineLookupConsent && !s.onlineLookupConsent
        settings = s; settingsLoaded = true; settingsStore.save(s)
        if (withdrawn) providers.forEach { service.resetProviderPause(it.info.id) }
    }

    fun setProviderKey(provider: String, value: String?) {
        if (provider == TmdbProvider.ID) { settingsStore.setTmdbCredential(value); service.resetProviderPause(provider) }
    }

    fun clearAllMediaData() = controller.clearAllMediaData({ queryCache.clear() }, { artworkDisk.clear() })

    /** Off main thread: a short, title-free summary for settings. */
    fun cacheSummary(): String {
        val mb = artworkDisk.sizeBytes() / (1024.0 * 1024.0)
        return "Cached: %.1f MB artwork · %d lookups".format(mb, queryCache.size)
    }

    private fun networkState(): MediaNetworkState {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        val saver = app.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        return MediaNetworkState(online, metered, saver)
    }

    companion object {
        @Volatile private var instance: ChunksMedia? = null
        fun get(context: Context): ChunksMedia = instance ?: synchronized(this) {
            instance ?: ChunksMedia(context).also { instance = it }
        }
    }
}

/**
 * Integration point for host screens: provide a controller (normally `ChunksMedia.get(ctx).controller`)
 * high in the tree. When absent, components fall back to name-only category art without any I/O.
 */
val LocalTransferMediaController = staticCompositionLocalOf<TransferMediaController?> { null }

/**
 * Observe one transfer's media state. Pure local reads; never starts a network lookup unless the
 * user enabled automatic identification. Safe for LazyColumn rows (bounded state cache).
 */
@Composable
fun rememberTransferMedia(key: MediaTransferKey, name: String, files: List<MediaFileHint> = emptyList()): State<TransferMediaUiState> {
    val controller = LocalTransferMediaController.current
    if (controller == null) {
        val static = remember(key, name, files) { TransferMediaUiState(key, MediaRecognizer.recognize(name, files)) }
        return remember(static) { kotlinx.coroutines.flow.MutableStateFlow(static) }.collectAsState()
    }
    val flow = remember(controller, key, name, files) { controller.observe(key, name, files) }
    return flow.collectAsState()
}
