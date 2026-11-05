package io.github.gamesbyian.chunks.media

import android.graphics.Bitmap
import io.github.gamesbyian.chunks.media.artwork.ArtworkLoadResult
import io.github.gamesbyian.chunks.media.artwork.ArtworkRepository
import io.github.gamesbyian.chunks.model.MediaLookupOrigin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything a transfer row/detail needs to present media identity. Immutable snapshot. */
data class TransferMediaUiState(
    val key: MediaTransferKey,
    val recognition: MediaRecognition,
    val assignment: TransferMediaAssignment? = null,
    val artwork: Bitmap? = null,
    val artworkSource: ArtworkCandidate? = null,
    val busy: Boolean = false,
    val searchResults: List<MediaCandidate>? = null,
    val message: String? = null
) {
    val state: MediaAssignmentState get() = assignment?.state ?: MediaAssignmentState.NOT_LOOKED_UP
    val match: MediaCandidate? get() = assignment?.displayMatch
    /** Category used for bundled art; adult always stays the neutral adult tile. */
    val displayKind: MediaKind get() = when {
        recognition.kind == MediaKind.ADULT -> MediaKind.ADULT
        assignment?.categoryOverride != null -> assignment.categoryOverride
        match != null -> match!!.kind
        else -> recognition.kind
    }
}

/**
 * Coordinates recognition, explicit/consented lookups, user corrections and artwork loading.
 * Opening or displaying a transfer only reads local state; network happens only for an explicit
 * user action, or for visible transfers when the user enabled automatic lookup.
 */
