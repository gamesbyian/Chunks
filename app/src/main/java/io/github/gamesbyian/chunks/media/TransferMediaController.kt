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
    /** Aspect-preserving poster/cover for detail headers; loaded only on request. */
    val poster: Bitmap? = null,
    val busy: Boolean = false,
    val searchResults: List<MediaCandidate>? = null,
    val message: String? = null
) {
    val state: MediaAssignmentState get() = assignment?.state ?: MediaAssignmentState.NOT_LOOKED_UP
    val match: MediaCandidate? get() = assignment?.displayMatch
    val userArtwork: ArtworkCandidate? get() = assignment?.userArtwork
    /** Real (non-category) art may be shown: confident/chosen match or the user's own image; never adult. */
    val mayShowRealArt: Boolean get() = (match != null || userArtwork != null) && displayKind != MediaKind.ADULT
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
    private val clock: () -> Long = System::currentTimeMillis,
    /** Separate lane so automatic lookups never queue ahead of explicit user actions. */
    private val backgroundDispatcher: CoroutineDispatcher = lookupDispatcher
) {
    companion object {
        const val MAX_STATES = 96
        const val MAX_PENDING_AUTOMATIC = 6
    }
    private val pendingAutomatic = java.util.concurrent.atomic.AtomicInteger(0)

    private val store by lazy(storeFactory)
    private val states = object : LinkedHashMap<MediaTransferKey, MutableStateFlow<TransferMediaUiState>>(64, 0.75f, true) {
        // Each state may reference a ~0.6 MB bitmap: keep only recently observed rows (visible + margin).
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaTransferKey, MutableStateFlow<TransferMediaUiState>>): Boolean {
            val evict = size > MAX_STATES
            if (evict) inputs.remove(eldest.key)
            return evict
        }
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
            if (a?.displayMatch != null || a?.userArtwork != null) loadArtwork(key) else if (a == null) maybeAutomatic(key)
        }
        return flow
    }

    private fun flow(key: MediaTransferKey) = synchronized(this) { states[key] }

    private fun maybeAutomatic(key: MediaTransferKey) {
        val s = settings()
        if (!s.onlineLookupConsent || !s.automaticLookup) return
        // Bounded: extra rows are simply retried the next time they are observed.
        if (pendingAutomatic.incrementAndGet() > MAX_PENDING_AUTOMATIC) { pendingAutomatic.decrementAndGet(); return }
        scope.launch(backgroundDispatcher) {
            try {
                // Skip rows scrolled far away (evicted) before their turn came.
                if (flow(key) != null && store.get(key) == null) runLookup(key, MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT)
            } finally { pendingAutomatic.decrementAndGet() }
        }
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
            f.update { it.copy(busy = false, assignment = existing,
                message = if (origin == MediaLookupOrigin.USER_REQUEST) "Your choice is kept. Use Reset to automatic to look up again, or search below." else it.message) }
            return
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
            prev?.takeIf { it.match?.ref != candidate.ref }?.let { releaseChosenArt(it) }
            val a = TransferMediaAssignment(key, MediaAssignmentState.CHOSEN_BY_USER, candidate,
                suggestions = (listOfNotNull(prev?.match) + (prev?.suggestions ?: emptyList())).filter { it.ref != candidate.ref },
                userArtwork = prev?.userArtwork,
                lastStatus = prev?.lastStatus, lastMessage = "Chosen by you", attempts = prev?.attempts ?: emptyList(), updatedAtMs = clock())
            store.put(a)
            f.update { it.copy(assignment = a, searchResults = null, artwork = null, artworkSource = null, poster = null, message = null) }
            loadArtwork(key, pin = true)
        }
    }

    /** User prefers the bundled category tile (optionally a corrected category). */
    fun useCategoryArt(key: MediaTransferKey, kind: MediaKind? = null) {
        val f = flow(key) ?: return
        scope.launch(lookupDispatcher) {
            val prev = store.get(key)
            prev?.userArtwork?.let { releaseIfUnreferenced(it, except = key) }
            prev?.let { releaseChosenArt(it) }
            val a = TransferMediaAssignment(key, MediaAssignmentState.CATEGORY_ART_BY_USER, prev?.match,
                prev?.suggestions ?: emptyList(), categoryOverride = kind ?: f.value.recognition.kind, updatedAtMs = clock())
            store.put(a)
            f.update { it.copy(assignment = a, artwork = null, artworkSource = null, poster = null, searchResults = null) }
        }
    }

    /** Reset to automatic: forget this transfer's choice; cached provider data may be reused. */
    fun reset(key: MediaTransferKey) {
        val f = flow(key) ?: return
        scope.launch(lookupDispatcher) {
            val prev = store.get(key)
            prev?.userArtwork?.let { releaseIfUnreferenced(it, except = key) }
            prev?.let { releaseChosenArt(it) }
            store.remove(key)
            f.update { it.copy(assignment = null, artwork = null, artworkSource = null, poster = null, searchResults = null, message = null) }
        }
    }

    /**
     * Use an image the user picked on the device (bytes read by the host with a size bound). Works
     * with online lookup off. The processed copy lives in app storage; the source is never modified.
     */
    fun importUserImage(key: MediaTransferKey, bytes: ByteArray) {
        val f = flow(key) ?: return
        f.update { it.copy(busy = true, message = null) }
        scope.launch(lookupDispatcher) {
            val (candidate, error) = runCatching { artwork.importLocal(bytes) }.getOrElse { null to "Image could not be used" }
            if (candidate == null) { f.update { it.copy(busy = false, message = error) }; return@launch }
            applyImported(key, candidate)
        }
    }

    private fun applyImported(key: MediaTransferKey, candidate: ArtworkCandidate) {
        val f = flow(key) ?: return
        run {
            val prev = store.get(key)
            prev?.userArtwork?.takeIf { it != candidate }?.let { releaseIfUnreferenced(it, except = key) }
            val keptMatch = prev?.state == MediaAssignmentState.AUTOMATIC || prev?.state == MediaAssignmentState.CHOSEN_BY_USER
            val a = (prev ?: TransferMediaAssignment(key, MediaAssignmentState.CHOSEN_BY_USER)).copy(
                state = MediaAssignmentState.CHOSEN_BY_USER, userArtwork = candidate, categoryOverride = null,
                stateBeforeImage = if (prev?.userArtwork != null) prev.stateBeforeImage else prev?.state?.takeIf { keptMatch },
                // Keep a still-confident match visible as metadata alongside the user's image.
                match = prev?.match?.takeIf { prev.state == MediaAssignmentState.AUTOMATIC || prev.state == MediaAssignmentState.CHOSEN_BY_USER },
                lastMessage = "Your image", updatedAtMs = clock())
            store.put(a)
            f.update { it.copy(assignment = a, busy = false, artwork = null, artworkSource = null, poster = null) }
            loadArtwork(key)
        }
    }

    /** Explicit "Use image URL". Never automatic; the URL itself is not stored. */
    fun importImageUrl(key: MediaTransferKey, url: String) {
        val f = flow(key) ?: return
        f.update { it.copy(busy = true, message = null) }
        scope.launch(lookupDispatcher) {
            val (candidate, error) = runCatching { artwork.importFromUrl(url) }.getOrElse { null to "Image could not be used" }
            if (candidate == null) { f.update { it.copy(busy = false, message = error) }; return@launch }
            applyImported(key, candidate)
        }
    }

    fun removeUserImage(key: MediaTransferKey) {
        val f = flow(key) ?: return
        scope.launch(lookupDispatcher) {
            val prev = store.get(key) ?: return@launch
            val image = prev.userArtwork ?: return@launch
            releaseIfUnreferenced(image, except = key)
            val restored = prev.stateBeforeImage ?: if (prev.match == null) MediaAssignmentState.NOT_LOOKED_UP else prev.state
            val a = prev.copy(userArtwork = null, stateBeforeImage = null, state = restored,
                match = prev.match.takeIf { restored == MediaAssignmentState.AUTOMATIC || restored == MediaAssignmentState.CHOSEN_BY_USER },
                updatedAtMs = clock())
            if (a.match == null && a.categoryOverride == null) store.remove(key) else store.put(a)
            f.update { it.copy(assignment = a.takeIf { it.match != null || it.categoryOverride != null }, artwork = null, artworkSource = null, poster = null) }
            if (a.displayMatch != null) loadArtwork(key)
        }
    }

    /** Unpin a user-chosen provider image unless another user choice still uses it. */
    private fun releaseChosenArt(prev: TransferMediaAssignment) {
        if (prev.state != MediaAssignmentState.CHOSEN_BY_USER || prev.stateBeforeImage == MediaAssignmentState.AUTOMATIC) return
        val m = prev.match ?: return
        val shared = store.all().any { it.key != prev.key && it.state == MediaAssignmentState.CHOSEN_BY_USER && it.match?.ref == m.ref }
        if (!shared) m.artwork.forEach { artwork.unpin(it, ArtworkRole.SQUARE) }
    }

    /** Never delete an imported image another transfer still uses. */
    private fun releaseIfUnreferenced(image: ArtworkCandidate, except: MediaTransferKey) {
        if (store.all().none { it.key != except && it.userArtwork == image }) artwork.removeLocal(image)
    }

    fun dismissSearch(key: MediaTransferKey) { flow(key)?.update { it.copy(searchResults = null, message = null) } }

    /** Purge assignments, lookup history and downloaded artwork. Transfers are untouched. */
    fun clearAllMediaData(clearQueryCache: () -> Unit, clearArtwork: () -> Unit): kotlinx.coroutines.Job =
        scope.launch(lookupDispatcher) {
            store.clear(); clearQueryCache(); clearArtwork(); artwork.clearMemory()
            synchronized(this@TransferMediaController) { states.values.toList() }.forEach { f ->
                f.update { it.copy(assignment = null, artwork = null, artworkSource = null, poster = null, searchResults = null, message = null) }
            }
        }

    /**
     * Detail header: load the poster/cover role (original aspect, ≤720 px) for a shown match. Never for
     * user images (square only), adult or when artwork is off; cached and offline-capable like squares.
     */
    fun requestPoster(key: MediaTransferKey) {
        val f = flow(key) ?: return
        val cur = f.value
        val match = cur.match ?: return
        val s = settings()
        if (cur.poster != null || cur.userArtwork != null || !s.showArtwork || cur.displayKind == MediaKind.ADULT) return
        if (match.artwork.none { it.role == ArtworkRole.POSTER }) return
        val net = network()
        val allowNetwork = s.downloadArtwork && net.online && (!net.metered || s.artworkOnMeteredNetwork)
        scope.launch(artworkDispatcher) {
            val posters = match.artwork.filter { it.role == ArtworkRole.POSTER }
            val r = artwork.load(posters, ArtworkRole.POSTER, allowNetwork, service.providerInfos().map { it.id })
            if (r is ArtworkLoadResult.Loaded) f.update { now -> if (now.match?.ref == match.ref && now.userArtwork == null) now.copy(poster = r.bitmap) else now }
        }
    }

    private fun loadArtwork(key: MediaTransferKey, pin: Boolean = false) {
        val f = flow(key) ?: return
        val s = settings()
        val a = f.value.assignment ?: return
        val match = a.displayMatch
        val candidates = listOfNotNull(a.userArtwork) + (match?.artwork ?: emptyList())
        if (!s.showArtwork || candidates.isEmpty() || f.value.recognition.kind == MediaKind.ADULT) return
        val net = network()
        val allowNetwork = s.downloadArtwork && net.online && (!net.metered || s.artworkOnMeteredNetwork)
        scope.launch(artworkDispatcher) {
            val providerOrder = service.providerInfos().map { it.id }
            // The user's own image always wins (explicit pin > provider art); otherwise deterministic order.
            val r = a.userArtwork?.let { artwork.loadOne(it, ArtworkRole.SQUARE, false) }?.takeIf { it is ArtworkLoadResult.Loaded }
                ?: artwork.load(match?.artwork ?: emptyList(), ArtworkRole.SQUARE, allowNetwork, providerOrder)
            when (r) {
                is ArtworkLoadResult.Loaded -> {
                    if (pin && r.source.provider != LOCAL_PROVIDER) artwork.pin(r.source, ArtworkRole.SQUARE)
                    f.update { cur ->
                        val still = cur.assignment?.let { now -> now.userArtwork == a.userArtwork && now.displayMatch?.ref == match?.ref } == true
                        if (still) cur.copy(artwork = r.bitmap, artworkSource = r.source) else cur
                    }
                }
                is ArtworkLoadResult.Unavailable -> {}
            }
        }
    }
}
