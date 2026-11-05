package io.github.gamesbyian.chunks.media.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.gamesbyian.chunks.media.ChunksMedia
import io.github.gamesbyian.chunks.media.LocalTransferMediaController
import io.github.gamesbyian.chunks.media.MediaFileHint
import io.github.gamesbyian.chunks.media.MediaPrivacySettings
import io.github.gamesbyian.chunks.media.MediaTransferKey
import io.github.gamesbyian.chunks.media.artwork.ArtworkBytesPolicy
import io.github.gamesbyian.chunks.media.providers.TmdbProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Complete identification surface for one transfer, ready for a details screen, sheet or route.
 * Hosts pass only identity and the original torrent name; all state lives in [ChunksMedia].
 */
@Composable
fun TransferMediaIdentifyRoute(key: MediaTransferKey, originalName: String, files: List<MediaFileHint> = emptyList(),
                               modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val media = remember(context) { ChunksMedia.get(context) }
    val controller = LocalTransferMediaController.current ?: media.controller
    val state by remember(controller, key, originalName, files) { controller.observe(key, originalName, files) }.collectAsState()
    var settings by remember { mutableStateOf(MediaPrivacySettings()) }
    var askConsent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(media) { settings = withContext(Dispatchers.IO) { media.loadSettings() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { readBounded(context, uri, ArtworkBytesPolicy.MAX_BYTES + 1) }
            if (bytes != null) controller.importUserImage(key, bytes)
        }
    }

    MediaIdentifyPanel(state, settings,
        onEnableLookup = { askConsent = true },
        onLookup = { controller.lookup(key) },
        onSearch = { kind, text, year -> controller.search(key, kind, text, year) },
        onChoose = { controller.choose(key, it) },
        onUseCategoryArt = { controller.useCategoryArt(key, it) },
        onReset = { controller.reset(key) },
        modifier = modifier,
        onPickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRemoveImage = { controller.removeUserImage(key) },
        onImageUrl = { controller.importImageUrl(key, it) })

    if (askConsent) MediaLookupConsentDialog(
        providerNames = media.providers.map { it.info.displayName },
        onConfirm = {
            askConsent = false
            // Build from the stored preferences, never from a not-yet-loaded default copy.
            scope.launch {
                settings = withContext(Dispatchers.IO) {
                    media.loadSettings().copy(onlineLookupConsent = true).also { media.updateSettings(it) }
                }
            }
        },
        onDismiss = { askConsent = false })
}

/** Informed, revocable consent text shown before the first online lookup. */
@Composable
fun MediaLookupConsentDialog(providerNames: List<String>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Turn on online lookup?") },
        text = { Text("Chunks will send a cleaned-up title, and a year, season or episode where known, to: " +
            providerNames.joinToString(", ") + ". Torrent names, magnet links, trackers, file paths, server addresses and " +
            "credentials are never sent. These services can see your network address. You can turn this off and clear " +
            "all downloaded artwork and lookup history at any time in media settings.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } })
}

/** Options → Media artwork & info, wired to [ChunksMedia]. */
@Composable
fun MediaSettingsRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val media = remember(context) { ChunksMedia.get(context) }
    var loaded by remember { mutableStateOf<MediaPrivacySettings?>(null) }
    var summary by remember { mutableStateOf("") }
    var keySet by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(media, generation) {
        withContext(Dispatchers.IO) {
            loaded = media.loadSettings()
            summary = media.cacheSummary()
            keySet = media.settingsStore.tmdbCredential()?.let { TmdbProvider.isPlausibleCredential(it) } == true
        }
    }
    var askConsent by remember { mutableStateOf<MediaPrivacySettings?>(null) }
    // Render nothing editable until stored preferences are loaded, so no default can overwrite them.
    val settings = loaded ?: return
    MediaPrivacySettingsPanel(settings, media.providers.map { it.info },
        configured = { id -> id == TmdbProvider.ID && keySet },
        onChange = { next ->
            if (next.onlineLookupConsent && !settings.onlineLookupConsent) askConsent = next
            else { loaded = next; scope.launch(Dispatchers.IO) { media.updateSettings(next) } }
        },
        onSetProviderKey = { id, value -> scope.launch(Dispatchers.IO) { media.setProviderKey(id, value); generation++ } },
        cacheSummary = summary,
        onClearData = { scope.launch { media.clearAllMediaData().join(); generation++ } },
        modifier = modifier)
    askConsent?.let { pending ->
        MediaLookupConsentDialog(media.providers.map { it.info.displayName },
            onConfirm = { askConsent = null; loaded = pending; scope.launch(Dispatchers.IO) { media.updateSettings(pending) } },
            onDismiss = { askConsent = null })
    }
}

/** Reads at most [limit] bytes; an over-limit image is passed on and rejected by the size policy. */
private fun readBounded(context: Context, uri: Uri, limit: Int): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }
}.getOrNull()
