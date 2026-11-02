package io.github.gamesbyian.chunks.model

/** Explicit scheduling source; simply opening transfer details cannot create a request. */
enum class MediaLookupOrigin { USER_REQUEST, CONSENTED_BACKGROUND_ENRICHMENT }

data class MediaLookupIntent(
    val key: MediaMetadataCacheKey,
    val origin: MediaLookupOrigin,
    /** Derived from the currently authorized, visible inventory. Counts are not identities. */
    val authorizedReferences: Int,
    val queuedAtEpochMs: Long = 0L
) {
    init { require(authorizedReferences >= 0 && queuedAtEpochMs >= 0) }
}

/** Every provider budget is configured under its own terms, never inferred from a universal rate. */
data class MediaProviderDispatchBudget(
    val provider: String,
    val minimumIntervalMs: Long,
    val maximumConcurrent: Int,
    val remainingDailyRequests: Int,
    val lastDispatchEpochMs: Long? = null,
    val retryNotBeforeEpochMs: Long? = null,
    val inFlight: Int = 0
) {
    init {
        require(provider.matches(Regex("[a-z][a-z0-9-]*")))
        require(minimumIntervalMs >= 0 && maximumConcurrent in 1..16)
        require(remainingDailyRequests >= 0 && inFlight in 0..maximumConcurrent)
        require(lastDispatchEpochMs == null || lastDispatchEpochMs >= 0)
        require(retryNotBeforeEpochMs == null || retryNotBeforeEpochMs >= 0)
    }
}

data class MediaEnrichmentDispatchScope(
    val nowEpochMs: Long,
    val online: Boolean,
    val metered: Boolean,
    val powerSaver: Boolean,
    val consentGranted: Boolean,
    val approvedProviders: Set<String>
) { init { require(nowEpochMs >= 0) } }

data class MediaLookupDispatch(val key: MediaMetadataCacheKey, val origin: MediaLookupOrigin)

/**
 * Deterministic *plan*, not an HTTP executor, task queue, network probe or clock owner.
 * The host must reserve each chosen request atomically before sending, enforce shared-IP
 * service limits, handle 429/503 and cancel pending requests when authorization changes.
 */
object MediaEnrichmentDispatchPlanner {
    fun plan(
        intents: Collection<MediaLookupIntent>,
        scope: MediaEnrichmentDispatchScope,
        budgets: Collection<MediaProviderDispatchBudget>,
        cacheEligibility: Map<MediaMetadataCacheKey, MediaMetadataCacheDecision>,
        maxBatch: Int = 4
    ): List<MediaLookupDispatch> {
        require(maxBatch in 1..16)
        require(budgets.map { it.provider }.toSet().size == budgets.size)
        if (!scope.online || !scope.consentGranted) return emptyList()
        val byProvider = budgets.associateBy { it.provider }
        val reserved = mutableMapOf<String, Int>()
        // Distinct typed identities across source environments consume one outbound lookup.
        val unique = intents.groupBy { it.key }.mapNotNull { (key, references) ->
            val authorized = references.filter { it.authorizedReferences > 0 }
            if (authorized.isEmpty()) null else MediaLookupIntent(key,
                if (authorized.any { it.origin == MediaLookupOrigin.USER_REQUEST })
                    MediaLookupOrigin.USER_REQUEST else MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT,
                authorized.sumOf { it.authorizedReferences.toLong() }
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                authorized.minOf { it.queuedAtEpochMs })
        }
        val ordered = unique.sortedWith(compareBy<MediaLookupIntent> {
            if (it.origin == MediaLookupOrigin.USER_REQUEST) 0 else 1
        }.thenBy { it.queuedAtEpochMs }.thenBy { it.key.provider }
            .thenBy { it.key.entityType }.thenBy { it.key.externalId })
        val result = mutableListOf<MediaLookupDispatch>()
        for (intent in ordered) {
            if (result.size == maxBatch) break
            val key = intent.key
            if (intent.queuedAtEpochMs > scope.nowEpochMs) continue
            if (key.provider !in scope.approvedProviders) continue
            if (cacheEligibility[key] != MediaMetadataCacheDecision.FETCH_ELIGIBLE) continue
            if (intent.origin == MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT &&
                (scope.metered || scope.powerSaver)) continue
            val budget = byProvider[key.provider] ?: continue
            val alreadyReserved = reserved[key.provider] ?: 0
            if (alreadyReserved >= budget.remainingDailyRequests) continue
            if (budget.inFlight + alreadyReserved >= budget.maximumConcurrent) continue
            // Time moving backwards invalidates a rate-limit token rather than permitting another call.
            if (budget.lastDispatchEpochMs != null) {
                if (scope.nowEpochMs < budget.lastDispatchEpochMs) continue
                if (scope.nowEpochMs - budget.lastDispatchEpochMs < budget.minimumIntervalMs) continue
            }
            if (budget.retryNotBeforeEpochMs != null && scope.nowEpochMs < budget.retryNotBeforeEpochMs) continue
            // Without a future reservation time from an executor, permit only one planned call per
            // provider per selection batch, preventing same-tick rate bursts after process restart.
            if (alreadyReserved > 0) continue
            reserved[key.provider] = alreadyReserved + 1
            result.add(MediaLookupDispatch(key, intent.origin))
        }
        return result
    }
}
