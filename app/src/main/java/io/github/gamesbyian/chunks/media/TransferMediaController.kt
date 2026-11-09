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
import kotlinx.coroutines.ensureActive

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
    val message: String? = null,
    val automaticLookupEnabled: Boolean = true
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
 * Background requests require current observer/inventory authority, provider consent and the
 * exact transfer's automation preference. Explicit corrections retain their own scoped request path.
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
    ruleStoreFactory: () -> MediaRuleStore = { InMemoryMediaRuleStore() },
    automationStoreFactory: () -> MediaAutomationPreferences = { InMemoryMediaAutomationPreferences() }
) {
    private val automationStore by lazy(automationStoreFactory)
    private val observationAuthorization = java.util.concurrent.ConcurrentHashMap<MediaTransferKey,
        java.util.concurrent.CopyOnWriteArraySet<() -> Boolean>>()
    private val assignmentLock = Any()
    private val pendingAutomatic = HashSet<MediaTransferKey>()
    private val ruleStoreLazy = lazy(ruleStoreFactory)
    private val ruleStore by ruleStoreLazy
    companion object {
        val AUDIO_TAG_EXTENSIONS = setOf("mp3", "flac", "m4a", "m4b", "mp4", "aac")
        const val MAX_STATES = 96
        const val MAX_PENDING_AUTOMATIC = 6
        /** Bounds one Similar Transfers index build (visible inventory, all environments). */
        const val MAX_SIMILAR_INVENTORY = 5_000
    }
    // Suspend instead of dropping the seventh and later eligible transfer. Background
    // lookup runs on its own dispatcher; explicit Identify stays on the foreground lane.
    private val automaticSlots = kotlinx.coroutines.sync.Semaphore(MAX_PENDING_AUTOMATIC)

    private val assignmentRevisionFlow = MutableStateFlow(0L)
    /** Increments after every stored assignment change; hosts use it to refresh derived views (search). */
    val assignmentRevision: StateFlow<Long> get() = assignmentRevisionFlow
    private val store by lazy {
        val inner = storeFactory()
        object : TransferMediaAssignmentStore by inner {
            override fun get(key: MediaTransferKey) = synchronized(assignmentLock) { inner.get(key) }
            override fun all() = synchronized(assignmentLock) { inner.all() }
            override fun put(a: TransferMediaAssignment) = synchronized(assignmentLock) {
                inner.put(a); assignmentRevisionFlow.update { it + 1 }
            }
            override fun putAll(list: Collection<TransferMediaAssignment>) = synchronized(assignmentLock) {
                inner.putAll(list); assignmentRevisionFlow.update { it + 1 }
            }
            override fun remove(key: MediaTransferKey) = synchronized(assignmentLock) {
                inner.remove(key); assignmentRevisionFlow.update { it + 1 }
            }
            override fun clear() = synchronized(assignmentLock) {
                inner.clear(); assignmentRevisionFlow.update { it + 1 }
            }
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
            if (evict) { inputs.remove(eldest.key); observationAuthorization.remove(eldest.key) }
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
    fun observe(key: MediaTransferKey, name: String, files: List<MediaFileHint> = emptyList(),
                isAuthorized: () -> Boolean = { true }): StateFlow<TransferMediaUiState> {
        observationAuthorization.computeIfAbsent(key) { java.util.concurrent.CopyOnWriteArraySet() }.add(isAuthorized)
        val input = MediaRecognitionInput(name, files)
        val existing = states[key]
        if (existing != null && inputs[key] == input) return existing
        inputs[key] = input
        val flow = existing ?: MutableStateFlow(TransferMediaUiState(key, recognizeWithEvidence(key, input), embeddedTags = embedded[key]))
            .also { states[key] = it }
        if (existing != null) flow.update { it.copy(recognition = recognizeWithEvidence(key, input)) }
        scope.launch(lookupDispatcher) {
            settings().let { largeListArtworkFlow.value = it.largeListArtwork; presentationFlow.value = it }
            val enabled = !automationStore.isExcluded(key)
            val a = store.get(key) ?: if (enabled) applyRuleIfAny(key, flow.value.recognition) else null
            flow.update { it.copy(assignment = store.get(key), automaticLookupEnabled = enabled) }
            if (a?.displayMatch != null || a?.userArtwork != null) loadArtwork(key)
            if (automaticDue(a)) maybeAutomatic(key)
        }
        return flow
    }

    private fun flow(key: MediaTransferKey) = synchronized(this) { states[key] }

    private fun authorization(key: MediaTransferKey): () -> Boolean {
        return { observationAuthorization[key]?.any { it() } == true }
    }

    fun stopObserving(key: MediaTransferKey, isAuthorized: () -> Boolean) {
        observationAuthorization[key]?.remove(isAuthorized)
    }

    /** Call off main; persistence is separate from assignment/cache cleanup and manual corrections. */
    fun prepareForAdd(key: io.github.gamesbyian.chunks.model.UnifiedTransferKey,
                      allowAutomaticLookup: Boolean): Boolean =
        // The default permits eligibility; it never withdraws an existing
        // explicit exclusion or grants global provider consent. Persist an
        // opt-out before any engine admission can expose the job to discovery.
        allowAutomaticLookup || setAutomaticLookupEnabled(MediaTransferKey.of(key), false)

    /** Call off main; persistence is separate from assignment/cache cleanup and manual corrections. */
    fun setAutomaticLookupEnabled(key: MediaTransferKey, enabled: Boolean): Boolean = try {
        synchronized(assignmentLock) { automationStore.setExcluded(key, !enabled) }
        flow(key)?.update { it.copy(automaticLookupEnabled = enabled) }
        if (enabled) refreshAutomatic(key)
        true
    } catch (_: Exception) {
        flow(key)?.update { it.copy(automaticLookupEnabled = false,
            message = "Could not save the automatic lookup preference. Background lookup is paused.") }
        false
    }

    /** A committed visible observer can re-check eligibility without rescanning cached hidden rows. */
    fun refreshAutomatic(key: MediaTransferKey) {
        scope.launch(lookupDispatcher) {
            val f = flow(key) ?: return@launch
            val enabled = !automationStore.isExcluded(key)
            val a = store.get(key) ?: if (enabled) applyRuleIfAny(key, f.value.recognition) else null
            f.update { it.copy(assignment = store.get(key), automaticLookupEnabled = enabled) }
            if (a?.displayMatch != null || a?.userArtwork != null) loadArtwork(key)
            if (automaticDue(a)) maybeAutomatic(key)
        }
    }

    private fun automaticDue(assignment: TransferMediaAssignment?): Boolean {
        if (assignment == null) return true
        if (assignment.state != MediaAssignmentState.COULDNT_IDENTIFY || assignment.lastStatus != MediaLookupStatus.NOT_FOUND) return false
        val now = clock()
        return now >= assignment.updatedAtMs && now - assignment.updatedAtMs >= MediaEnrichmentService.NOT_FOUND_TTL_MS
    }

    private fun automaticAllowed(key: MediaTransferKey, authorized: () -> Boolean): Boolean =
        authorized() && settings().let { it.onlineLookupConsent && it.automaticLookup && it.showArtwork && it.downloadArtwork } &&
            !automationStore.isExcluded(key)

    private fun maybeAutomatic(key: MediaTransferKey) {
        val authorized = authorization(key)
        // Preference reads stay on the background lane, before queue admission/provider dispatch.
        scope.launch(backgroundDispatcher) {
            val recognition = flow(key)?.value?.recognition ?: return@launch
            try { enrichAutomatic(key, recognition, authorized) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { /* A later authorized refresh uses the provider retry policy. */ }
        }
    }

    private suspend fun enrichAutomatic(key: MediaTransferKey, recognition: MediaRecognition, authorized: () -> Boolean) {
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val permitted = { job?.isActive != false && automaticAllowed(key, authorized) }
        if (!permitted()) return
        if (!synchronized(this) { pendingAutomatic.add(key) }) return
        try {
            automaticSlots.acquire()
            try {
                if (!permitted()) return
                val previous = store.get(key)
                if (!automaticDue(previous)) return
                // Offscreen inventory and composed rows use the same saved-rule precedence.
                val inherited = applyRuleIfAny(key, recognition, previous, permitted)
                if (inherited != null) {
                    flow(key)?.update { cur -> if (store.get(key) == inherited) cur.copy(assignment = inherited) else cur }
                    if (flow(key) != null) loadArtwork(key)
                    return
                }
                val preferences = settings()
                val outcome = service.lookup(recognition, preferences, network(),
                    MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT, requestAllowed = permitted)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                // A user edit, exclusion, withdrawn consent or closed privacy scope wins over HTTP.
                if (!permitted() || store.get(key) != previous) return
                val state = when (outcome.status) {
                    MediaLookupStatus.MATCHED -> MediaAssignmentState.AUTOMATIC
                    MediaLookupStatus.NEEDS_REVIEW -> MediaAssignmentState.SUGGESTED
                    MediaLookupStatus.NOT_FOUND -> MediaAssignmentState.COULDNT_IDENTIFY
                    else -> return
                }
                val assignment = TransferMediaAssignment(key, state, match = outcome.match,
                    suggestions = outcome.alternates, lastStatus = outcome.status,
                    lastMessage = outcome.message, attempts = outcome.attempts, updatedAtMs = clock())
                if (!replaceIfUnchanged(key, previous, assignment, permitted)) return
                flow(key)?.update { cur ->
                    if (store.get(key) == assignment) cur.copy(assignment = assignment, busy = false) else cur
                }
                val match = assignment.displayMatch ?: return
                if (flow(key) != null) loadArtwork(key)
                else if (permitted() && preferences.showArtwork && preferences.downloadArtwork && recognition.kind != MediaKind.ADULT) {
                    val net = network()
                    if (net.online && (!net.metered || preferences.artworkOnMeteredNetwork)) {
                        kotlinx.coroutines.withContext(artworkDispatcher) {
                            if (permitted()) artwork.load(match.artwork, ArtworkRole.SQUARE, true,
                                service.providerInfos().map { it.id },
                                requestAllowed = { permitted() && artworkNetworkAllowed(key) { true } })
                        }
                    }
                }
            } finally { automaticSlots.release() }
        } finally { synchronized(this) { pendingAutomatic.remove(key) } }
    }

    /** Compare and commit under the same lock as every deliberate edit and exclusion change. */
    private fun replaceIfUnchanged(key: MediaTransferKey, expected: TransferMediaAssignment?,
                                   next: TransferMediaAssignment, allowed: () -> Boolean): Boolean =
        synchronized(assignmentLock) {
            if (!allowed() || store.get(key) != expected) false
            else { store.put(next); true }
        }

    /** Fresh unlocked inventory only; cancellation ends authorization, including between HTTP requests. */
    suspend fun enrichInventory(entries: Collection<Pair<MediaTransferKey, String>>) {
        kotlinx.coroutines.withContext(backgroundDispatcher) {
            for ((key, name) in entries.distinctBy { it.first }) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                try { enrichAutomatic(key, MediaRecognizer.recognize(name)) { true } }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { /* Retry through the provider cache/backoff on a later inventory. */ }
            }
        }
    }

    /** Explicit user request: "Look up online". */
    fun lookup(key: MediaTransferKey) {
        flow(key)?.update { it.copy(busy = true, message = null) }
        scope.launch(lookupDispatcher) { runLookup(key, MediaLookupOrigin.USER_REQUEST) }
    }

    private suspend fun runLookup(key: MediaTransferKey, origin: MediaLookupOrigin) {
        val f = flow(key) ?: return
        val recognition = f.value.recognition
        val existing = store.get(key)
        // A user choice always wins over automatic results; never overwrite it from a lookup.
        if (existing?.state == MediaAssignmentState.CHOSEN_BY_USER || existing?.state == MediaAssignmentState.CATEGORY_ART_BY_USER) {
            f.update { it.copy(busy = false, assignment = existing,
                message = if (origin == MediaLookupOrigin.USER_REQUEST) "Your choice is kept. Use Reset to automatic to look up again, or search below." else it.message) }
            return
        }
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val allowed = { job?.isActive != false && authorization(key)() && settings().onlineLookupConsent }
        val result = runCatching { service.lookup(recognition, settings(), network(), origin, requestAllowed = allowed) }.getOrElse {
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
        if (persist && !replaceIfUnchanged(key, existing, assignment, allowed)) {
            f.update { it.copy(busy = false, assignment = store.get(key) ?: it.assignment) }
            return
        }
        // Background attempts that were simply not allowed leave no trace in the UI.
        val quiet = origin == MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT && !persist
        f.update { it.copy(busy = false, assignment = if (persist) store.get(key) else it.assignment ?: if (quiet) null else assignment,
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
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val permitted = { job?.isActive != false && authorization(key)() && settings().onlineLookupConsent }
            val r = runCatching { service.lookup(q, settings(), network(), MediaLookupOrigin.USER_REQUEST, requestAllowed = permitted) }
                .getOrElse { MediaLookupResult(MediaLookupStatus.ERROR_RETRYABLE, message = "Search failed; try again") }
            if (!permitted()) {
                f.update { it.copy(busy = false, searchResults = null) }
                return@launch
            }
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
    private fun applyRuleIfAny(key: MediaTransferKey, r: MediaRecognition,
                               expected: TransferMediaAssignment? = null,
                               authorized: () -> Boolean = authorization(key)): TransferMediaAssignment? {
        val rule = ruleStore.all().lastOrNull { it.matches(r) } ?: return null
        val base = rule.candidate
        val c = if (base.kind.isTv) base.copy(kind = if (r.episode != null) MediaKind.TV_EPISODE else if (r.season != null) MediaKind.TV_SEASON else MediaKind.TV_SERIES,
            season = r.season, episode = r.episode, episodeTitle = null,
            artwork = base.artwork.filter { a -> a.season == null || a.season == r.season }) else base
        val a = TransferMediaAssignment(key, MediaAssignmentState.CHOSEN_BY_USER, c, lastMessage = "Applied by your rule", updatedAtMs = clock())
        val allowed = { authorized() && !automationStore.isExcluded(key) }
        return if (replaceIfUnchanged(key, expected, a, allowed)) a else store.get(key)
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
            // The selected provider's year identifies a specific movie. An
            // absent year or contradictory source year cannot seed a global
            // rule for unrelated remakes, though exact-job choice remains.
            if (family == "movie" &&
                (m.year == null || (r.year != null && r.year != m.year))) return@launch
            val ruleYear = if (family == "movie") m.year else r.year
            val series = m.copy(episodeTitle = null, episode = null, artwork = m.artwork.filter { it.role != ArtworkRole.BANNER && it.season == null })
            ruleStore.put(MediaAssignmentRule("$family|$title|${ruleYear ?: ""}", title, family, ruleYear, series, clock()))
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
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val permitted = { job?.isActive != false && artworkNetworkAllowed(key, explicitRequest = true) }
            match.artwork.filter { it.season == null || it.season == match.season }.distinctBy { it.url }.take(limit.coerceIn(1, 24))
                .mapNotNull { c -> (artwork.loadOne(c, ArtworkRole.SQUARE, true, permitted) as? ArtworkLoadResult.Loaded)?.let { ArtworkAlternative(c, it.bitmap) } }
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
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val permitted = { job?.isActive != false && artworkNetworkAllowed(key, explicitRequest = true) }
            posterCandidates(match).distinctBy { it.url }.take(limit.coerceIn(1, 24))
                .mapNotNull { c -> (artwork.loadOne(c, ArtworkRole.POSTER, true, permitted) as? ArtworkLoadResult.Loaded)?.let { ArtworkAlternative(c, it.bitmap) } }
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
     * loaded images immediately (category art shows instead). Current authorized observers can
     * refresh eligible identification; cached hidden observations never authorize a request.
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
                if (authorization(key)() && s.onlineLookupConsent && s.automaticLookup && automaticDue(f.value.assignment)) maybeAutomatic(key)
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
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val permitted = { job?.isActive != false && artworkNetworkAllowed(key) }
            val posters = posterCandidates(match)
            // The user's chosen poster first (pinned, so offline-capable); otherwise deterministic order.
            val chosen = cur.assignment?.userPosterUrl?.let { u -> posters.firstOrNull { it.url == u } }
            val r = chosen?.let { artwork.loadOne(it, ArtworkRole.POSTER, true, permitted) }?.takeIf { it is ArtworkLoadResult.Loaded }
                ?: artwork.load(posters, ArtworkRole.POSTER, true, service.providerInfos().map { it.id }, permitted)
            if (r is ArtworkLoadResult.Loaded) f.update { now -> if (now.match?.ref == match.ref && now.userArtwork == null) now.copy(poster = r.bitmap) else now }
        }
    }

    private fun artworkNetworkAllowed(key: MediaTransferKey, explicitRequest: Boolean = false,
                                      authorized: () -> Boolean = authorization(key)): Boolean {
        if (!authorized() || (!explicitRequest && automationStore.isExcluded(key))) return false
        val s = settings()
        val net = network()
        return s.onlineLookupConsent && s.showArtwork && s.downloadArtwork && net.online &&
            (!net.metered || s.artworkOnMeteredNetwork)
    }

    private fun loadArtwork(key: MediaTransferKey, pin: Boolean = false) {
        val f = flow(key) ?: return
        val s = settings()
        val a = f.value.assignment ?: return
        val match = a.displayMatch
        val candidates = listOfNotNull(a.userArtwork) + (match?.artwork ?: emptyList())
        if (!s.showArtwork || candidates.isEmpty() || f.value.recognition.kind == MediaKind.ADULT) return
        scope.launch(artworkDispatcher) {
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val permitted = { job?.isActive != false && artworkNetworkAllowed(key) }
            val providerOrder = service.providerInfos().map { it.id }
            // The user's own image always wins (explicit pin > provider art); otherwise deterministic order.
            val r = a.userArtwork?.let { artwork.loadOne(it, ArtworkRole.SQUARE, false) }?.takeIf { it is ArtworkLoadResult.Loaded }
                ?: artwork.load(match?.artwork ?: emptyList(), ArtworkRole.SQUARE, true, providerOrder, permitted)
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
