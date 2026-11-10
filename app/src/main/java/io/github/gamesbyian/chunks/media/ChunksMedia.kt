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
import io.github.gamesbyian.chunks.media.providers.AniListProvider
import io.github.gamesbyian.chunks.media.providers.MusicBrainzProvider
import io.github.gamesbyian.chunks.media.providers.OpenLibraryProvider
import io.github.gamesbyian.chunks.media.providers.TmdbProvider
import io.github.gamesbyian.chunks.media.providers.TvMazeProvider
import io.github.gamesbyian.chunks.media.providers.WikidataProvider
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
    private val settingsLock = Any()

    private val http = UrlConnectionMediaHttp()
    private val queryCache by lazy { FileMediaQueryCache(File(app.filesDir, "media/queries.json")) }
    private val artworkDisk by lazy { ArtworkDiskCache(File(app.cacheDir, "media-art")) }
    val providers: List<MediaMetadataProvider> = listOf(TvMazeProvider(), TmdbProvider { settingsStore.tmdbCredential() },
        WikidataProvider(), MusicBrainzProvider(), OpenLibraryProvider(), AniListProvider())
    private val service by lazy { MediaEnrichmentService(providers, http, lazyCache()) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val controllerLazy = lazy {
        val byId = providers.associateBy { it.info.id }
        TransferMediaController(
            service = service,
            artwork = ArtworkRepository(http, lazyDisk(), hostPolicyFor = { id: String ->
                // Provider disable is a network revocation, including stored matches. Cached
                // derivatives still load offline because ArtworkRepository checks disk first.
                byId[id]?.info?.takeIf { currentSettings().providerOn(it) }?.artworkHostPolicy
            }),
            storeFactory = { FileTransferMediaAssignmentStore(File(app.filesDir, "media/assignments.json")) },
            settings = { currentSettings() },
            network = { networkState() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            lookupDispatcher = Dispatchers.IO.limitedParallelism(1),
            artworkDispatcher = Dispatchers.IO.limitedParallelism(2),
            backgroundDispatcher = Dispatchers.IO.limitedParallelism(1),
            ruleStoreFactory = { FileMediaRuleStore(File(app.filesDir, "media/rules.json")) },
            automationStoreFactory = { FileMediaAutomationPreferences(File(app.filesDir, "media/automation.json")) })
    }
    val controller: TransferMediaController by controllerLazy

    // Retain only explicitly opened, revocable full-inventory sessions. Never
    // infer remote/profile authority from a name, a selected scope or past data.
    private val inventorySessions =
        java.util.concurrent.CopyOnWriteArraySet<MediaInventoryEnrichmentSession>()

    /**
     * Host-level inventory owner: start at the canonical Transfers/activity source, not
     * from LazyColumn rows or Details. Call replace() for fresh authorized inventory,
     * and close() when the host/privacy scope ends. The supplied witness MUST check
     * current exact environment/profile freshness and privacy permission dynamically.
     */
    fun openInventorySession(ownerScope: CoroutineScope,
                             forController: TransferMediaController = controller): MediaInventoryEnrichmentSession {
        val session = MediaInventoryEnrichmentSession(
            ownerScope,
            enrich = { entries, authorized -> forController.enrichInventory(entries, authorized) },
            onClosed = { inventorySessions.remove(it) })
        inventorySessions.add(session)
        return session
    }

    /** Recheck offscreen inventory following settings/credentials changes without UI composition. */
    private fun refreshInventorySessions() {
        inventorySessions.forEach { it.refreshRevoking() }
    }

    /** Delegates resolve the lazily-created stores only when first used off the main thread. */
    private fun lazyCache(): MediaQueryCache = object : MediaQueryCache {
        override fun get(key: io.github.gamesbyian.chunks.model.MediaMetadataCacheKey) = queryCache.get(key)
        override fun put(entry: CachedLookup) = queryCache.put(entry)
        override fun clear() = queryCache.clear()
        override val size get() = queryCache.size
    }
    private fun lazyDisk() = artworkDisk

    private fun currentSettings(): MediaPrivacySettings = synchronized(settingsLock) {
        if (!settingsLoaded) { settings = settingsStore.load(); settingsLoaded = true }
        settings
    }

    /** Call off the main thread. */
    fun loadSettings(): MediaPrivacySettings = currentSettings()

    fun updateSettings(s: MediaPrivacySettings) {
        // Initial lazy disk loading and revocable-consent writes are atomic with
        // respect to each other. A racing first reader must never restore an
        // older, already-revoked online provider consent snapshot in memory.
        val old = synchronized(settingsLock) {
            val prior = if (settingsLoaded) settings else settingsStore.load()
            settings = s
            settingsLoaded = true
            settingsStore.save(s)
            prior
        }
        val withdrawn = old.onlineLookupConsent && !s.onlineLookupConsent
        val newlyAvailableArtwork = (!old.downloadArtwork && s.downloadArtwork) ||
            (!old.showArtwork && s.showArtwork)
        val providerSetChanged = old.disabledProviders != s.disabledProviders ||
            old.enabledProviders != s.enabledProviders
        // Never acquire a provider/service lock while holding settingsLock:
        // active provider requests can recheck this same settings snapshot.
        if (withdrawn) providers.forEach { service.resetProviderPause(it.info.id) }
        if (controllerLazy.isInitialized()) {
            if (providerSetChanged || newlyAvailableArtwork)
                controller.onProviderConfigurationChanged()
            else controller.onSettingsChanged()
        }
        refreshInventorySessions()
    }

    fun setProviderKey(provider: String, value: String?) {
        if (provider == TmdbProvider.ID) {
            settingsStore.setTmdbCredential(value)
            service.resetProviderPause(provider)
            // A newly configured provider can fill artwork on already-confirmed assignments.
            if (controllerLazy.isInitialized()) controller.onProviderConfigurationChanged()
            refreshInventorySessions()
        }
    }

    // Metadata cache deletion shares the enrichment service lock with provider result writes.
    fun clearAllMediaData() = controller.clearAllMediaData({ service.clearLookupHistory() }, { artworkDisk.clear() })

    /** Off main thread: a short, title-free summary for settings. */
    fun cacheSummary(): String {
        val mb = artworkDisk.sizeBytes() / (1024.0 * 1024.0)
        val names = providers.associate { it.info.id to it.info.displayName }
        val diag = controller.diagnostics()
        return "Cached: %.1f MB artwork · %d lookups\n".format(mb, queryCache.size) + diag.summary { names[it] ?: it }
    }

    /**
     * Pure platform reachability/power snapshot for lifecycle-owned media refresh.
     * No provider calls, torrent/network mutation or preference writes.
     */
    fun currentNetworkState(): MediaNetworkState = networkState()

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
fun rememberTransferMedia(key: MediaTransferKey, name: String, files: List<MediaFileHint> = emptyList(),
                          controller: TransferMediaController? = LocalTransferMediaController.current): State<TransferMediaUiState> {
    if (controller == null) {
        val static = remember(key, name, files) { TransferMediaUiState(key, MediaRecognizer.recognize(name, files)) }
        return remember(static) { kotlinx.coroutines.flow.MutableStateFlow(static) }.collectAsState()
    }
    val authorized = remember(controller, key) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val isAuthorized = remember(authorized) { { authorized.get() } }
    val flow = remember(controller, key, name, files) { controller.observe(key, name, files, isAuthorized) }
    androidx.compose.runtime.DisposableEffect(controller, key) {
        authorized.set(true)
        controller.refreshAutomatic(key)
        onDispose { authorized.set(false); controller.stopObserving(key, isAuthorized) }
    }
    return flow.collectAsState()
}

/**
 * Confirmed match titles for list search, refreshed off the main thread whenever an assignment
 * changes. Empty without a controller (tests, previews) — search then matches names only.
 */
@Composable
fun rememberMediaSearchTitles(): State<Map<MediaTransferKey, String>> {
    val controller = LocalTransferMediaController.current
        ?: return remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<MediaTransferKey, String>()) }.collectAsState()
    val revision = controller.assignmentRevision.collectAsState().value
    return androidx.compose.runtime.produceState(emptyMap(), controller, revision) {
        value = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { controller.searchableTitles() }.getOrDefault(emptyMap()) }
    }
}
