package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.model.MediaEnrichmentCachePolicy
import io.github.gamesbyian.chunks.model.MediaLookupOrigin
import io.github.gamesbyian.chunks.model.MediaMetadataCacheDecision
import io.github.gamesbyian.chunks.model.MediaMetadataCacheReceipt
import io.github.gamesbyian.chunks.model.MediaMetadataReceiptState

/** User privacy and enrichment preferences. Online lookup is OFF until the user opts in. */
data class MediaPrivacySettings(
    val onlineLookupConsent: Boolean = false,
    /** Look up transfers as they become visible, without a tap. Needs [onlineLookupConsent]. */
    val automaticLookup: Boolean = true,
    val disabledProviders: Set<String> = emptySet(),
    /** Providers that are off by default (e.g. AniList) and that the user explicitly turned on. */
    val enabledProviders: Set<String> = emptySet(),
    val showArtwork: Boolean = true,
    val downloadArtwork: Boolean = true,
    val artworkOnMeteredNetwork: Boolean = false,
    /** Presentation only: 64dp list artwork instead of the compact 40dp default. */
    val largeListArtwork: Boolean = false,
    /** Presentation only: show "Similar transfers" in expanded details (SHARED-MEDIA-IDENTITY default on). */
    val showSimilarTransfers: Boolean = true,
    /** Similar transfers from the viewed transfer's own environment only (default: all configured). */
    val similarSameEnvironmentOnly: Boolean = false
)

fun MediaPrivacySettings.providerOn(info: MediaProviderInfo): Boolean =
    if (info.enabledByDefault) info.id !in disabledProviders else info.id in enabledProviders

data class MediaNetworkState(val online: Boolean, val metered: Boolean, val powerSaver: Boolean = false)

enum class MediaLookupStatus {
    MATCHED, NEEDS_REVIEW, NOT_FOUND, BLOCKED_CONSENT, REFUSED, NO_PROVIDER, PROVIDER_COOLDOWN, OFFLINE, ERROR_RETRYABLE
}

/** One line of the "Why?" lookup explanation; never contains the query text or secrets. */
data class ProviderAttempt(val provider: String, val outcome: String, val fromCache: Boolean = false)

data class MediaLookupResult(
    val status: MediaLookupStatus,
    val match: MediaCandidate? = null,
    val alternates: List<MediaCandidate> = emptyList(),
    val attempts: List<ProviderAttempt> = emptyList(),
    val message: String,
    val retryNotBeforeMs: Long? = null
)

/**
 * Provider-independent orchestrator: sanitized query → per-provider cache policy → rate/cooldown
 * gate → typed adapter → deterministic matching → optional refine → artwork completion from a later
 * provider. Blocking; run on a background dispatcher. Never touches transfer or engine state.
 */
