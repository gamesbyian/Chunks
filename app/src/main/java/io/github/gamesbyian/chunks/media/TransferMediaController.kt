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
    /** Tags read from this transfer's own completed local audio file, if inspected. */
    val embeddedTags: io.github.gamesbyian.chunks.media.inspection.EmbeddedMediaTags? = null,
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
    private val backgroundDispatcher: CoroutineDispatcher = lookupDispatcher,
    ruleStoreFactory: () -> MediaRuleStore = { InMemoryMediaRuleStore() }
) {
    private val ruleStoreLazy = lazy(ruleStoreFactory)
    private val ruleStore by ruleStoreLazy
    companion object {
        val AUDIO_TAG_EXTENSIONS = setOf("mp3", "flac", "m4a", "m4b", "mp4", "aac")
        const val MAX_STATES = 96
        const val MAX_PENDING_AUTOMATIC = 6
        /** Bounds one Similar Transfers index build (visible inventory, all environments). */
        const val MAX_SIMILAR_INVENTORY = 5_000
    }
    private val pendingAutomatic = java.util.concurrent.atomic.AtomicInteger(0)

    private val assignmentRevisionFlow = MutableStateFlow(0L)
    /** Increments after every stored assignment change; hosts use it to refresh derived views (search). */
    val assignmentRevision: StateFlow<Long> get() = assignmentRevisionFlow
    private val store by lazy {
        val inner = storeFactory()
        object : TransferMediaAssignmentStore by inner {
            override fun put(a: TransferMediaAssignment) { inner.put(a); assignmentRevisionFlow.update { it + 1 } }
            override fun putAll(list: Collection<TransferMediaAssignment>) { inner.putAll(list); assignmentRevisionFlow.update { it + 1 } }
            override fun remove(key: MediaTransferKey) { inner.remove(key); assignmentRevisionFlow.update { it + 1 } }
            override fun clear() { inner.clear(); assignmentRevisionFlow.update { it + 1 } }
        }
    }

    /**
     * Confirmed (automatic or user-chosen) match titles for list search, keyed by exact transfer.
     * Local only; suggestions, category choices and adult content never become searchable text.
     * Call off the main thread.
     */
    fun searchableTitles(): Map<MediaTransferKey, String> = store.all().mapNotNull { a ->
        val m = a.displayMatch ?: return@mapNotNull null
        if (m.kind == MediaKind.ADULT) return@mapNotNull null
        a.key to listOfNotNull(m.title, m.episodeTitle).joinToString(" ")
    }.toMap()
    private val states = object : LinkedHashMap<MediaTransferKey, MutableStateFlow<TransferMediaUiState>>(64, 0.75f, true) {
        // Each state may reference a ~0.6 MB bitmap: keep only recently observed rows (visible + margin).
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaTransferKey, MutableStateFlow<TransferMediaUiState>>): Boolean {
            val evict = size > MAX_STATES
            if (evict) inputs.remove(eldest.key)
            return evict
        }
    }
    private val inputs = HashMap<MediaTransferKey, MediaRecognitionInput>()
    private val embedded = HashMap<MediaTransferKey, io.github.gamesbyian.chunks.media.inspection.EmbeddedMediaTags>()

    private fun recognizeWithEvidence(key: MediaTransferKey, input: MediaRecognitionInput): MediaRecognition {
        val base = MediaRecognizer.recognize(input)
        return embedded[key]?.let { MediaRecognizer.withEmbeddedTags(base, it) } ?: base
    }

    fun providerInfos() = service.providerInfos()

    /** Pure, synchronous and cheap: safe to call during composition via remember. */
    fun recognize(name: String, files: List<MediaFileHint> = emptyList()) = MediaRecognizer.recognize(name, files)

    @Synchronized
    fun observe(key: MediaTransferKey, name: String, files: List<MediaFileHint> = emptyList()): StateFlow<TransferMediaUiState> {
        val input = MediaRecognitionInput(name, files)
        val existing = states[key]
        if (existing != null && inputs[key] == input) return existing
        inputs[key] = input
        val flow = existing ?: MutableStateFlow(TransferMediaUiState(key, recognizeWithEvidence(key, input), embeddedTags = embedded[key]))
            .also { states[key] = it }
        if (existing != null) flow.update { it.copy(recognition = recognizeWithEvidence(key, input)) }
        scope.launch(lookupDispatcher) {
            settings().let { largeListArtworkFlow.value = it.largeListArtwork; presentationFlow.value = it }
            val a = store.get(key) ?: applyRuleIfAny(key, flow.value.recognition)
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
            val sameMatch = prev?.match?.ref == candidate.ref
            prev?.takeIf { !sameMatch }?.let { releaseChosenArt(it); releasePoster(it) }
            val a = TransferMediaAssignment(key, MediaAssignmentState.CHOSEN_BY_USER, candidate,
                suggestions = (listOfNotNull(prev?.match) + (prev?.suggestions ?: emptyList())).filter { it.ref != candidate.ref },
                userArtwork = prev?.userArtwork, userPosterUrl = prev?.userPosterUrl?.takeIf { sameMatch },
                lastStatus = prev?.lastStatus, lastMessage = "Chosen by you", attempts = prev?.attempts ?: emptyList(), updatedAtMs = clock())
            store.put(a)
            f.update { it.copy(assignment = a, searchResults = null, artwork = null, artworkSource = null, poster = null, message = null) }
            loadArtwork(key, pin = true)
        }
    }

    /** A transfer that could share the user's choice, shown in a preview before anything is applied. */
    data class RelatedCandidate(val key: MediaTransferKey, val originalName: String, val season: Int?, val episode: Int?)

    /**
     * Preview for "Apply to related transfers": currently observed transfers whose *recognized* title
     * (comparison form) and media family equal this one's, excluding ones the user already decided.
     * A preview only; nothing changes until [applyChoiceTo] is called with the user's selection.
     */
    @Synchronized
    fun relatedForChoice(key: MediaTransferKey): List<RelatedCandidate> {
        val source = states[key]?.value ?: return emptyList()
        val title = source.recognition.title?.let { MediaText.withoutArticle(MediaText.normalize(it)) } ?: return emptyList()
        if (source.recognition.kind == MediaKind.ADULT) return emptyList()
        return states.values.map { it.value }.filter { s ->
            s.key != key && s.recognition.kind != MediaKind.ADULT &&
                s.recognition.kind.compatibleWith(source.recognition.kind) && s.recognition.kind != MediaKind.UNKNOWN &&
                s.recognition.title?.let { MediaText.withoutArticle(MediaText.normalize(it)) } == title &&
                (source.recognition.year == null || s.recognition.year == null || s.recognition.year == source.recognition.year) &&
                s.state != MediaAssignmentState.CHOSEN_BY_USER && s.state != MediaAssignmentState.CATEGORY_ART_BY_USER
        }.map { RelatedCandidate(it.key, it.recognition.raw, it.recognition.season, it.recognition.episode) }
            .sortedWith(compareBy({ it.season ?: -1 }, { it.episode ?: -1 }, { it.originalName }))
    }

    /**
     * Apply the source transfer's chosen match to the user-confirmed [targets]. TV episodes get the
     * series identity plus their own recognized season/episode (no per-episode network calls); the
     * artwork is the shared series/album/work art. Presentation only, reversible per transfer.
     */
    fun applyChoiceTo(source: MediaTransferKey, targets: Collection<MediaTransferKey>) {
        scope.launch(lookupDispatcher) {
            // Only a match the user actually chose spreads; an automatic match under a user image does not.
            val chosen = store.get(source)?.takeIf { it.state == MediaAssignmentState.CHOSEN_BY_USER &&
                it.stateBeforeImage != MediaAssignmentState.AUTOMATIC }?.match ?: return@launch
            val series = chosen.copy(episodeTitle = null, overview = if (chosen.kind == MediaKind.TV_EPISODE) null else chosen.overview,
                artwork = chosen.artwork.filter { it.role != ArtworkRole.BANNER })
            val batch = mutableListOf<Pair<MutableStateFlow<TransferMediaUiState>, TransferMediaAssignment>>()
            for (t in targets.distinct()) {
                if (t == source) continue
                val f = flow(t) ?: continue
                val prev = store.get(t)
                if (prev?.state == MediaAssignmentState.CHOSEN_BY_USER || prev?.state == MediaAssignmentState.CATEGORY_ART_BY_USER) continue
                val r = f.value.recognition
                val c = if (chosen.kind.isTv) series.copy(kind = if (r.episode != null) MediaKind.TV_EPISODE else if (r.season != null) MediaKind.TV_SEASON else MediaKind.TV_SERIES,
                    season = r.season, episode = r.episode,
                    // Season-specific posters only ever go to transfers of that same season.
                    artwork = series.artwork.filter { a -> a.season == null || a.season == r.season }) else series
                val a = TransferMediaAssignment(t, MediaAssignmentState.CHOSEN_BY_USER, c, lastMessage = "Applied from a related transfer",
                    updatedAtMs = clock())
                batch.add(f to a)
            }
            store.putAll(batch.map { it.second })
            for ((f, a) in batch) {
                f.update { it.copy(assignment = a, artwork = null, artworkSource = null, poster = null) }
                loadArtwork(a.key, pin = true)
            }
        }
    }

    /** A saved rule fills only transfers without any assignment; no network, reversible per transfer. */
    private fun applyRuleIfAny(key: MediaTransferKey, r: MediaRecognition): TransferMediaAssignment? {
        val rule = ruleStore.all().lastOrNull { it.matches(r) } ?: return null
        val base = rule.candidate
        val c = if (base.kind.isTv) base.copy(kind = if (r.episode != null) MediaKind.TV_EPISODE else if (r.season != null) MediaKind.TV_SEASON else MediaKind.TV_SERIES,
            season = r.season, episode = r.episode, episodeTitle = null,
            artwork = base.artwork.filter { a -> a.season == null || a.season == r.season }) else base
        val a = TransferMediaAssignment(key, MediaAssignmentState.CHOSEN_BY_USER, c, lastMessage = "Applied by your rule", updatedAtMs = clock())
        store.put(a)
        return a
    }

    /** Save "use this for future transfers with the same name" from a genuine user choice. */
    fun saveRuleFrom(source: MediaTransferKey) {
        val f = flow(source) ?: return
        scope.launch(lookupDispatcher) {
            val a = store.get(source)?.takeIf { it.state == MediaAssignmentState.CHOSEN_BY_USER && it.stateBeforeImage != MediaAssignmentState.AUTOMATIC } ?: return@launch
            val m = a.match ?: return@launch
            val r = f.value.recognition
            val title = r.title?.let { MediaText.withoutArticle(MediaText.normalize(it)) }?.takeIf { it.isNotEmpty() } ?: return@launch
            if (r.kind == MediaKind.ADULT || r.kind == MediaKind.UNKNOWN) return@launch
            val family = MediaAssignmentRule.familyOf(r.kind)
            val series = m.copy(episodeTitle = null, episode = null, artwork = m.artwork.filter { it.role != ArtworkRole.BANNER && it.season == null })
            ruleStore.put(MediaAssignmentRule("$family|$title|${r.year ?: ""}", title, family, r.year, series, clock()))
        }
    }

    fun rules(): List<MediaAssignmentRule> = ruleStore.all()
    fun removeRule(id: String) { scope.launch(lookupDispatcher) { ruleStore.remove(id) } }
    /** Composition-safe: answers only once the rule file was opened on the background dispatcher. */
    fun ruleFor(key: MediaTransferKey): MediaAssignmentRule? {
        if (!ruleStoreLazy.isInitialized()) return null
        return flow(key)?.value?.recognition?.let { r -> ruleStore.all().lastOrNull { it.matches(r) } }
    }

    /** Title-free counts from the live store; call off the main thread. */
    fun diagnostics(): MediaDiagnostics = MediaDiagnostics.of(store.all())

    /** User prefers the bundled category tile (optionally a corrected category). */
    fun useCategoryArt(key: MediaTransferKey, kind: MediaKind? = null) {
        val f = flow(key) ?: return
        scope.launch(lookupDispatcher) {
            val prev = store.get(key)
            prev?.userArtwork?.let { releaseIfUnreferenced(it, except = key) }
            prev?.let { releaseChosenArt(it); releasePoster(it) }
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
            prev?.let { releaseChosenArt(it); releasePoster(it) }
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

    /**
     * Read embedded tags/cover from the first audio file of a *completed local* transfer (host opens
     * the bounded byte source). Refines recognition for later lookups; never changes files.
     */
    fun inspectLocalAudio(key: MediaTransferKey, files: List<MediaFileHint>, open: (MediaFileHint) -> io.github.gamesbyian.chunks.media.inspection.MediaByteSource?) {
        val f = flow(key) ?: return
        val candidate = files.filter { it.path.substringAfterLast('.').lowercase() in AUDIO_TAG_EXTENSIONS }
            .minWithOrNull(compareBy<MediaFileHint> { it.path.count { c -> c == '/' } }.thenBy { it.path }) ?: return
        scope.launch(lookupDispatcher) {
            val tags = runCatching { open(candidate)?.let { io.github.gamesbyian.chunks.media.inspection.EmbeddedTagReader.read(it) } }.getOrNull()
                ?: return@launch
            synchronized(this@TransferMediaController) { embedded[key] = tags }
            val input = synchronized(this@TransferMediaController) { inputs[key] } ?: return@launch
            f.update { it.copy(recognition = recognizeWithEvidence(key, input), embeddedTags = tags) }
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

    /** Unpin a chosen detail poster unless another transfer's choice still uses the same image. */
    private fun releasePoster(prev: TransferMediaAssignment) {
        val url = prev.userPosterUrl ?: return
        val c = prev.match?.artwork?.firstOrNull { it.role == ArtworkRole.POSTER && it.url == url } ?: return
        if (store.all().none { it.key != prev.key && it.userPosterUrl == url }) artwork.unpin(c, ArtworkRole.POSTER)
    }

    /** Never delete an imported image another transfer still uses. */
    private fun releaseIfUnreferenced(image: ArtworkCandidate, except: MediaTransferKey) {
        if (store.all().none { it.key != except && it.userArtwork == image }) {
            if (image.provider == LOCAL_PROVIDER) artwork.removeLocal(image) else artwork.unpin(image, ArtworkRole.SQUARE)
        }
    }

    /** One loaded alternative image for the current match (square role), for the user to choose from. */
    data class ArtworkAlternative(val candidate: ArtworkCandidate, val bitmap: Bitmap)

    /**
     * Explicitly requested gallery of the current match's other square-role images. Downloads only
     * with "Download artwork" on and an allowed network; otherwise shows what is already cached.
     * Never for adult content, and never changes the assignment by itself.
     */
    suspend fun alternatives(key: MediaTransferKey, limit: Int = 8): List<ArtworkAlternative> =
        kotlinx.coroutines.withContext(artworkDispatcher) {
            val cur = flow(key)?.value ?: return@withContext emptyList()
            val match = cur.match ?: return@withContext emptyList()
            val s = settings()
            if (!s.showArtwork || cur.displayKind == MediaKind.ADULT) return@withContext emptyList()
            val net = network()
            val allowNetwork = s.downloadArtwork && net.online && (!net.metered || s.artworkOnMeteredNetwork)
            match.artwork.filter { it.season == null || it.season == match.season }.distinctBy { it.url }.take(limit.coerceIn(1, 24))
                .mapNotNull { c -> (artwork.loadOne(c, ArtworkRole.SQUARE, allowNetwork) as? ArtworkLoadResult.Loaded)?.let { ArtworkAlternative(c, it.bitmap) } }
        }

    /** The user picked one provider image: pin it and use it ahead of automatic selection. */
    fun chooseAlternative(key: MediaTransferKey, candidate: ArtworkCandidate) {
        val cur = flow(key)?.value ?: return
        if (cur.match?.artwork?.contains(candidate) != true) return
        scope.launch(lookupDispatcher) {
            artwork.pin(candidate, ArtworkRole.SQUARE)
            applyImported(key, candidate)
        }
    }

    /**
     * Explicitly requested gallery of the current match's poster/cover-role images (original aspect),
     * for choosing the detail header image. Same consent, network and adult rules as [alternatives].
     */
    suspend fun posterAlternatives(key: MediaTransferKey, limit: Int = 8): List<ArtworkAlternative> =
        kotlinx.coroutines.withContext(artworkDispatcher) {
            val cur = flow(key)?.value ?: return@withContext emptyList()
            val match = cur.match ?: return@withContext emptyList()
            val s = settings()
            if (!s.showArtwork || cur.displayKind == MediaKind.ADULT || cur.userArtwork != null) return@withContext emptyList()
            val net = network()
            val allowNetwork = s.downloadArtwork && net.online && (!net.metered || s.artworkOnMeteredNetwork)
            posterCandidates(match).distinctBy { it.url }.take(limit.coerceIn(1, 24))
                .mapNotNull { c -> (artwork.loadOne(c, ArtworkRole.POSTER, allowNetwork) as? ArtworkLoadResult.Loaded)?.let { ArtworkAlternative(c, it.bitmap) } }
        }

    private fun posterCandidates(match: MediaCandidate) =
        match.artwork.filter { it.role == ArtworkRole.POSTER && (it.season == null || it.season == match.season) }

    /**
     * The user picked the detail poster from the current match's own poster images: pin it and
     * remember it for this transfer only. List tiles are unchanged; the match is not changed.
     */
    fun choosePoster(key: MediaTransferKey, candidate: ArtworkCandidate) {
        val f = flow(key) ?: return
        val cur = f.value
        val match = cur.match ?: return
        if (candidate !in posterCandidates(match) || cur.displayKind == MediaKind.ADULT) return
        scope.launch(lookupDispatcher) {
            val prev = store.get(key)?.takeIf { it.displayMatch?.ref == match.ref } ?: return@launch
            if (prev.userPosterUrl == candidate.url) return@launch
            artwork.pin(candidate, ArtworkRole.POSTER)
            releasePoster(prev)
            val a = prev.copy(userPosterUrl = candidate.url, updatedAtMs = clock())
            store.put(a)
            f.update { it.copy(assignment = a, poster = null) }
            requestPoster(key)
        }
    }

    /**
     * Similar Transfers over the caller's already-authorized, visible inventory. Only confirmed
     * (automatic or user-chosen) identities count; title-only or suggested matches never join.
     * Reads local assignments on the lookup dispatcher; never starts a lookup.
     */
    suspend fun similarFor(source: io.github.gamesbyian.chunks.model.UnifiedTransferKey,
                           visible: Collection<io.github.gamesbyian.chunks.model.UnifiedTransferKey>,
                           limit: Int = 20, sameEnvironmentOnly: Boolean = false): List<io.github.gamesbyian.chunks.model.RelatedTransferCandidate> =
        kotlinx.coroutines.withContext(lookupDispatcher) {
            val keys = (listOf(source) + visible).distinct().take(MAX_SIMILAR_INVENTORY)
            val index = io.github.gamesbyian.chunks.model.MediaRelationIndex.build(
                MediaRelationBridge.members(keys.map { it to store.get(MediaTransferKey.of(it)) }))
            index.similar(source, keys.toSet(), sameEnvironmentOnly = sameEnvironmentOnly, limit = limit.coerceIn(1, 100))
        }

    /**
     * Apply changed presentation settings to rows already on screen: turning artwork off drops
     * loaded images immediately (category art shows instead); turning it on reloads only from
     * existing assignments. Never starts an identification lookup.
     */
    private val largeListArtworkFlow = MutableStateFlow(false)
    /** Presentation preference for list rows; refreshed from settings on first observe and on change. */
    val largeListArtwork: StateFlow<Boolean> get() = largeListArtworkFlow
    private val presentationFlow = MutableStateFlow(MediaPrivacySettings())
    /** The latest loaded media settings, for presentation-only host decisions (e.g. Similar transfers). */
    val presentation: StateFlow<MediaPrivacySettings> get() = presentationFlow

    fun onSettingsChanged() {
        val snapshot = synchronized(this) { states.entries.map { it.key to it.value } }
        scope.launch(artworkDispatcher) {
            val s = settings()
            largeListArtworkFlow.value = s.largeListArtwork
            presentationFlow.value = s
            snapshot.forEach { (key, f) ->
                if (!s.showArtwork) f.update { it.copy(artwork = null, artworkSource = null, poster = null) }
                else if (f.value.artwork == null && f.value.assignment != null) loadArtwork(key)
            }
        }
    }

    fun dismissSearch(key: MediaTransferKey) { flow(key)?.update { it.copy(searchResults = null, message = null) } }

    /** Purge assignments, lookup history and downloaded artwork. Transfers are untouched. */
    fun clearAllMediaData(clearQueryCache: () -> Unit, clearArtwork: () -> Unit): kotlinx.coroutines.Job =
        scope.launch(lookupDispatcher) {
            store.clear(); ruleStore.clear(); clearQueryCache(); clearArtwork(); artwork.clearMemory()
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
        if (cur.poster != null || cur.userArtwork != null || cur.displayKind == MediaKind.ADULT) return
        if (match.artwork.none { it.role == ArtworkRole.POSTER }) return
        // Settings/network reads may touch disk: never on the caller's (main) thread.
        scope.launch(artworkDispatcher) {
            val s = settings()
            if (!s.showArtwork) return@launch
            val net = network()
            val allowNetwork = s.downloadArtwork && net.online && (!net.metered || s.artworkOnMeteredNetwork)
            val posters = posterCandidates(match)
            // The user's chosen poster first (pinned, so offline-capable); otherwise deterministic order.
            val chosen = cur.assignment?.userPosterUrl?.let { u -> posters.firstOrNull { it.url == u } }
            val r = chosen?.let { artwork.loadOne(it, ArtworkRole.POSTER, allowNetwork) }?.takeIf { it is ArtworkLoadResult.Loaded }
                ?: artwork.load(posters, ArtworkRole.POSTER, allowNetwork, service.providerInfos().map { it.id })
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