class TransferMediaController(
    private val service: MediaEnrichmentService,
    private val artwork: ArtworkRepository,
    storeFactory: () -> TransferMediaAssignmentStore,
    private val settings: () -> MediaPrivacySettings,
    private val network: () -> MediaNetworkState,
    private val scope: CoroutineScope,
    private val lookupDispatcher: CoroutineDispatcher,
    private val artworkDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val store by lazy(storeFactory)
    private val states = object : LinkedHashMap<MediaTransferKey, MutableStateFlow<TransferMediaUiState>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaTransferKey, MutableStateFlow<TransferMediaUiState>>) = size > 1_500
    }
    private val inputs = HashMap<MediaTransferKey, MediaRecognitionInput>()

    fun providerInfos() = service.providerInfos()

    /** Pure, synchronous and cheap: safe to call during composition via remember. */
    fun recognize(name: String, files: List<MediaFileHint> = emptyList()) = MediaRecognizer.recognize(name, files)

    @Synchronized
    fun observe(key: MediaTransferKey, name: String, files: List<MediaFileHint> = emptyList()): StateFlow<TransferMediaUiState> {
        val input = MediaRecognitionInput(name, files)
        val existing = states[key]
        if (existing != null && inputs[key] == input) return existing
        inputs[key] = input
        val flow = existing ?: MutableStateFlow(TransferMediaUiState(key, MediaRecognizer.recognize(input))).also { states[key] = it }
        if (existing != null) flow.update { it.copy(recognition = MediaRecognizer.recognize(input)) }
        scope.launch(lookupDispatcher) {
            val a = store.get(key)
            flow.update { it.copy(assignment = a) }
            if (a?.displayMatch != null) loadArtwork(key) else if (a == null) maybeAutomatic(key)
        }
        return flow
    }

    private fun flow(key: MediaTransferKey) = synchronized(this) { states[key] }

    private fun maybeAutomatic(key: MediaTransferKey) {
        val s = settings()
        if (!s.onlineLookupConsent || !s.automaticLookup) return
        runLookup(key, MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT)
    }

    /** Explicit user request: "Look up online". */
    fun lookup(key: MediaTransferKey) {
        flow(key)?.update { it.copy(busy = true, message = null) }
        scope.launch(lookupDispatcher) { runLookup(key, MediaLookupOrigin.USER_REQUEST) }
    }

    private fun runLookup(key: MediaTransferKey, origin: MediaLookupOrigin) {
        val f = flow(key) ?: return
        val recognition = f.value.recognition
        val existing = store.get(key)
        // A user choice always wins over automatic results; never overwrite it from a lookup.
        if (existing?.state == MediaAssignmentState.CHOSEN_BY_USER || existing?.state == MediaAssignmentState.CATEGORY_ART_BY_USER) {
            f.update { it.copy(busy = false, assignment = existing) }; return
        }
        val result = runCatching { service.lookup(recognition, settings(), network(), origin) }.getOrElse {
            MediaLookupResult(MediaLookupStatus.ERROR_RETRYABLE, message = "Lookup failed; try again")
        }
        val persist = when (result.status) {
            MediaLookupStatus.MATCHED, MediaLookupStatus.NEEDS_REVIEW, MediaLookupStatus.NOT_FOUND -> true
            else -> false
        }
        val assignment = TransferMediaAssignment(key,
            state = when (result.status) {
                MediaLookupStatus.MATCHED -> MediaAssignmentState.AUTOMATIC
                MediaLookupStatus.NEEDS_REVIEW -> MediaAssignmentState.SUGGESTED
                MediaLookupStatus.NOT_FOUND, MediaLookupStatus.REFUSED -> MediaAssignmentState.COULDNT_IDENTIFY
                else -> existing?.state ?: MediaAssignmentState.NOT_LOOKED_UP
            },
            match = result.match ?: existing?.match?.takeIf { !persist },
            suggestions = result.alternates, lastStatus = result.status, lastMessage = result.message,
            attempts = result.attempts, updatedAtMs = clock())
        if (persist) store.put(assignment)
        // Background attempts that were simply not allowed leave no trace in the UI.
        val quiet = origin == MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT && !persist
        f.update { it.copy(busy = false, assignment = if (persist) assignment else it.assignment ?: if (quiet) null else assignment,
            message = if (quiet) it.message else result.message) }
        if (assignment.displayMatch != null) loadArtwork(key)
    }

    /** Manual search with user-typed text (still sanitized). Results are offered, never auto-applied. */
    fun search(key: MediaTransferKey, kind: MediaKind, text: String, year: Int? = null, season: Int? = null,
               episode: Int? = null, creator: String? = null) {
        val f = flow(key) ?: return
        f.update { it.copy(busy = true, message = null) }
        scope.launch(lookupDispatcher) {
            val q = when (val d = MediaQuerySanitizer.manual(kind, text, year, season, episode, creator)) {
                is MediaQueryDecision.Refused -> { f.update { it.copy(busy = false, message = d.reason, searchResults = emptyList()) }; return@launch }
                is MediaQueryDecision.Allowed -> d.query
            }
            val r = runCatching { service.lookup(q, settings(), network(), MediaLookupOrigin.USER_REQUEST) }
                .getOrElse { MediaLookupResult(MediaLookupStatus.ERROR_RETRYABLE, message = "Search failed; try again") }
            val results = listOfNotNull(r.match) + r.alternates
            f.update { it.copy(busy = false, searchResults = results, message = if (results.isEmpty()) r.message else null) }
        }
    }

    /** User confirms a candidate (suggestion, alternate or search result). */
    fun choose(key: MediaTransferKey, candidate: MediaCandidate) {
        val f = flow(key) ?: return
        scope.launch(lookupDispatcher) {
            val prev = store.get(key)
            val a = TransferMediaAssignment(key, MediaAssignmentState.CHOSEN_BY_USER, candidate,
                suggestions = (listOfNotNull(prev?.match) + (prev?.suggestions ?: emptyList())).filter { it.ref != candidate.ref },
                lastStatus = prev?.lastStatus, lastMessage = "Chosen by you", attempts = prev?.attempts ?: emptyList(), updatedAtMs = clock())
            store.put(a)
            f.update { it.copy(assignment = a, searchResults = null, artwork = null, artworkSource = null, message = null) }
            loadArtwork(key, pin = true)
        }
    }

    /** User prefers the bundled category tile (optionally a corrected category). */
    fun useCategoryArt(key: MediaTransferKey, kind: MediaKind? = null) {
        val f = flow(key) ?: return
        scope.launch(lookupDispatcher) {
            val prev = store.get(key)
            val a = TransferMediaAssignment(key, MediaAssignmentState.CATEGORY_ART_BY_USER, prev?.match,
                prev?.suggestions ?: emptyList(), categoryOverride = kind ?: f.value.recognition.kind, updatedAtMs = clock())
            store.put(a)
            f.update { it.copy(assignment = a, artwork = null, artworkSource = null, searchResults = null) }
        }
    }

    /** Reset to automatic: forget this transfer's choice; cached provider data may be reused. */
    fun reset(key: MediaTransferKey) {
        val f = flow(key) ?: return
        scope.launch(lookupDispatcher) {
            store.remove(key)
            f.update { it.copy(assignment = null, artwork = null, artworkSource = null, searchResults = null, message = null) }
        }
    }

    fun dismissSearch(key: MediaTransferKey) { flow(key)?.update { it.copy(searchResults = null, message = null) } }

    /** Purge assignments, lookup history and downloaded artwork. Transfers are untouched. */
    fun clearAllMediaData(clearQueryCache: () -> Unit, clearArtwork: () -> Unit) {
        scope.launch(lookupDispatcher) {
            store.clear(); clearQueryCache(); clearArtwork(); artwork.clearMemory()
            synchronized(this@TransferMediaController) { states.values.toList() }.forEach { f ->
                f.update { it.copy(assignment = null, artwork = null, artworkSource = null, searchResults = null, message = null) }
            }
        }
    }

    private fun loadArtwork(key: MediaTransferKey, pin: Boolean = false) {
        val f = flow(key) ?: return
        val s = settings()
        val match = f.value.assignment?.displayMatch ?: return
        if (!s.showArtwork || match.artwork.isEmpty() || f.value.recognition.kind == MediaKind.ADULT) return
        val net = network()
        val allowNetwork = s.downloadArtwork && net.online && (!net.metered || s.artworkOnMeteredNetwork)
        scope.launch(artworkDispatcher) {
            val providerOrder = service.providerInfos().map { it.id }
            when (val r = artwork.load(match.artwork, ArtworkRole.SQUARE, allowNetwork, providerOrder)) {
                is ArtworkLoadResult.Loaded -> {
                    if (pin) artwork.pin(r.source, ArtworkRole.SQUARE)
                    f.update { cur -> if (cur.assignment?.displayMatch?.ref == match.ref) cur.copy(artwork = r.bitmap, artworkSource = r.source) else cur }
                }
                is ArtworkLoadResult.Unavailable -> {}
            }
        }
    }
}