class MediaEnrichmentService(
    private val providers: List<MediaMetadataProvider>,
    private val http: MediaHttp,
    private val cache: MediaQueryCache,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    /** Longest polite wait for a provider's own minimum interval before deferring instead. */
    private val maxWaitMs: Long = 2_500
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val FOUND_TTL_MS = 30L * 24 * 3600_000
        const val NOT_FOUND_TTL_MS = 3L * 24 * 3600_000
        const val MANUAL_NOT_FOUND_TTL_MS = 3600_000L
        const val FAILURE_TTL_MS = 10L * 60_000
    }

    private val lastCall = HashMap<String, Long>()
    private val cooldownUntil = HashMap<String, Long>()
    private val unauthorized = HashSet<String>()
    private var revision = 0L

    fun providerInfos(): List<MediaProviderInfo> = providers.map { it.info }

    /** Returns configuration only; this does not trigger an outbound provider request. */
    fun hasConfiguredArtworkProvider(kind: MediaKind, preferences: MediaPrivacySettings): Boolean =
        providers.any { it.info.providesArtwork && it.info.kinds.any(kind::compatibleWith) &&
            preferences.providerOn(it.info) && it.isConfigured() }

    @Synchronized fun resetProviderPause(provider: String) { unauthorized.remove(provider); cooldownUntil.remove(provider) }

    /** Serializes a privacy purge with in-flight lookups and their response-cache commits. */
    @Synchronized fun clearLookupHistory() { cache.clear() }

    fun lookup(recognition: MediaRecognition, settings: MediaPrivacySettings, network: MediaNetworkState,
               origin: MediaLookupOrigin = MediaLookupOrigin.USER_REQUEST,
               requestAllowed: () -> Boolean = { true }): MediaLookupResult =
        when (val d = MediaQuerySanitizer.fromRecognition(recognition)) {
            is MediaQueryDecision.Refused -> MediaLookupResult(MediaLookupStatus.REFUSED, message = d.reason)
            is MediaQueryDecision.Allowed -> lookup(d.query, settings, network, origin, recognition.episodeTitle, requestAllowed)
        }

    @Synchronized
    fun lookup(query: MediaLookupQuery, settings: MediaPrivacySettings, network: MediaNetworkState,
               origin: MediaLookupOrigin = MediaLookupOrigin.USER_REQUEST, episodeTitle: String? = null,
               requestAllowed: () -> Boolean = { true }): MediaLookupResult {
        val requestHttp = object : MediaHttp {
            private fun checkPermission() {
                if (!requestAllowed()) throw MediaHttpException(MediaHttpFailure.BLOCKED_HOST, "Lookup is no longer authorized")
            }
            override fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse {
                checkPermission()
                return http.get(url, hosts.whileAllowed(requestAllowed), headers, maxBytes)
            }
            override fun postJson(url: String, hosts: MediaHostPolicy, body: String, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse {
                checkPermission()
                return http.postJson(url, hosts.whileAllowed(requestAllowed), body, headers, maxBytes)
            }
        }
        if (!settings.onlineLookupConsent) return MediaLookupResult(MediaLookupStatus.BLOCKED_CONSENT,
            message = "Online lookup is off. Turn it on in media settings to search providers.")
        if (!requestAllowed()) return MediaLookupResult(MediaLookupStatus.BLOCKED_CONSENT, message = "Lookup is no longer authorized")
        if (origin == MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT &&
            (!settings.automaticLookup || !settings.showArtwork || !settings.downloadArtwork ||
                network.metered || network.powerSaver))
            return MediaLookupResult(MediaLookupStatus.BLOCKED_CONSENT, message = when {
                !settings.automaticLookup -> "Automatic identification is switched off"
                !settings.showArtwork || !settings.downloadArtwork -> "Automatic artwork collection is switched off"
                network.powerSaver -> "Automatic identification is paused in power saver mode"
                else -> "Automatic identification is waiting for an unmetered connection"
            })
        val eligible = providers.filter { settings.providerOn(it.info) && it.supports(query) && it.isConfigured() }
        if (eligible.isEmpty()) return MediaLookupResult(MediaLookupStatus.NO_PROVIDER,
            message = "No enabled provider covers ${query.kind.label.lowercase()} lookups" +
                if (providers.any { it.supports(query) && !it.isConfigured() }) " (add your own key in media settings)" else "")

        val attempts = mutableListOf<ProviderAttempt>()
        val review = mutableListOf<ScoredCandidate>()
        var confident: MediaCandidate? = null
        var cooldown: Long? = null
        var offline = false
        var retryable = false
        for (provider in eligible) {
            if (!requestAllowed()) return MediaLookupResult(MediaLookupStatus.BLOCKED_CONSENT, message = "Lookup is no longer authorized")
            val id = provider.info.id
            val key = MediaQueryKeys.of(id, query)
            val cached = cache.get(key)
            val now = clock()
            val decision = MediaEnrichmentCachePolicy.build(listOfNotNull(cached?.receipt)).decide(key,
                approvedProviders = setOf(id), consentGranted = true, memberVisible = true,
                networkAllowed = network.online, currentSchemaVersion = SCHEMA_VERSION, nowMs = now,
                staleDisplayAllowed = true)
            val candidates: List<MediaCandidate> = when (decision) {
                MediaMetadataCacheDecision.FRESH_VERIFIED -> { attempts.add(ProviderAttempt(id, "Found (cached)", true)); cached!!.candidates }
                MediaMetadataCacheDecision.STALE_DISPLAY_ONLY -> { attempts.add(ProviderAttempt(id, "Found (cached, offline)", true)); cached!!.candidates }
                MediaMetadataCacheDecision.NEGATIVE_CACHE -> { attempts.add(ProviderAttempt(id, "No match (checked recently)", true)); continue }
                MediaMetadataCacheDecision.WAIT_FOR_RETRY -> {
                    attempts.add(ProviderAttempt(id, "Waiting to retry", true))
                    cooldown = minOf(cooldown ?: Long.MAX_VALUE, cached?.receipt?.retryNotBeforeMs ?: cached!!.receipt.expiresAtMs)
                    continue
                }
                MediaMetadataCacheDecision.OFFLINE_MISS -> { attempts.add(ProviderAttempt(id, "Offline")); offline = true; continue }
                MediaMetadataCacheDecision.FETCH_ELIGIBLE -> {
                    val outcome = fetch(provider, query, key, attempts, requestHttp, requestAllowed)
                    when (outcome) {
                        is FetchResult.Cooldown -> cooldown = minOf(cooldown ?: Long.MAX_VALUE, outcome.until)
                        FetchResult.Offline -> offline = true
                        FetchResult.Retryable -> retryable = true
                        else -> {}
                    }
                    if (outcome !is FetchResult.Candidates) continue
                    outcome.list
                }
                else -> { attempts.add(ProviderAttempt(id, "Skipped")); continue }
            }
            if (!requestAllowed()) return MediaLookupResult(MediaLookupStatus.BLOCKED_CONSENT, message = "Lookup is no longer authorized")
            when (val m = MediaCandidateMatcher.match(query, candidates, episodeTitle)) {
                is MediaMatchDecision.Confident -> {
                    val refined = refine(provider, m.best.candidate, query, requestHttp)
                    if (confident == null) {
                        confident = refined
                        if (refined.artwork.isNotEmpty() || !settings.downloadArtwork) break
                    } else if (confident.artwork.isEmpty() && refined.artwork.isNotEmpty() &&
                        safeArtworkCompletion(confident, refined)) {
                        // Metadata and artwork are independent: keep the first record, add later art.
                        confident = confident.copy(artwork = refined.artwork)
                        break
                    }
                }
                is MediaMatchDecision.Ambiguous -> {
                    val resolved = if (confident == null) disambiguateByEpisode(provider, query, m, episodeTitle, requestHttp) else null
                    if (resolved != null) {
                        attempts.add(ProviderAttempt(id, "Matched by episode"))
                        confident = resolved
                        if (resolved.artwork.isNotEmpty()) break
                    } else review.addAll(m.ranked.filter { it.eligible })
                }
                is MediaMatchDecision.NoMatch -> attempts.add(ProviderAttempt(id, "No close title"))
            }
        }
        val found = confident
        if (found != null) {
            return MediaLookupResult(MediaLookupStatus.MATCHED, found,
                alternates = review.map { it.candidate }.filter { it.ref != found.ref }.take(12),
                attempts = attempts, message = "Matched with ${providerName(found.ref.provider)}")
        }
        if (review.isNotEmpty()) {
            val ranked = review.sortedByDescending { it.score }.map { it.candidate }.distinctBy { it.ref }
            return MediaLookupResult(MediaLookupStatus.NEEDS_REVIEW, null, ranked.take(12), attempts,
                "Several possible matches. Choose the right one.")
        }
        return when {
            cooldown != null -> MediaLookupResult(MediaLookupStatus.PROVIDER_COOLDOWN, attempts = attempts,
                message = "Provider busy; try again later", retryNotBeforeMs = cooldown)
            offline -> MediaLookupResult(MediaLookupStatus.OFFLINE, attempts = attempts, message = "Offline; lookup will need a connection")
            retryable -> MediaLookupResult(MediaLookupStatus.ERROR_RETRYABLE, attempts = attempts, message = "Lookup failed; try again")
            else -> MediaLookupResult(MediaLookupStatus.NOT_FOUND, attempts = attempts, message = "No match found")
        }
    }

    private sealed class FetchResult {
        data class Candidates(val list: List<MediaCandidate>) : FetchResult()
        data class Cooldown(val until: Long) : FetchResult()
        object Offline : FetchResult()
        object Retryable : FetchResult()
        object Nothing : FetchResult()
    }

    private fun fetch(provider: MediaMetadataProvider, query: MediaLookupQuery,
                      key: io.github.gamesbyian.chunks.model.MediaMetadataCacheKey, attempts: MutableList<ProviderAttempt>, requestHttp: MediaHttp, requestAllowed: () -> Boolean): FetchResult {
        val id = provider.info.id
        if (id in unauthorized) { attempts.add(ProviderAttempt(id, "Paused: check provider key")); return FetchResult.Nothing }
        val until = cooldownUntil[id]
        if (until != null && clock() < until) { attempts.add(ProviderAttempt(id, "Cooling down")); return FetchResult.Cooldown(until) }
        if (!waitTurn(provider)) { attempts.add(ProviderAttempt(id, "Rate limit; deferred")); return FetchResult.Cooldown(nextAllowed(provider)) }
        lastCall[id] = clock()
        val outcome = provider.search(query, requestHttp)
        if (!requestAllowed()) return FetchResult.Nothing
        val now = clock()
        fun store(state: MediaMetadataReceiptState, ttl: Long, retry: Long? = null, list: List<MediaCandidate> = emptyList()) =
            cache.put(CachedLookup(MediaMetadataCacheReceipt(key, ++revision, SCHEMA_VERSION, now, now + ttl, state, retry), list))
        return when (outcome) {
            is MediaProviderOutcome.Found -> {
                store(MediaMetadataReceiptState.VERIFIED, FOUND_TTL_MS, list = outcome.candidates)
                attempts.add(ProviderAttempt(id, "Found ${outcome.candidates.size}"))
                FetchResult.Candidates(outcome.candidates)
            }
            MediaProviderOutcome.NotFound -> {
                store(MediaMetadataReceiptState.NOT_FOUND, if (query.manual) MANUAL_NOT_FOUND_TTL_MS else NOT_FOUND_TTL_MS)
                attempts.add(ProviderAttempt(id, "No match")); FetchResult.Nothing
            }
            is MediaProviderOutcome.RateLimited -> {
                val retry = now + outcome.retryAfterMs
                cooldownUntil[id] = retry
                store(MediaMetadataReceiptState.RATE_LIMITED, outcome.retryAfterMs, retry)
                attempts.add(ProviderAttempt(id, "Rate limited")); FetchResult.Cooldown(retry)
            }
            MediaProviderOutcome.Unauthorized -> {
                if (!provider.info.requiresUserKey) {
                    val retry = now + 10 * 60_000L
                    cooldownUntil[id] = retry
                    attempts.add(ProviderAttempt(id, "Access refused; will retry later")); return FetchResult.Cooldown(retry)
                }
                unauthorized.add(id)
                attempts.add(ProviderAttempt(id, "Rejected key or access")); FetchResult.Nothing
            }
            is MediaProviderOutcome.Failed -> {
                if (outcome.reason == "Offline") { attempts.add(ProviderAttempt(id, "Offline")); return FetchResult.Offline }
                if (outcome.retryable) store(MediaMetadataReceiptState.TEMPORARY_FAILURE, FAILURE_TTL_MS, now + FAILURE_TTL_MS)
                attempts.add(ProviderAttempt(id, outcome.reason))
                if (outcome.retryable) FetchResult.Retryable else FetchResult.Nothing
            }
            is MediaProviderOutcome.BadSchema -> {
                store(MediaMetadataReceiptState.TEMPORARY_FAILURE, 24 * 3600_000L, now + 24 * 3600_000L)
                attempts.add(ProviderAttempt(id, "Unexpected response")); FetchResult.Nothing
            }
        }
    }

    private fun nextAllowed(p: MediaMetadataProvider) = (lastCall[p.info.id] ?: 0L) + p.info.minIntervalMs

    private fun waitTurn(p: MediaMetadataProvider): Boolean {
        val wait = nextAllowed(p) - clock()
        if (wait <= 0) return true
        if (wait > maxWaitMs) return false
        sleeper(wait)
        return true
    }

    private fun refine(provider: MediaMetadataProvider, c: MediaCandidate, q: MediaLookupQuery, requestHttp: MediaHttp): MediaCandidate {
        if (q.season == null) return c
        if (!waitTurn(provider)) return c
        lastCall[provider.info.id] = clock()
        return runCatching { provider.refine(c, q, requestHttp) }.getOrDefault(c)
    }

    /** Ask the provider which of the tied shows actually has this exact episode (bounded to 3). */
    private fun disambiguateByEpisode(provider: MediaMetadataProvider, q: MediaLookupQuery, m: MediaMatchDecision.Ambiguous,
                                      episodeTitle: String?, requestHttp: MediaHttp): MediaCandidate? {
        if (q.season == null || q.episode == null) return null
        val top = m.ranked.filter { it.eligible && it.titleSimilarity >= MediaCandidateMatcher.CONFIDENT_TITLE_SIMILARITY }.take(3)
        if (top.size < 2) return null
        val withEpisode = top.mapNotNull { s -> refine(provider, s.candidate, q, requestHttp).takeIf { it.episodeTitle != null } }
        if (withEpisode.size == 1) return withEpisode.single()
        if (episodeTitle != null && withEpisode.size > 1) {
            val byTitle = withEpisode.filter { MediaText.similarity(episodeTitle, it.episodeTitle!!) >= 0.85 }
            if (byTitle.size == 1) return byTitle.single()
        }
        return null
    }

    /** Identity match is stronger than a title match when borrowing another provider's cover.
     * In particular, remakes with identical names must never exchange posters. */
    private fun safeArtworkCompletion(verified: MediaCandidate, source: MediaCandidate): Boolean {
        if (!verified.kind.compatibleWith(source.kind) ||
            MediaText.similarity(verified.title, source.title) < MediaCandidateMatcher.CONFIDENT_TITLE_SIMILARITY)
            return false
        // For movies an unknown release year cannot prove the two provider records depict one work.
        if (verified.kind == MediaKind.MOVIE || source.kind == MediaKind.MOVIE)
            if (verified.year == null || source.year == null) return false
        if (verified.year != null && source.year != null && verified.year != source.year) return false
        if (verified.season != null && source.season != null && verified.season != source.season) return false
        if (verified.episode != null && source.episode != null && verified.episode != source.episode) return false
        return true
    }

    private fun providerName(id: String) = providers.firstOrNull { it.info.id == id }?.info?.displayName ?: id
}
