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
    val automaticLookupEnabled: Boolean = true,
    /** Transient, privacy-safe background status, separate from persisted provider identity. */
    val automaticMessage: String? = null
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
    private val providerConfigurationRevision = java.util.concurrent.atomic.AtomicLong(0L)
    private val mediaDataGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val lastProviderAttemptRevision = java.util.concurrent.ConcurrentHashMap<MediaTransferKey, Long>()
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
            val a = store.get(key) ?: if (enabled && localRulesAllowed()) applyRuleIfAny(key, flow.value.recognition) else null
            flow.update { it.copy(assignment = store.get(key), automaticLookupEnabled = enabled,
                automaticMessage = artworkGapReason(a ?: it.assignment) ?:
                    if ((a ?: it.assignment)?.displayMatch != null) null else automaticPauseReason(enabled)) }
            if (a?.displayMatch != null || a?.userArtwork != null) loadArtwork(key)
            if (automaticDue(key, a)) maybeAutomatic(key)
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
            val a = store.get(key) ?: if (enabled && localRulesAllowed()) applyRuleIfAny(key, f.value.recognition) else null
            f.update { it.copy(assignment = store.get(key), automaticLookupEnabled = enabled,
                automaticMessage = artworkGapReason(a ?: it.assignment) ?:
                    if ((a ?: it.assignment)?.displayMatch != null) null else automaticPauseReason(enabled)) }
            if (a?.displayMatch != null || a?.userArtwork != null) loadArtwork(key)
            if (automaticDue(key, a)) maybeAutomatic(key)
        }
    }

    private fun automaticDue(key: MediaTransferKey, assignment: TransferMediaAssignment?): Boolean {
        if (assignment == null) return true
        val now = clock()
        val stale = now >= assignment.updatedAtMs &&
            now - assignment.updatedAtMs >= MediaEnrichmentService.NOT_FOUND_TTL_MS
        val previousRevision = lastProviderAttemptRevision[key]
        // This is the first inventory pass for this key in this process. A configured
        // provider may have been added while Chunks was closed; the old saved
        // assignment cannot encode that in-memory configuration revision.
        // Per-provider query-cache receipts still prevent premature HTTP retries.
        val firstPassAfterRestart = previousRevision == null
        val providerChanged = providerConfigurationRevision.get() > (previousRevision ?: 0L)
        if (assignment.state == MediaAssignmentState.COULDNT_IDENTIFY &&
            assignment.lastStatus == MediaLookupStatus.NOT_FOUND)
            return stale || firstPassAfterRestart || providerChanged
        // An ambiguous filename cannot be verified by its existing suggestions, but
        // a newly enabled provider can break a tie. Retry once per provider revision
        // and once on cold startup, with service query-cache TTLs guarding HTTP.
        // Ordinary inventory notifications must not repeatedly search the same ambiguity.
        if (assignment.state == MediaAssignmentState.SUGGESTED)
            return firstPassAfterRestart || providerChanged
        val match = assignment.match ?: return false
        if (assignment.state != MediaAssignmentState.AUTOMATIC ||
            assignment.userArtwork != null || match.artwork.isNotEmpty()) return false
        return (stale || firstPassAfterRestart || providerChanged) &&
            service.hasConfiguredArtworkProvider(match.kind, settings())
    }

    /** Verified identity can coexist with missing artwork: disclose the gap without inventing a poster. */
    private fun artworkGapReason(a: TransferMediaAssignment?): String? {
        val match = a?.displayMatch ?: return null
        if (a.userArtwork != null || match.artwork.isNotEmpty()) return null
        val s = settings()
        return when {
            !s.onlineLookupConsent -> "Identified; fetching artwork requires online lookup consent"
            !s.automaticLookup -> "Identified; automatic artwork lookup is switched off"
            !s.showArtwork -> "Identified; artwork display is off in Options"
            !s.downloadArtwork -> "Identified; artwork download is off in Options"
            !network().online -> "Identified; artwork retrieval is paused while offline"
            network().powerSaver -> "Identified; artwork retrieval is paused in power saver mode"
            network().metered -> "Identified; artwork retrieval is paused on a metered network"
            !service.hasConfiguredArtworkProvider(match.kind, s) ->
                "Identified; no artwork provider is configured for this media"
            else -> "Identified; no verified poster or cover was returned"
        }
    }

    /** Safe status for an unresolved title. Never stores or displays a raw query or path. */
    private fun automaticPauseReason(enabledForTransfer: Boolean): String? {
        val s = settings()
        val net = network()
        return when {
            !enabledForTransfer -> "Automatic identification excluded for this transfer"
            !s.automaticLookup -> "Automatic identification switched off in Options"
            !s.showArtwork || !s.downloadArtwork -> "Automatic artwork collection switched off in Options"
            !s.onlineLookupConsent -> "Online identification needs your consent in Options"
            !net.online -> "Offline; artwork lookup will resume with a connection"
            net.powerSaver -> "Artwork lookup paused while power saver is on"
            net.metered -> "Artwork lookup paused on a metered network"
            else -> null
        }
    }

    private fun automaticAllowed(key: MediaTransferKey, authorized: () -> Boolean): Boolean =
        authorized() && settings().let { it.onlineLookupConsent && it.automaticLookup && it.showArtwork && it.downloadArtwork } &&
            !automationStore.isExcluded(key)

    /** Local saved rules need global automation/collection enabled, but never provider consent. */
    private fun localRulesAllowed(): Boolean =
        settings().let { it.automaticLookup && it.showArtwork && it.downloadArtwork }

    /** Retry missing poster sources after credential change; user overrides remain protected. */
    fun onProviderConfigurationChanged() {
        providerConfigurationRevision.incrementAndGet()
        onSettingsChanged()
        synchronized(this) { states.keys.toList() }.forEach(::refreshAutomatic)
    }

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
        val providerRevision = providerConfigurationRevision.get()
        val dataGeneration = mediaDataGeneration.get()
        val permitted = { job?.isActive != false && providerConfigurationRevision.get() == providerRevision &&
            mediaDataGeneration.get() == dataGeneration && automaticAllowed(key, authorized) }
        if (!permitted()) return
        if (!synchronized(this) { pendingAutomatic.add(key) }) return
        try {
            automaticSlots.acquire()
            try {
                if (!permitted()) return
                val previous = store.get(key)
                if (!automaticDue(key, previous)) {
                    // Offscreen, already verified transfers may be missing the processed
                    // derivative after cache eviction or a previous failed image request.
                    // Re-fetch only absent images; no metadata query or user-choice mutation.
                    val matched = previous?.takeIf {
                        it.state == MediaAssignmentState.AUTOMATIC && it.userArtwork == null
                    }?.displayMatch
                    if (matched != null && matched.artwork.isNotEmpty() &&
                        recognition.kind != MediaKind.ADULT) {
                        val s = settings()
                        val net = network()
                        if (s.showArtwork && s.downloadArtwork && !net.powerSaver &&
                            net.online && (!net.metered || s.artworkOnMeteredNetwork)) {
                            kotlinx.coroutines.withContext(artworkDispatcher) {
                                if (permitted() && !artwork.hasCachedSquare(matched.artwork)) {
                                    artwork.load(matched.artwork, ArtworkRole.SQUARE, true,
                                        service.providerInfos().map { it.id },
                                        requestAllowed = { permitted() && artworkNetworkAllowed(key) { true } })
                                }
                            }
                        }
                    }
                    return
                }
                flow(key)?.update { cur ->
                    if (cur.assignment == previous && cur.match == null)
                        cur.copy(automaticMessage = "Looking up artwork and metadata") else cur
                }
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
                // A previously verified work without cover art is a poster-completion request.
                // Never replace its verified provider identity with a superficially similar work.
                val missingArt = previous?.takeIf {
                    it.state == MediaAssignmentState.AUTOMATIC && it.userArtwork == null &&
                        it.match != null && it.match.artwork.isEmpty()
                }
                if (missingArt != null) {
                    if (outcome.status !in setOf(MediaLookupStatus.MATCHED, MediaLookupStatus.NEEDS_REVIEW,
                            MediaLookupStatus.NOT_FOUND)) return
                    val original = missingArt.match!!
                    val proposed = outcome.match
                    val sameWork = proposed != null && original.kind.compatibleWith(proposed.kind) &&
                        original.year != null && original.year == proposed.year &&
                        MediaText.similarity(original.title, proposed.title) >= MediaCandidateMatcher.CONFIDENT_TITLE_SIMILARITY
                    val verifiedArt = proposed?.artwork?.takeIf { sameWork && it.isNotEmpty() }
                    val updated = missingArt.copy(
                        match = if (verifiedArt != null) original.copy(artwork = verifiedArt) else original,
                        lastStatus = outcome.status, attempts = outcome.attempts,
                        lastMessage = if (verifiedArt != null) "Artwork found for the verified identity"
                            else "Identified, but no verified matching artwork available",
                        updatedAtMs = clock())
                    if (!replaceIfUnchanged(key, previous, updated, permitted)) return
                    lastProviderAttemptRevision[key] = providerConfigurationRevision.get()
                    flow(key)?.update { cur -> if (store.get(key) == updated)
                        cur.copy(assignment = updated,
                            automaticMessage = if (verifiedArt == null) updated.lastMessage else null) else cur }
                    if (verifiedArt != null) {
                        if (flow(key) != null) loadArtwork(key)
                        else if (permitted() && preferences.showArtwork && preferences.downloadArtwork) {
                            val net = network()
                            if (net.online && (!net.metered || preferences.artworkOnMeteredNetwork)) {
                                kotlinx.coroutines.withContext(artworkDispatcher) {
                                    if (permitted()) artwork.load(verifiedArt, ArtworkRole.SQUARE, true,
                                        service.providerInfos().map { it.id },
                                        requestAllowed = { permitted() && artworkNetworkAllowed(key) { true } })
                                }
                            }
                        }
                    }
                    return
                }
                val state = when (outcome.status) {
                    MediaLookupStatus.MATCHED -> MediaAssignmentState.AUTOMATIC
                    MediaLookupStatus.NEEDS_REVIEW -> MediaAssignmentState.SUGGESTED
                    MediaLookupStatus.NOT_FOUND -> MediaAssignmentState.COULDNT_IDENTIFY
                    else -> {
                        // A missing/disabled provider needs no repeated per-inventory
                        // retry until configuration changes; offline remains retryable.
                        if (outcome.status == MediaLookupStatus.NO_PROVIDER && previous != null)
                            lastProviderAttemptRevision[key] = providerConfigurationRevision.get()
                        flow(key)?.update { cur ->
                            if (cur.assignment == previous && cur.match == null)
                                cur.copy(automaticMessage = outcome.message) else cur
                        }
                        return
                    }
                }
                // An entirely cached negative recheck is not a new finding.
                // Leave the persisted assignment and original retry anchor
                // untouched; updating it, even only for status, can postpone
                // a legitimate automatic retry after the provider TTL.
                if (previous?.state == MediaAssignmentState.COULDNT_IDENTIFY &&
                    outcome.status == MediaLookupStatus.NOT_FOUND &&
                    outcome.attempts.all { it.fromCache }) {
                    lastProviderAttemptRevision[key] = providerConfigurationRevision.get()
                    return
                }
                val assignment = TransferMediaAssignment(key, state, match = outcome.match,
                    suggestions = outcome.alternates, lastStatus = outcome.status,
                    lastMessage = outcome.message, attempts = outcome.attempts,
                    // Cached negative evidence is not a fresh provider search; retain
                    // the original expiry anchor instead of postponing retries.
                    updatedAtMs = if (previous?.state == MediaAssignmentState.COULDNT_IDENTIFY &&
                        outcome.status == MediaLookupStatus.NOT_FOUND &&
                        // No provider attempts also means no new network evidence.
                        // Keep the original negative-cache expiry anchor.
                        outcome.attempts.all { it.fromCache })
                        previous.updatedAtMs else clock())
                if (!replaceIfUnchanged(key, previous, assignment, permitted)) return
                lastProviderAttemptRevision[key] = providerConfigurationRevision.get()
                flow(key)?.update { cur ->
                    if (store.get(key) == assignment) cur.copy(assignment = assignment, busy = false,
                        automaticMessage = artworkGapReason(assignment) ?: if (assignment.displayMatch != null) null else outcome.message) else cur
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
    suspend fun enrichInventory(entries: Collection<Pair<MediaTransferKey, String>>,
                                isAuthorized: (MediaTransferKey) -> Boolean = { true }) {
        kotlinx.coroutines.withContext(backgroundDispatcher) {
            for ((key, name) in entries.distinctBy { it.first }) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!isAuthorized(key)) continue
                val recognition = MediaRecognizer.recognize(name)
                // A rule the user explicitly saved is local identity data, not an online
                // lookup. Apply it to a newly arrived offscreen job even before provider
                // consent, just as observe() does for a rendered row.
                if (localRulesAllowed() && store.get(key) == null && !automationStore.isExcluded(key))
                    applyRuleIfAny(key, recognition, authorized = { isAuthorized(key) })
                try { enrichAutomatic(key, recognition) { isAuthorized(key) } }
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
        val providerRevision = providerConfigurationRevision.get()
        val dataGeneration = mediaDataGeneration.get()
        val allowed = { job?.isActive != false && providerConfigurationRevision.get() == providerRevision &&
            mediaDataGeneration.get() == dataGeneration && authorization(key)() && settings().onlineLookupConsent }
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
        if (assignment.displayMatch != null) loadArtwork(key, explicitRequest = origin == MediaLookupOrigin.USER_REQUEST)
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
            val providerRevision = providerConfigurationRevision.get()
            val dataGeneration = mediaDataGeneration.get()
            val permitted = { job?.isActive != false && providerConfigurationRevision.get() == providerRevision &&
                mediaDataGeneration.get() == dataGeneration && authorization(key)() && settings().onlineLookupConsent }
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
        // Future-arrival rules never replace an already persisted provider assignment,
        // including a verified artless match being retried for a missing poster.
        if (expected != null) return null
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
    suspend fun alternatives(key: MediaTransferKey, limit: Int = 8): List<ArtworkAlternative> {
        val dataGeneration = mediaDataGeneration.get()
        return kotlinx.coroutines.withContext(artworkDispatcher) {
            if (mediaDataGeneration.get() != dataGeneration) return@withContext emptyList()
            val cur = flow(key)?.value ?: return@withContext emptyList()
            val match = cur.match ?: return@withContext emptyList()
            val s = settings()
            if (!s.showArtwork || cur.displayKind == MediaKind.ADULT) return@withContext emptyList()
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val permitted = { job?.isActive != false && mediaDataGeneration.get() == dataGeneration &&
                artworkNetworkAllowed(key, explicitRequest = true) }
            match.artwork.filter { it.season == null || it.season == match.season }.distinctBy { it.url }.take(limit.coerceIn(1, 24))
                .mapNotNull { c -> (artwork.loadOne(c, ArtworkRole.SQUARE, true, permitted) as? ArtworkLoadResult.Loaded)?.let { ArtworkAlternative(c, it.bitmap) } }
        }
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
    suspend fun posterAlternatives(key: MediaTransferKey, limit: Int = 8): List<ArtworkAlternative> {
        val dataGeneration = mediaDataGeneration.get()
        return kotlinx.coroutines.withContext(artworkDispatcher) {
            if (mediaDataGeneration.get() != dataGeneration) return@withContext emptyList()
            val cur = flow(key)?.value ?: return@withContext emptyList()
            val match = cur.match ?: return@withContext emptyList()
            val s = settings()
            if (!s.showArtwork || cur.displayKind == MediaKind.ADULT || cur.userArtwork != null) return@withContext emptyList()
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val permitted = { job?.isActive != false && mediaDataGeneration.get() == dataGeneration &&
                artworkNetworkAllowed(key, explicitRequest = true) }
            posterCandidates(match).distinctBy { it.url }.take(limit.coerceIn(1, 24))
                .mapNotNull { c -> (artwork.loadOne(c, ArtworkRole.POSTER, true, permitted) as? ArtworkLoadResult.Loaded)?.let { ArtworkAlternative(c, it.bitmap) } }
        }
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
            // Provider configuration revisions are issued by ChunksMedia.updateSettings()
            // and setProviderKey(), never by this presentation refresh.
            largeListArtworkFlow.value = s.largeListArtwork
            presentationFlow.value = s
            snapshot.forEach { (key, f) ->
                val enabled = !automationStore.isExcluded(key)
                f.update { cur -> cur.copy(automaticMessage =
                    artworkGapReason(cur.assignment) ?: if (cur.match != null) null else automaticPauseReason(enabled)) }
                if (authorization(key)() && s.onlineLookupConsent && s.automaticLookup &&
                    s.showArtwork && s.downloadArtwork && automaticDue(key, f.value.assignment)) maybeAutomatic(key)
                if (!s.showArtwork) f.update { it.copy(artwork = null, artworkSource = null, poster = null) }
                else if (f.value.artwork == null && f.value.assignment != null) loadArtwork(key)
            }
        }
    }

    fun dismissSearch(key: MediaTransferKey) { flow(key)?.update { it.copy(searchResults = null, message = null) } }

    /** Purge assignments, lookup history and downloaded artwork. Transfers are untouched. */
    fun clearAllMediaData(clearQueryCache: () -> Unit, clearArtwork: () -> Unit): kotlinx.coroutines.Job {
        // Invalidate in-flight HTTP callbacks *before* the queued deletion runs.
        // Otherwise a late background response can repopulate records after purge.
        mediaDataGeneration.incrementAndGet()
        return scope.launch(lookupDispatcher) {
            store.clear(); ruleStore.clear(); clearQueryCache(); artwork.clearSafely(clearArtwork)
            synchronized(this@TransferMediaController) { states.values.toList() }.forEach { f ->
                f.update { it.copy(assignment = null, artwork = null, artworkSource = null, poster = null,
                    searchResults = null, message = null, automaticMessage = null) }
            }
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
        val dataGeneration = mediaDataGeneration.get()
        // Settings/network reads may touch disk: never on the caller's (main) thread.
        scope.launch(artworkDispatcher) {
            val s = settings()
            if (!s.showArtwork) return@launch
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val permitted = { job?.isActive != false && mediaDataGeneration.get() == dataGeneration &&
                artworkNetworkAllowed(key, explicitRequest = cur.assignment?.state == MediaAssignmentState.CHOSEN_BY_USER) }
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
            (!net.metered || s.artworkOnMeteredNetwork) &&
            // Displaying a verified transfer must not silently collect new artwork
            // after the user disables automatic collection or enables power saver.
            // A deliberate manual image choice remains separately consent-gated.
            (explicitRequest || (s.automaticLookup && !net.powerSaver))
    }

    private fun loadArtwork(key: MediaTransferKey, pin: Boolean = false,
                            explicitRequest: Boolean = false) {
        val f = flow(key) ?: return
        val s = settings()
        val a = f.value.assignment ?: return
        val match = a.displayMatch
        val candidates = listOfNotNull(a.userArtwork) + (match?.artwork ?: emptyList())
        if (!s.showArtwork || candidates.isEmpty() || f.value.recognition.kind == MediaKind.ADULT) return
        val dataGeneration = mediaDataGeneration.get()
        scope.launch(artworkDispatcher) {
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            // Add's exclusion blocks automation, never a deliberately chosen manual image.
            // Consent, host allowlists, network gates and a live observer remain mandatory.
            val manualChoice = explicitRequest || a.state == MediaAssignmentState.CHOSEN_BY_USER
            val permitted = { job?.isActive != false && mediaDataGeneration.get() == dataGeneration &&
                artworkNetworkAllowed(key, explicitRequest = manualChoice) }
            val providerOrder = service.providerInfos().map { it.id }
            // The user's own image always wins (explicit pin > provider art); otherwise deterministic order.
            val r = a.userArtwork?.let { artwork.loadOne(it, ArtworkRole.SQUARE, false) }?.takeIf { it is ArtworkLoadResult.Loaded }
                ?: artwork.load(match?.artwork ?: emptyList(), ArtworkRole.SQUARE, true, providerOrder, permitted)
            when (r) {
                is ArtworkLoadResult.Loaded -> {
                    if (pin && r.source.provider != LOCAL_PROVIDER) artwork.pin(r.source, ArtworkRole.SQUARE)
                    f.update { cur ->
                        val still = cur.assignment?.let { now -> now.userArtwork == a.userArtwork && now.displayMatch?.ref == match?.ref } == true
                        if (still) cur.copy(artwork = r.bitmap, artworkSource = r.source,
                            automaticMessage = null) else cur
                    }
                }
                is ArtworkLoadResult.Unavailable -> {
                    // Distinguish a verified match from its missing image; no filenames, URLs
                    // or credentials are stored in this transient presentation message.
                    val net = network()
                    val reason = when {
                        !settings().onlineLookupConsent -> "Image is not cached; online lookup consent is off"
                        !net.online -> "Image is not cached; network is offline"
                        net.metered && !settings().artworkOnMeteredNetwork ->
                            "Image is not cached; artwork download is paused on mobile data"
                        !settings().downloadArtwork -> "Artwork download is switched off in Options"
                        else -> "Identified, but the image source is temporarily unavailable"
                    }
                    f.update { cur ->
                        if (cur.assignment == a && cur.artwork == null)
                            cur.copy(automaticMessage = reason) else cur
                    }
                }
            }
        }
    }
}
