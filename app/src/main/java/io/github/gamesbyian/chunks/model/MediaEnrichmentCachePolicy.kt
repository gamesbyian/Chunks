package io.github.gamesbyian.chunks.model

/** Keyed only by already-identified, typed provider identifiers, never private titles or paths. */
data class MediaMetadataCacheKey(val provider: String, val entityType: String, val externalId: String) {
    init {
        require(provider.matches(Regex("[a-z][a-z0-9-]*")))
        require(entityType.matches(Regex("[a-z][a-z0-9-]*")))
        require(externalId.isNotBlank() && externalId.none { it.isISOControl() })
    }
}

enum class MediaMetadataReceiptState {
    /** Only an adapter's schema-validated, legally cacheable positive record may be marked VERIFIED. */
    VERIFIED, NOT_FOUND, RATE_LIMITED, TEMPORARY_FAILURE, UNSUPPORTED_SCHEMA
}

/** Contains no provider payload, artwork, private path, raw title or credential. */
data class MediaMetadataCacheReceipt(
    val key: MediaMetadataCacheKey,
    val revision: Long,
    val normalizedSchemaVersion: Int,
    val observedAtMs: Long,
    val expiresAtMs: Long,
    val state: MediaMetadataReceiptState,
    /** 429/503 backoff provided by adapter; must never be shorter than observedAtMs. */
    val retryNotBeforeMs: Long? = null
) {
    init {
        require(revision >= 0 && normalizedSchemaVersion > 0)
        require(observedAtMs >= 0 && expiresAtMs >= observedAtMs)
        require(retryNotBeforeMs == null || retryNotBeforeMs >= observedAtMs)
        require(state == MediaMetadataReceiptState.RATE_LIMITED ||
            state == MediaMetadataReceiptState.TEMPORARY_FAILURE || retryNotBeforeMs == null)
    }
}

enum class MediaMetadataCacheDecision {
    FORBIDDEN, /** Consent, source approval or visibility absent; no external lookup or cache disclosure. */
    FRESH_VERIFIED, /** May supply locally validated provider facts to derived media index. */
    STALE_DISPLAY_ONLY, /** Existing locally cached facts may be labelled stale, never newly verified. */
    NEGATIVE_CACHE, /** 404/unknown ID within receipt TTL, no repeated outbound probe. */
    WAIT_FOR_RETRY, /** 429/503 backoff, no automatic fresh query on detail opening. */
    SCHEMA_QUARANTINE, /** Unrecognized version/schema must be explicitly requalified. */
    FETCH_ELIGIBLE, /** Pure policy result only: caller must still explicitly schedule a lookup. */
    OFFLINE_MISS
}

/** No clock/network/framework dependency, and no stored provider body. */
class MediaEnrichmentCachePolicy private constructor(
    private val latest: Map<MediaMetadataCacheKey, MediaMetadataCacheReceipt>,
    private val ambiguous: Set<MediaMetadataCacheKey>
) {
    companion object {
        fun build(receipts: Collection<MediaMetadataCacheReceipt>): MediaEnrichmentCachePolicy {
            val latest = mutableMapOf<MediaMetadataCacheKey, MediaMetadataCacheReceipt>()
            val ambiguous = mutableSetOf<MediaMetadataCacheKey>()
            for ((key, versions) in receipts.groupBy { it.key }) {
                val maxRevision = versions.maxOf { it.revision }
                val options = versions.filter { it.revision == maxRevision }.distinct()
                if (options.size != 1) ambiguous.add(key) else latest[key] = options.single()
            }
            return MediaEnrichmentCachePolicy(latest, ambiguous)
        }
    }

    fun decide(
        key: MediaMetadataCacheKey,
        approvedProviders: Set<String>,
        consentGranted: Boolean,
        memberVisible: Boolean,
        networkAllowed: Boolean,
        currentSchemaVersion: Int,
        nowMs: Long,
        /** Whether this provider permits stale display under its current terms. */
        staleDisplayAllowed: Boolean = false
    ): MediaMetadataCacheDecision {
        require(currentSchemaVersion > 0 && nowMs >= 0)
        if (!consentGranted || !memberVisible || key.provider !in approvedProviders) return MediaMetadataCacheDecision.FORBIDDEN
        if (key in ambiguous) return MediaMetadataCacheDecision.SCHEMA_QUARANTINE
        val receipt = latest[key] ?: return if (networkAllowed) MediaMetadataCacheDecision.FETCH_ELIGIBLE
            else MediaMetadataCacheDecision.OFFLINE_MISS
        if (receipt.normalizedSchemaVersion != currentSchemaVersion) {
            return MediaMetadataCacheDecision.SCHEMA_QUARANTINE
        }
        return when (receipt.state) {
            MediaMetadataReceiptState.VERIFIED -> when {
                nowMs < receipt.expiresAtMs -> MediaMetadataCacheDecision.FRESH_VERIFIED
                networkAllowed -> MediaMetadataCacheDecision.FETCH_ELIGIBLE
                staleDisplayAllowed -> MediaMetadataCacheDecision.STALE_DISPLAY_ONLY
                else -> MediaMetadataCacheDecision.OFFLINE_MISS
            }
            MediaMetadataReceiptState.NOT_FOUND -> if (nowMs < receipt.expiresAtMs) {
                MediaMetadataCacheDecision.NEGATIVE_CACHE
            } else if (networkAllowed) MediaMetadataCacheDecision.FETCH_ELIGIBLE
                else MediaMetadataCacheDecision.OFFLINE_MISS
            MediaMetadataReceiptState.RATE_LIMITED, MediaMetadataReceiptState.TEMPORARY_FAILURE -> when {
                receipt.retryNotBeforeMs != null && nowMs < receipt.retryNotBeforeMs ->
                    MediaMetadataCacheDecision.WAIT_FOR_RETRY
                nowMs < receipt.expiresAtMs -> MediaMetadataCacheDecision.WAIT_FOR_RETRY
                networkAllowed -> MediaMetadataCacheDecision.FETCH_ELIGIBLE
                else -> MediaMetadataCacheDecision.OFFLINE_MISS
            }
            MediaMetadataReceiptState.UNSUPPORTED_SCHEMA -> MediaMetadataCacheDecision.SCHEMA_QUARANTINE
        }
    }
}
